package bank;

import org.oxoo2a.sim4da.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.ArrayList;
import java.util.List;

public class BankSimulation {

    // Überweisung
    record Transfer(int amount) implements Message {}

    static class AccountNode extends Node {
        private final int n; // Gesamtzahl Konten
        private int balance; // Kontostand
        private long sentTotal = 0; // zähler gesendete amount
        private long receivedTotal = 0; // zähler empfangene amount

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
                ReceivedMessage received = receive(); // Nachricht aus Mailbox 
                if (received == null) return;

                switch (received.message()) {
                    case Transfer(int amount) -> {
                        balance += amount; // Konto erhöhen beim empfangen
                        receivedTotal += amount;
                        sleep(ThreadLocalRandom.current().nextInt(20, 100)); // Latenz: 20-100ms "unterwegs"
                        startTransfer(); // neue Überweisung
                    }
                    default -> throw new IllegalStateException(
                            "Unerwartete Nachricht: " + received.message());
                }
            }
        }

        private void startTransfer() {
            /** Überweist zufälligen Betrag an zufälligen Knoten */
            if (balance <= 0) return;
            int b = ThreadLocalRandom.current().nextInt(1, balance + 1); //maximal balance überweisen
            int target = pickOtherNode();
            balance -= b; // abbuchen
            sentTotal += b;
            send(new Transfer(b), String.valueOf(target)); // senden
        }

        private int pickOtherNode() {
            /** random anderen Knoten auswählen, sich selbst überspringen */
            int t = ThreadLocalRandom.current().nextInt(n - 1);
            return (t >= Integer.parseInt(nodeName())) ? t + 1 : t;
        }
    }

    public static void main(String[] args) {
        int n = 5; // Anzahl Konten
        int seconds = 5; // Laufzeit
        int startBalance = 1000;
        if (args.length > 0) n = Integer.parseInt(args[0]);
        if (args.length > 1) seconds = Integer.parseInt(args[1]);
        long S = (long) n * startBalance; // Gesamtsumme

        Simulator simulator = Simulator.getInstance();

        // ZUFÄLLIGE Nachricht aus Queue
        SimulationBehavior.setMessageQueueSelectionDistributionFunction(
                RandomValues.getUniformDistribution());

        // n Konten erzeugen (start balance = 1000)
        List<AccountNode> accounts = new ArrayList<>();
        for (int id = 0; id < n; id++) {
            accounts.add(new AccountNode(id, n, startBalance));
        }

        simulator.simulate(seconds);
        simulator.shutdown();

        // Konten + Unterwegs == S
        long balanceSum = 0, totalSent = 0, totalReceived = 0;
        for (AccountNode a : accounts) {
            balanceSum    += a.balance;
            totalSent     += a.sentTotal;
            totalReceived += a.receivedTotal;
        }
        long inTransit = totalSent - totalReceived;   // beim Abbruch noch nicht zugestellt

        System.out.println("Bank-Ergebnis");
        System.out.printf("Startsumme S = %d%n", S);
        System.out.printf("Summe der Konten = %d%n", balanceSum);
        System.out.printf("Geld unterwegs = %d%n", inTransit);
        System.out.printf("Konten + Unterwegs = %d  ->  %s%n",
                balanceSum + inTransit,
                (balanceSum + inTransit == S) ? "OK: kein Geld verloren" : "FEHLER!");
    }
}
