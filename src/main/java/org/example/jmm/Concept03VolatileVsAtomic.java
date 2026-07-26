// ABOUTME: Demonstrates what volatile actually buys (visibility + ordering) and what it does
// ABOUTME: NOT (atomicity): a volatile counter++ is still a race that silently loses updates.
package org.example.jmm;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * =====================================================================================
 * Concept #3 - "What volatile actually buys you"
 * =====================================================================================
 *
 * volatile makes EACH individual read and EACH individual write of the field:
 *
 *   - VISIBLE   : a write is flushed and later reads see it, never a stale cached copy.
 *                 (This is the guarantee Concept #2 Demo A lacked, so its reader hung.)
 *   - ORDERED   : a volatile WRITE is a release - no earlier action (even to plain
 *                 fields) may be reordered after it; a volatile READ is an acquire - no
 *                 later action may be reordered before it. So a volatile flag can safely
 *                 publish a whole payload of plain fields written before it.
 *
 * volatile does NOT make a compound read-modify-write atomic. `counter++` is three
 * separate actions:
 *
 *        int tmp = counter;   // volatile read   (atomic, visible)
 *        tmp = tmp + 1;       // add             (thread-local)
 *        counter = tmp;       // volatile write  (atomic, visible)
 *
 * Each of the three is individually atomic and visible - but nothing stops another
 * thread from squeezing its own read-add-write BETWEEN this thread's read and write.
 * Both read the same value, both add 1, both store the same result: two increments, one
 * winner. The update is lost, silently. volatile bought visibility and ordering; it did
 * NOT buy atomicity of the trio, and no amount of volatile ever will.
 *
 * The fix is not "more volatile" - it is an atomic read-modify-write. Either a CAS-based
 * primitive (AtomicInteger.incrementAndGet, the deep-dive belongs to Concept #5) or
 * mutual exclusion (synchronized). Both demos below recover the exact count; the plain
 * volatile counter does not.
 *
 * On this multicore x86 box the lost-update demo loses a large, obvious fraction of the
 * increments every run. Unlike Concept #1/#2, this failure is NOT x86-specific: a
 * non-atomic read-modify-write races on EVERY architecture, strong or weak.
 * =====================================================================================
 */
public final class Concept03VolatileVsAtomic {

    private static final int NUM_THREADS = 4;
    private static final int INCREMENTS = 1_000_000;         // per thread
    private static final long EXPECTED = (long) NUM_THREADS * INCREMENTS;

    private static final long WARMUP_MS = 300;
    private static final long JOIN_TIMEOUT_MS = 2_000;

    // ---- Demo 1/2 counters, one per strategy.
    private volatile int volatileCounter;                   // NOT atomic under ++
    private final AtomicInteger atomicCounter = new AtomicInteger();
    private int guardedCounter;
    private final Object lock = new Object();

    // ---- Demo 3 (visibility) flag.
    private volatile boolean visibleFlag;

    // ---- Demo 4 (ordering / safe publication): plain payload + volatile publish flag.
    private int px, py, pz;
    private volatile boolean published;

    /** Run NUM_THREADS threads, each invoking `op` INCREMENTS times, released together. */
    private long runConcurrently(Runnable op) throws InterruptedException {
        CountDownLatch go = new CountDownLatch(1);
        Thread[] workers = new Thread[NUM_THREADS];
        for (int t = 0; t < NUM_THREADS; t++) {
            workers[t] = new Thread(() -> {
                await(go);                                  // all threads start together
                for (int i = 0; i < INCREMENTS; i++) op.run();
            }, "worker-" + t);
            workers[t].start();
        }
        long start = System.nanoTime();
        go.countDown();                                     // release the herd
        for (Thread w : workers) w.join();
        return System.nanoTime() - start;
    }

    // =================================================================================
    // Demo 1 - ATOMICITY NOT bought: volatile counter++ silently loses updates
    // =================================================================================
    private void demoLostUpdates() throws InterruptedException {
        volatileCounter = 0;
        long ns = runConcurrently(() -> volatileCounter++); // read-modify-write RACE
        long observed = volatileCounter;
        long lost = EXPECTED - observed;
        System.out.printf("  [1 volatile ++     ] expected %,d  observed %,d  LOST %,d (%.1f%%)  [%d ms]%n",
                EXPECTED, observed, lost, 100.0 * lost / EXPECTED, ns / 1_000_000);
        System.out.println("      volatile made each read/write atomic - but not the read-modify-write trio.");
    }

    // =================================================================================
    // Demo 2a - atomic RMW via CAS: AtomicInteger recovers the exact count
    // =================================================================================
    private void demoAtomic() throws InterruptedException {
        atomicCounter.set(0);
        long ns = runConcurrently(atomicCounter::incrementAndGet); // one atomic step
        long observed = atomicCounter.get();
        System.out.printf("  [2a AtomicInteger  ] expected %,d  observed %,d  LOST %,d  [%d ms]%n",
                EXPECTED, observed, EXPECTED - observed, ns / 1_000_000);
    }

    // =================================================================================
    // Demo 2b - atomic RMW via mutual exclusion: synchronized also recovers it
    // =================================================================================
    private void demoSynchronized() throws InterruptedException {
        guardedCounter = 0;
        long ns = runConcurrently(() -> { synchronized (lock) { guardedCounter++; } });
        long observed = guardedCounter;
        System.out.printf("  [2b synchronized ++] expected %,d  observed %,d  LOST %,d  [%d ms]%n",
                EXPECTED, observed, EXPECTED - observed, ns / 1_000_000);
    }

    // =================================================================================
    // Demo 3 - VISIBILITY bought: a volatile flag stops a spin-reader (cf. Concept #2 A)
    // =================================================================================
    private String demoVisibility() throws InterruptedException {
        visibleFlag = false;
        Thread reader = new Thread(() -> { while (!visibleFlag) { /* spin */ } }, "vis-reader");
        reader.start();
        Thread.sleep(WARMUP_MS);
        visibleFlag = true;
        reader.join(JOIN_TIMEOUT_MS);
        return reader.isAlive()
                ? "UNEXPECTED: reader still stuck with a volatile flag"
                : "spin-reader stopped promptly -> volatile buys visibility (Concept #2 Demo A hung)";
    }

    // =================================================================================
    // Demo 4 - ORDERING bought: a volatile write publishes the plain payload before it
    // =================================================================================
    private String demoOrdering() throws InterruptedException {
        px = 0; py = 0; pz = 0;
        published = false;
        // reader must observe the FULL payload once it sees `published`, because the
        // volatile write acts as a release fence over the three plain writes before it.
        final int[] seen = {-1, -1, -1};
        Thread reader = new Thread(() -> {
            while (!published) { /* spin on the volatile flag */ }
            seen[0] = px; seen[1] = py; seen[2] = pz;       // plain reads, guaranteed fresh
        }, "pub-reader");
        Thread writer = new Thread(() -> {
            px = 11; py = 22; pz = 33;                       // plain payload...
            published = true;                                // ...released by the volatile write
        }, "pub-writer");
        reader.start();
        writer.start();
        writer.join(JOIN_TIMEOUT_MS);
        reader.join(JOIN_TIMEOUT_MS);

        boolean ok = seen[0] == 11 && seen[1] == 22 && seen[2] == 33;
        return ok
                ? "reader saw the whole payload {11,22,33} after the flag -> volatile write->read orders prior plain writes"
                : "UNEXPECTED torn payload: {" + seen[0] + "," + seen[1] + "," + seen[2] + "}";
    }

    private void run() throws InterruptedException {
        System.out.println("Concept #3 - what volatile buys: visibility + ordering, NOT atomicity");
        System.out.println("===================================================================");
        System.out.println(NUM_THREADS + " threads x " + String.format("%,d", INCREMENTS)
                + " increments = " + String.format("%,d", EXPECTED) + " expected");
        System.out.println();
        demoLostUpdates();   // the star: volatile is NOT atomic
        demoAtomic();        // fix via CAS
        demoSynchronized();  // fix via mutual exclusion
        System.out.println();
        line("3 visibility ", demoVisibility());
        line("4 ordering   ", demoOrdering());
        System.out.println();
        System.out.println("Takeaway: volatile guarantees each single read/write is visible and ordered,");
        System.out.println("but a read-modify-write (counter++) is three steps and still races. Atomicity");
        System.out.println("needs a CAS primitive (AtomicInteger) or a lock (synchronized), not volatile.");
    }

    private static void line(String label, String verdict) {
        System.out.println("  [" + label + "] " + verdict);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for start gate", e);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        new Concept03VolatileVsAtomic().run();
    }
}
