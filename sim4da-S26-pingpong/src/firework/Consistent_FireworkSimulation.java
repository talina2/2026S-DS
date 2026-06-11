package firework;

import org.oxoo2a.sim4da.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class Consistent_FireworkSimulation {

    record Token(int hops, int emptyRounds, boolean firedThisLap, long lamport) implements Message {}
    record Terminate() implements Message {}
    record Firework(String origin, long seq, long lamport) implements Message {}
    record RunStats(int n, int rounds, int multicasts, double minMs, double avgMs, double maxMs) {}

    static class RingSegment extends Node {
        private final String nextId;  // Nachfolger im Ring
        private final int k; // leere Runden bis Terminierung
        private double myP; // lokale Zündwahrscheinlichkeit
        private int myMulticasts;
        private long roundStartTs = 0;
        private final List<Double> roundTimes = new ArrayList<>();

        //neu um Konsistenz sicherzustellen:
        private long fireworksReceived = 0;
        private final List<String> receivedOrder = new ArrayList<>();
        private long lamport = 0;



        RingSegment(int id, int nextId, double p, int k) {
            super(String.valueOf(id));          // pid -> String
            this.nextId = String.valueOf(nextId);
            this.myP = p;
            this.k = k;
        }

        @Override
        protected void engage() {
            if (nodeName().equals("0")){
                lamport++;
                send(new Token(0, 0, false, lamport), nextId);
                roundStartTs = System.currentTimeMillis();
            }

            while (true){
                ReceivedMessage received = receive();
                if (received == null) return;

                // Lamport-Update
                long incoming = switch (received.message()) {
                    case Token(var h, var e, var f, var l) -> l;
                    case Firework(var o, var s, var l) -> l;
                    default -> 0;
                };
                lamport = Math.max(lamport, incoming) + 1;

                switch (received.message()) {
                    case Token(int hops, int emptyRounds, boolean firedThisLap, long lts) -> {
                        System.out.printf("Knoten %s | Hop %d | p=%.4f | leere Runden: %d%n",
                                nodeName(), hops, myP, emptyRounds);

                        // Rakete zünden mit Wahrscheinlichkeit myP
                        if (Math.random() < myP) {
                            System.out.printf("Knoten %s zündet Rakete!%n", nodeName());
                            lamport++;
                            broadcast(new Firework(nodeName(), myMulticasts, lamport));
                            myMulticasts++;
                            firedThisLap = true;
                        }
                        myP /= 2;

                        if (nodeName().equals("0") && roundStartTs != 0) {
                            double elapsed = System.currentTimeMillis() - roundStartTs;
                            roundTimes.add(elapsed);
                        }


                        if (nodeName().equals("0")) {

                            if (firedThisLap) {
                                emptyRounds = 0;
                            } else {
                                emptyRounds++;
                            }
                            firedThisLap = false;

                            if (emptyRounds >= k){
                                System.out.printf("Terminierung nach %d leeren Runden%n", k);
                                send(new Terminate(), nextId);
                                return;
                            }
                        }
                        if (nodeName().equals("0")) {
                            roundStartTs = System.currentTimeMillis();
                        }
                        // Token mit aktualisierten Werten weiterschicken
                        lamport++;
                        send(new Token(hops + 1, emptyRounds, firedThisLap, lamport), nextId);
                    }
                    case Firework(String origin, long seq, long lts) -> {
                        fireworksReceived++;
                        receivedOrder.add(lts + "|" + origin + ":" + seq);
                    }
                    case Terminate t -> {
                        System.out.printf("Ring segment %s terminating.%n", nodeName());
                        if (!nodeName().equals("0")){
                            send(t, nextId);
                        }
                        return;
                    }
                    default -> throw new IllegalStateException("Unexpected message: " + received.message());
                }
            }
        }
    }

    static RunStats runSimulation(int n, double p, int k) {
        Simulator simulator = Simulator.getInstance();

        List<RingSegment> nodes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            RingSegment seg = new RingSegment(i, (i + 1) % n, p, k);
            nodes.add(seg);
        }

        //Queue verändern, um Konsistenz zu prüfen
        //SimulationBehavior.setMessageQueueSelectionDistributionFunction(() -> Math.random());
        simulator.simulate();
        simulator.shutdown();

        //Konsistenz prüfen
        long totalFired = 0;
        long totalReceived = 0;

        for(RingSegment seg: nodes){
            totalFired += seg.myMulticasts;
            totalReceived += seg.fireworksReceived;
        }

        long erwartet = totalFired * (long)(n - 1);
        long verloren = erwartet - totalReceived;

        System.out.printf("Konsistenz-Check: erwartet=%d, empfangen=%d, verloren=%d%n",
                erwartet, totalReceived, verloren);
        if (verloren != 0) {
            System.out.println("INKONSISTENZ: nicht jede Rakete erreichte alle Knoten!");
        }

        // Reihenfolge-Konsistenz prüfen: vergleiche alle Knoten mit Knoten 0
        List<String> ref = nodes.get(0).receivedOrder;
        int abweichendeKnoten = 0;
        int erstesAbweichungsPaar = -1;

        for (int i = 1; i < nodes.size(); i++) {
            List<String> other = nodes.get(i).receivedOrder;
            // Vergleiche nur die Raketen, die BEIDE empfangen haben
            String selfPrefix = i + ":";
            List<String> refFiltered = ref.stream()
                    .map(s -> s.substring(s.indexOf('|') + 1))
                    .filter(s -> !s.startsWith(selfPrefix))
                    .toList();
            List<String> otherFiltered = other.stream()
                    .map(s -> s.substring(s.indexOf('|') + 1))
                    .filter(s -> !s.startsWith("0:"))
                    .toList();

            if (!refFiltered.equals(otherFiltered)) {
                abweichendeKnoten++;
                if (erstesAbweichungsPaar == -1) erstesAbweichungsPaar = i;
            }
        }

        System.out.printf("Reihenfolge-Check: %d von %d Knoten haben dieselbe Reihenfolge wie Knoten 0%n",
                nodes.size() - abweichendeKnoten, nodes.size());
        if (abweichendeKnoten > 0) {
            System.out.printf("INKONSISTENZ: %d Knoten sehen Raketen in anderer Reihenfolge als Knoten 0%n",
                    abweichendeKnoten);
            // Beispiel zeigen
            List<String> other = nodes.get(erstesAbweichungsPaar).receivedOrder;
            int minLen = Math.min(ref.size(), other.size());
            for (int j = 0; j < minLen; j++) {
                if (!ref.get(j).equals(other.get(j))) {
                    System.out.printf("  Erste Abweichung an Position %d: Knoten 0 sah '%s', Knoten %d sah '%s'%n",
                            j, ref.get(j), erstesAbweichungsPaar, other.get(j));
                    break;
                }
            }
        }

        //Lamport
        int kausalVerletzungen = 0;
        for (RingSegment seg : nodes) {
            long lastTs = -1;
            for (String entry : seg.receivedOrder) {
                long ts = Long.parseLong(entry.split("\\|")[0]);
                if (ts < lastTs) kausalVerletzungen++;
                lastTs = ts;
            }
        }
        System.out.printf("Kausal-Check: %d Verletzungen über alle Knoten%n", kausalVerletzungen);

        // Statistiken ausgeben
        int totalMulticasts = 0;
        for (RingSegment seg : nodes) {
            totalMulticasts += seg.myMulticasts;
        }
        RingSegment node0 = nodes.get(0);
        int rounds = node0.roundTimes.size();
        double min = 0, avg = 0, max = 0;
        if (!node0.roundTimes.isEmpty()) {
            min = node0.roundTimes.stream().mapToDouble(d -> d).min().getAsDouble();
            avg = node0.roundTimes.stream().mapToDouble(d -> d).average().getAsDouble();
            max = node0.roundTimes.stream().mapToDouble(d -> d).max().getAsDouble();
        }
        System.out.printf("  n=%d: %d Raketen, %d Runden%n", n, totalMulticasts, rounds);
        System.out.printf("  Rundenzeiten (ms): min=%.2f avg=%.2f max=%.2f%n", min, avg, max);
        return new RunStats(n, rounds, totalMulticasts, min, avg, max);
    }

    static void writeCsv(Path path, List<RunStats> results) {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(path))) {
            pw.println("n,rounds,multicasts,min_ms,avg_ms,max_ms");
            for (RunStats s : results) {
                pw.printf(Locale.US, "%d,%d,%d,%.2f,%.2f,%.2f%n",
                        s.n(), s.rounds(), s.multicasts(), s.minMs(), s.avgMs(), s.maxMs());
            }
        } catch (IOException e) {
            System.out.println("CSV-Schreiben fehlgeschlagen: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        Runtime rt = Runtime.getRuntime();
        System.out.printf("JVM max heap:  %d MB%n", rt.maxMemory() / 1_000_000);
        System.out.printf("CPU-Kerne:     %d%n", rt.availableProcessors());

        double p = 1;
        int k = 3;

        runSimulation(512, p, k);
    }
}