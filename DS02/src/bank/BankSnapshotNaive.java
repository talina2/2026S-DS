package bank;

import org.oxoo2a.sim4da.*;
import java.util.concurrent.ThreadLocalRandom;

public class BankSnapshotNaive {

    static final String COORDINATOR = "Coordinator";

    record Transfer(int amount) implements Message {} // ohne Farbe
    record BalanceRequest() implements Message {}
    record BalanceReply(int id, int balance) implements Message {} // Konto -> Koordinator

    static class AccountNode extends Node {
        private final int n;
        private int balance;

        AccountNode(int id, int n, int startBalance) {
            super(String.valueOf(id));
            this.n = n;
            this.balance = startBalance;
        }

        @Override
        protected void engage() {
            startTransfer();
            while (true) {
                ReceivedMessage received = receive();
                if (received == null) return;
                switch (received.message()) {
                    case Transfer(int amount) -> {
                        balance += amount;
                        sleep(ThreadLocalRandom.current().nextInt(20, 100)); // Latenz
                        startTransfer();
                    }
                    case BalanceRequest() ->
                        // einfach den AKTUELLEN Kontostand melden
                        send(new BalanceReply(Integer.parseInt(nodeName()), balance), COORDINATOR);
                    default -> throw new IllegalStateException(
                            "Unerwartete Nachricht: " + received.message());
                }
            }
        }

        private void startTransfer() {
            if (balance <= 0) return;
            int b = ThreadLocalRandom.current().nextInt(1, balance + 1);
            int target = pickOtherNode();
            balance -= b;
            send(new Transfer(b), String.valueOf(target));
        }

        private int pickOtherNode() {
            int t = ThreadLocalRandom.current().nextInt(n - 1);
            return (t >= Integer.parseInt(nodeName())) ? t + 1 : t;
        }
    }

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
            nc.sleep(startDelayMs);
            nc.broadcast(new BalanceRequest()); // nur nach Kontoständen fragen

            long balanceSum = 0;
            int replies = 0;
            int[] balances = new int[n];
            while (replies < n) {
                ReceivedMessage rm = nc.receive();
                if (rm == null) return;
                switch (rm.message()) {
                    case BalanceReply(int id, int balance) -> {
                        balances[id] = balance;
                        balanceSum += balance;
                        replies++;
                    }
                    default -> { }
                }
            }

            long diff = balanceSum - S;
            System.out.println("NAIVER SCHNAPPSCHUSS");
            for (int i = 0; i < n; i++)
                System.out.printf("P%d = %d%n", i, balances[i]);
            System.out.printf("Summe Kontostände = %d  (S = %d)%n", balanceSum, S);
            if (balanceSum == S)
                System.out.println("-> konsistent (kein Geld beim Schnitt unterwegs)");
            else
                System.out.printf("-> INKONSISTENT: %d %s%n",
                        Math.abs(diff), diff < 0 ? "verschwunden" : "entstanden");
        }
    }

    public static void main(String[] args) {
        int n = 5, seconds = 5, startBalance = 1000;
        if (args.length > 0) n = Integer.parseInt(args[0]);
        if (args.length > 1) seconds = Integer.parseInt(args[1]);
        long S = (long) n * startBalance;

        Simulator simulator = Simulator.getInstance();

        // FIFO abschalten
        SimulationBehavior.setMessageQueueSelectionDistributionFunction(
                RandomValues.getUniformDistribution());

        for (int id = 0; id < n; id++) new AccountNode(id, n, startBalance);
        new Coordinator(n, S, 1000);

        simulator.simulate(seconds);
        simulator.shutdown();
    }
}
