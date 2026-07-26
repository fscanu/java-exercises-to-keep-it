// ABOUTME: Traces the happens-before chain of a producer/consumer handoff three ways - plain field,
// ABOUTME: volatile flag, BlockingQueue - naming the exact link where the plain version's chain breaks.
package org.example.jmm;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/*
 * =====================================================================================
 * Concept #7 - "Producer/consumer: where the chain holds, and where it breaks"
 * =====================================================================================
 *
 * Same handoff three times: a producer fills a payload and signals; a consumer waits for
 * the signal and reads the payload. The payload is IDENTICAL and unsynchronized in all
 * three. Only the signalling mechanism changes, and that alone decides whether the
 * consumer is entitled to see the payload. Concept #2 enumerated the edges; this file
 * spends them.
 *
 * -------------------------------------------------------------------------------------
 * VARIANT A - plain field. NO CHAIN.
 * -------------------------------------------------------------------------------------
 *   producer:  (1) payloadA/B/C = 11/22/33      plain writes
 *              (2) plainReady   = true          plain write
 *   consumer:  (3) while (!plainReady) {}       plain read
 *              (4) read payloadA/B/C            plain reads
 *
 *   (1) -po-> (2)   program order, same thread. Fine.
 *   (2) -??-> (3)   NOTHING. No edge exists between a plain write and a plain read.
 *   (3) -po-> (4)   program order, same thread. Fine.
 *
 *   The chain has exactly one hole, at (2)->(3), and one hole is enough to void the whole
 *   thing: without it there is no (1) -hb-> (4), so the consumer is entitled to see stale
 *   payload even after observing ready==true. TWO distinct failures follow, and they are
 *   worth separating because they fail on different hardware:
 *     - LIVENESS: (3) may never observe (2) at all. C2 proves the plain read loop-invariant
 *       and hoists it into a register, so the consumer spins on a cached copy forever. This
 *       is what you actually see on x86, and what Demo A reproduces below.
 *     - ORDERING: even if (3) does observe true, nothing forces (1) to be visible before
 *       (4). The consumer can legally read ready==true and payload 0/0/0. x86's strong store
 *       ordering hides this one; ARM and Power do not.
 *
 * -------------------------------------------------------------------------------------
 * VARIANT B - volatile flag, PLAIN payload. CHAIN COMPLETE.
 * -------------------------------------------------------------------------------------
 *   producer:  (1) payloadA/B/C = 11/22/33      plain writes, UNCHANGED from variant A
 *              (2) volatileReady = true         VOLATILE write  (release)
 *   consumer:  (3) while (!volatileReady) {}    VOLATILE read   (acquire)
 *              (4) read payloadA/B/C            plain reads, UNCHANGED from variant A
 *
 *   (1) -po-> (2)   program order
 *   (2) -hb-> (3)   EDGE #2: volatile write -> subsequent read of the same field
 *   (3) -po-> (4)   program order
 *   therefore (1) -hb-> (4) by transitivity.
 *
 *   The payload fields are still plain. That is the point, and the part people get wrong:
 *   you do not need to mark the payload volatile. One volatile write acts as a release over
 *   EVERYTHING the producer wrote before it, and the matching read acquires all of it. The
 *   flag is a door; the payload walks through it. Marking every payload field volatile
 *   instead would be slower and would still not order them relative to each other.
 *
 * -------------------------------------------------------------------------------------
 * VARIANT C - BlockingQueue. CHAIN COMPLETE, AND THE HANDOFF IS THE EDGE.
 * -------------------------------------------------------------------------------------
 *   producer:  (1) build the payload            plain writes
 *              (2) queue.put(item)
 *   consumer:  (3) item = queue.take()
 *              (4) read the payload             plain reads
 *
 *   (2) -hb-> (3) is guaranteed by the java.util.concurrent package specification: actions
 *   in a thread before placing an object into a concurrent collection happen-before actions
 *   subsequent to the removal of that element in another thread. Internally ArrayBlockingQueue
 *   is a ReentrantLock plus Conditions, so the primitive underneath is edge #1 (unlock ->
 *   lock), but you are entitled to the guarantee without knowing that.
 *
 *   Two things this buys over variant B, neither of them about memory:
 *     - the consumer BLOCKS instead of spinning, so it costs no CPU while waiting;
 *     - the queue carries the value, so there is no shared mutable field at all. Variant B
 *       still has three fields two threads both touch, correct only because of the flag.
 *
 * -------------------------------------------------------------------------------------
 * WHAT YOU WILL SEE. B and C are spec guarantees: they pass on every JVM and every CPU,
 * deterministically. A has no guarantee and fails here on the liveness axis - the consumer
 * hangs - so it runs as a DAEMON with a join timeout and "still alive" means "never saw the
 * signal". Its ordering failure is invisible on this x86 box; see Concept #2 Demo F for why
 * "x86 didn't show it" is never evidence of correctness.
 * =====================================================================================
 */
public final class Concept07ProducerConsumer {

    private static final long WARMUP_MS = 300;         // let C2 compile+hoist the spin loop
    private static final long JOIN_TIMEOUT_MS = 2_000;  // cap on waiting for a (maybe stuck) consumer
    private static final long DEADLINE_MS = 2_000;      // cap on the queue consumer's blocking wait

    // The payload: plain, non-volatile, non-final. Identical across all three variants.
    private int payloadA, payloadB, payloadC;

