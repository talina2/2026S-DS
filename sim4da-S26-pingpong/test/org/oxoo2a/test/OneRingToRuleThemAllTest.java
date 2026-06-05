package org.oxoo2a.test;

import org.junit.jupiter.api.Test;
import org.oxoo2a.sim4da.*;

/**
 * Token-passing ring with a coordinator that injects the initial token,
 * waits, and then injects an end-of-simulation marker. Demonstrates the
 * canonical sim4da idioms after the Message-as-record redesign:
 *
 * <ul>
 *   <li>Messages are records implementing the {@link Message} marker
 *       interface — defining one is a one-liner.</li>
 *   <li>The receive loop is a {@code switch} with record-deconstruction
 *       patterns, binding each message's data directly into locals.</li>
 *   <li>Records are immutable, so passing the token forward is "send a new
 *       Token with the next value" rather than mutating the received one.</li>
 * </ul>
 */
public class OneRingToRuleThemAllTest {

    record Token(int value)   implements Message {}
    record EndMessage()       implements Message {}

    /** Injects the initial token, waits, then injects the EndMessage. */
    static class Coordinator {
        private final NetworkConnection nc = new NetworkConnection("Coordinator");
        private final int waitMillis;

        Coordinator(int waitMillis) {
            this.waitMillis = waitMillis;
            nc.engage(this::run);
        }

        private void run() {
            nc.send(new Token(0), "0");
            nc.sleep(waitMillis);
            nc.send(new EndMessage(), "0");
        }
    }

    /** A node in the ring: forwards tokens (incremented) and end markers. */
    static class RingSegment extends Node {
        private final String nextId;

        RingSegment(int id, int nextId) {
            super(String.valueOf(id));
            this.nextId = String.valueOf(nextId);
        }

        @Override
        protected void engage() {
            while (true) {
                ReceivedMessage received = receive();
                if (received == null) return;       // simulation has been shut down
                switch (received.message()) {
                    case Token(int v) -> {
                        System.out.printf("Ring segment %s received token %d from %s%n",
                                          nodeName(), v, received.sender());
                        sleep(500);
                        send(new Token(v + 1), nextId);
                    }
                    case EndMessage e -> {
                        System.out.printf("Ring segment %s terminating.%n", nodeName());
                        send(e, nextId);
                        return;
                    }
                    default -> throw new IllegalStateException(
                            "Unexpected message: " + received.message());
                }
            }
        }
    }

    @Test
    void testOneRingToRuleThemAll() {
        final int ringSize = 5;
        Simulator simulator = Simulator.getInstance();

        for (int i = 0; i < ringSize; i++) {
            new RingSegment(i, (i + 1) % ringSize);
        }
        new Coordinator(5000);

        simulator.simulate();
        simulator.shutdown();
    }
}
