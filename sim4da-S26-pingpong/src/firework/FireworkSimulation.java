package firework;

import org.oxoo2a.sim4da.Message;
import org.oxoo2a.sim4da.Node;
import org.oxoo2a.sim4da.ReceivedMessage;
import org.oxoo2a.sim4da.Simulator;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FireworkSimulation {

    record Token(int hops, int emptyRounds, boolean firedThisLap) implements Message {}
    record Terminate() implements Message {}
    record Firework() implements Message {}
    record RunStats(int n, int rounds, int multicasts, double minMs, double avgMs, double maxMs) {}

    static class RingSegment extends Node {
        private final String nextId;  // Nachfolger im Ring
        private final int k; // leere Runden bis Terminierung
        private double myP; // lokale Zündwahrscheinlichkeit
        private int myMulticasts;
        private long roundStartTs = 0;
        private final List<Double> roundTimes = new ArrayList<>();

        RingSegment(int id, int nextId, double p, int k) {
            super(String.valueOf(id));          // pid -> String
            this.nextId = String.valueOf(nextId);
            this.myP = p;
            this.k = k;
        }

        @Override
        protected void engage() {
            if (nodeName().equals("0")){
                send(new Token(0, 0, false), nextId);
                roundStartTs = System.currentTimeMillis();
            }

            while (true){
                ReceivedMessage received = receive();
                if (received == null) return;

                switch (received.message()) {
                    case Token(int hops, int emptyRounds, boolean firedThisLap) -> {
                        System.out.printf("Knoten %s | Hop %d | p=%.4f | leere Runden: %d%n",
                                nodeName(), hops, myP, emptyRounds);

                        // Rakete zünden mit Wahrscheinlichkeit myP
                        if (Math.random() < myP) {
                            System.out.printf("Knoten %s zündet Rakete!%n", nodeName());
                            broadcast(new Firework());
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
                        send(new Token(hops + 1, emptyRounds, firedThisLap), nextId);
                    }
                    case Firework f -> {
                        // empfangene Rakete: nichts zu tun
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

        simulator.simulate();
        simulator.shutdown();

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

        double p = 1.0;
        int k = 3;
        int maxN = 0;
        List<RunStats> results = new ArrayList<>();

        int n = 2;
        while (n <= 16384) {
            System.out.printf("%n=== n = %d ===%n", n);
            try {
                results.add(runSimulation(n, p, k));
                maxN = n;
                n *= 2;
            } catch (Throwable e) {
                System.out.printf("FEHLGESCHLAGEN bei n=%d: %s%n", n, e);
                break;
            }
        }
        writeCsv(Path.of("firework_results.csv"), results);
        System.out.printf("%nMaximales n: %d%n", maxN);
    }
}