    private boolean plainReady;             // variant A: carries no edge
    private volatile boolean volatileReady; // variant B: carries edge #2

    private void resetPayload() {
        payloadA = payloadB = payloadC = 0;
    }

    // =================================================================================
    // Demo A - plain flag: the chain breaks at (2)->(3) and the consumer never proceeds
    // =================================================================================
    private String demoPlainHandoff() throws InterruptedException {
        resetPayload();
        plainReady = false;
        final int[] seen = {-1, -1, -1};

        Thread consumer = new Thread(() -> {
            while (!plainReady) { /* spin on a PLAIN read: hoistable, may never reload */ }
            seen[0] = payloadA; seen[1] = payloadB; seen[2] = payloadC;
        }, "A-consumer");
        consumer.setDaemon(true); // a stuck consumer must not block JVM shutdown
        consumer.start();

        Thread.sleep(WARMUP_MS);   // give C2 time to compile the loop and hoist the read
        payloadA = 11; payloadB = 22; payloadC = 33; // (1)
        plainReady = true;                            // (2) plain write: no release
        consumer.join(JOIN_TIMEOUT_MS);

        return consumer.isAlive()
                ? "consumer stuck -> chain broken at (2)->(3), signal never observed  [EXPECTED]"
                : "consumer escaped and read " + fmt(seen) + " - it was never entitled to; re-run";
    }

    // =================================================================================
    // Demo B - volatile flag over a PLAIN payload: one edge carries all three fields
    // =================================================================================
    private String demoVolatileHandoff() throws InterruptedException {
        resetPayload();
        volatileReady = false;
        final int[] seen = {-1, -1, -1};

        Thread consumer = new Thread(() -> {
            while (!volatileReady) { /* spin on a VOLATILE read: reloaded every iteration */ }
            seen[0] = payloadA; seen[1] = payloadB; seen[2] = payloadC; // plain reads, still safe
        }, "B-consumer");
        consumer.start();

        Thread.sleep(WARMUP_MS);   // same warm-up as A, so the only difference is the keyword
        payloadA = 11; payloadB = 22; payloadC = 33; // (1)
        volatileReady = true;                         // (2) volatile write: release over (1)
        consumer.join(JOIN_TIMEOUT_MS);

        if (consumer.isAlive()) return "UNEXPECTED: consumer stuck behind a volatile flag";
        return seen[0] == 11 && seen[1] == 22 && seen[2] == 33
                ? "consumer read " + fmt(seen) + " -> (1)-po->(2)-hb->(3)-po->(4) closes the chain"
                : "UNEXPECTED: consumer read " + fmt(seen);
    }

    // =================================================================================
    // Demo C - BlockingQueue: the handoff itself is the edge, and the wait is not a spin
    // =================================================================================
    private String demoBlockingQueueHandoff() throws InterruptedException {
        BlockingQueue<int[]> queue = new ArrayBlockingQueue<>(1);
        final int[] seen = {-1, -1, -1};
        final boolean[] timedOut = {false};

        Thread consumer = new Thread(() -> {
            try {
                int[] item = queue.poll(DEADLINE_MS, TimeUnit.MILLISECONDS); // (3) blocks, no spin
                if (item == null) { timedOut[0] = true; return; }
                seen[0] = item[0]; seen[1] = item[1]; seen[2] = item[2];     // (4)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "C-consumer");
        consumer.start();

        Thread producer = new Thread(() -> {
            int[] item = {11, 22, 33};      // (1) written before the put, by a different thread
            try {
                queue.put(item);            // (2) -hb-> the take that returns this element
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "C-producer");
        producer.start();

        producer.join(JOIN_TIMEOUT_MS);
        consumer.join(JOIN_TIMEOUT_MS);

        if (timedOut[0]) return "UNEXPECTED: consumer timed out waiting on the queue";
        return seen[0] == 11 && seen[1] == 22 && seen[2] == 33
                ? "consumer read " + fmt(seen) + " -> put -hb-> take, no shared field at all"
                : "UNEXPECTED: consumer read " + fmt(seen);
    }

    private static String fmt(int[] seen) {
        return seen[0] + "/" + seen[1] + "/" + seen[2];
    }

    private void run() throws InterruptedException {
        System.out.println("Concept #7 - producer/consumer: where the happens-before chain holds");
        System.out.println("==================================================================");
        System.out.println("Same plain payload every time; only the signalling mechanism changes.");
        System.out.println();
        line("A  plain field   ", demoPlainHandoff());
        line("B  volatile flag ", demoVolatileHandoff());
        line("C  BlockingQueue ", demoBlockingQueueHandoff());
        System.out.println();
        System.out.println("Takeaway: the payload is unsynchronized in all three. What differs is whether a");
        System.out.println("chain reaches it. A has a hole at (2)->(3) and the consumer hangs. B closes that");
        System.out.println("one link with volatile, and the plain payload rides through it - you do not mark");
        System.out.println("the payload volatile, you mark the door. C removes the shared field entirely and");
        System.out.println("blocks instead of spinning: the same guarantee, and nothing left to get wrong.");
    }

    private static void line(String label, String verdict) {
        System.out.println("  [" + label + "] " + verdict);
    }

    public static void main(String[] args) throws InterruptedException {
        new Concept07ProducerConsumer().run();
    }
}
