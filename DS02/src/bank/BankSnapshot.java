package bank;

import org.oxoo2a.sim4da.*;
import java.util.concurrent.ThreadLocalRandom;

public class BankSnapshot {

    static final String COORDINATOR = "Coordinator";
    static int latencyMaxMs = 100; // Latenz-Obergrenze

    // zusätzlich zu Überweisung auch Farbe übergeben
    record Transfer(int amount, boolean senderBlack) implements Message {}
    record SnapshotRequest() implements Message {} // state? vom Koordinator an Knoten
    record StateReport(int id, int balance, long whiteSent, long whiteRecv) implements Message {} // Konto infos -> Koordinator
    record ChannelReport(int from, int to, int amount) implements Message {} // eingefangener Kanalinhalt

    static class AccountNode extends Node {
        private final int n; // Gesamtzahl Konten
        private int balance; // Kontostand
        private boolean black = false; // Farbe: false = weiß, true = schwarz
        private long whiteSent = 0; // verschickte weiße Überweisungen
        private long whiteRecv = 0; // empfangene weiße Überweisungen

        AccountNode(int id, int n, int startBalance) {
            super(String.valueOf(id));
            this.n = n;
            this.balance = startBalance;
        }

        @Override
        protected void engage() {
            // Prozesse starten (Einmal Startschuss pro Prozess)
            startTransfer();

            // jede empfangene Überweisung löst eine neue aus
            while (true) {
                ReceivedMessage received = receive();
                if (received == null) return;

                switch (received.message()) {
                    case Transfer(int amount, boolean senderBlack) -> {
                        // Sonderfall Nachricht aus der Zukunft (weiß empfängt schwarz) -> snapshot
                        if (!black && senderBlack) takeSnapshot();

                        // schwarz empfängt weiß -> report an Coordinator
                        if (black && !senderBlack) send(new ChannelReport(
                                Integer.parseInt(received.sender()), Integer.parseInt(nodeName()), amount), COORDINATOR);

                        // weiß empfängt weiß -> zählt schon vor dem Schnitt
                        if (!black && !senderBlack) whiteRecv++;

                        balance += amount;
                        sleep(ThreadLocalRandom.current().nextInt(20, latencyMaxMs)); // Latenz
                        startTransfer();
                    }
                    case SnapshotRequest() -> {
                        takeSnapshot(); 
                    }
                    default -> throw new IllegalStateException(
                            "Unerwartete Nachricht: " + received.message());
                }
            }
        }

        private void takeSnapshot() {
            /** Zustand sichern und schwarz werdeb*/
            if (black) return;
            black = true;
            send(new StateReport(Integer.parseInt(nodeName()), balance, whiteSent, whiteRecv), COORDINATOR);
        }

        private void startTransfer() {
            /** Überweist zufälligen Betrag an zufälligen Knoten */
            if (balance <= 0) return;
            int b = ThreadLocalRandom.current().nextInt(1, balance + 1); //maximal balance überweisen
            int target = pickOtherNode();
            balance -= b; // abbuchen
            if (!black) whiteSent++; // weiße Überweisung mitzählen (für Terminierung)
            send(new Transfer(b, black), String.valueOf(target)); // eigene Farbe mitgeben
        }

        private int pickOtherNode() {
            /** random anderen Knoten auswählen, sich selbst überspringen */
            int t = ThreadLocalRandom.current().nextInt(n - 1);
            return (t >= Integer.parseInt(nodeName())) ? t + 1 : t;
        }
    }

    // Koordinator: löst Schnappschuss aus und sammelt Meldungen
    static class Coordinator {
        private final NetworkConnection nc = new NetworkConnection(COORDINATOR);
        private final int n;
        private final long S;
        private final int startDelayMs;

        Coordinator(int n, long S, int startDelayMs) {
            this.n = n;
            this.S = S;
            this.startDelayMs = startDelayMs;
            nc.engage(this::run);
        }

        private void run() {
            nc.sleep(startDelayMs); // Bank laufen lassen
            nc.broadcast(new SnapshotRequest()); // state? an alle

            long balanceSum = 0, channelSum = 0;
            long whiteSentTotal = 0, whiteRecvTotal = 0;
            int states = 0, channels = 0;
            long expectedChannels = Long.MAX_VALUE;
            int[] balances = new int[n];
            StringBuilder channelLines = new StringBuilder();

            while (states < n || channels < expectedChannels) {
                ReceivedMessage rm = nc.receive();
                if (rm == null) return;
                switch (rm.message()) {
                    case StateReport(int id, int balance, long wSent, long wRecv) -> {
                        balances[id] = balance;
                        balanceSum += balance;
                        whiteSentTotal += wSent;
                        whiteRecvTotal += wRecv;
                        states++;
                        if (states == n)
                            expectedChannels = whiteSentTotal - whiteRecvTotal;
                    }
                    case ChannelReport(int from, int to, int amount) -> {
                        channelSum += amount;
                        channels++;
                        channelLines.append(String.format("P%d -> P%d: %d%n", from, to, amount));
                    }
                    default -> { }
                }
            }

            long total = balanceSum + channelSum;
            System.out.println("SCHNAPPSCHUSS");
            System.out.println("Kontostände:");
            for (int i = 0; i < n; i++)
                System.out.printf("P%d = %d%n", i, balances[i]);
            System.out.println("Kanäle (unterwegs):");
            System.out.print(!channelLines.isEmpty() ? channelLines.toString() : "(keine)\n");
            System.out.printf("Summe Kontostände = %d%n", balanceSum);
            System.out.printf("Summe unterwegs = %d%n", channelSum);
            System.out.printf("Gesamt = %d   (S = %d)  ->  %s%n",
                    total, S, (total == S) ? "KONSISTENT" : "INKONSISTENT");

            // Statistik Kontrollnachrichten pro Schnappschuss
            int control = n + states + channels;
            System.out.printf("Kontrollnachrichten = %d (%d Anfragen + %d StateReports + %d ChannelReports)%n",
                    control, n, states, channels);

            // Anteil des Geldes, das beim Schnitt unterwegs war
            System.out.printf("Anteil unterwegs = %.1f%%%n", channelSum * 100.0 / S);
        }
    }

    public static void main(String[] args) {
        int n = 5, seconds = 5, startBalance = 1000;
        if (args.length > 0) n = Integer.parseInt(args[0]);
        if (args.length > 1) seconds = Integer.parseInt(args[1]);
        if (args.length > 2) latencyMaxMs = Integer.parseInt(args[2]); // Frequenz
        long S = (long) n * startBalance;

        Simulator simulator = Simulator.getInstance();

        // FIFO abschalten
        SimulationBehavior.setMessageQueueSelectionDistributionFunction(
                RandomValues.getUniformDistribution());

        for (int id = 0; id < n; id++) new AccountNode(id, n, startBalance);
        new Coordinator(n, S, 1000); // Schnappschuss 1s nach Start auslösen

        simulator.simulate(seconds);
        simulator.shutdown();
    }
}
