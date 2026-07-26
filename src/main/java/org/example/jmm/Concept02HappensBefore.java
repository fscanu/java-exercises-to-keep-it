// ABOUTME: Demonstrates the concrete happens-before edges the JMM actually gives you, and
// ABOUTME: that without one of them (transitively) there is no cross-thread ordering, full stop.
package org.example.jmm;

/*
 * =====================================================================================
 * Concept #2 - "The happens-before edges that actually exist"
 * =====================================================================================
 *
 * Concept #1 showed that plain accesses on two threads have NO ordering guarantee, so a
 * reader can observe an outcome no single-threaded run could produce. This file shows
 * the flip side: the *specific*, enumerable edges the Java Memory Model (JLS 17.4.5)
 * hands you to buy ordering + visibility back. The complete list of primitive edges:
 *
 *   1. Monitor unlock -> a subsequent lock of the SAME monitor   (synchronized)
 *   2. Volatile write -> a subsequent read of the SAME field     (volatile)
 *   3. Thread.start()  -> every action inside the started thread
 *   4. every action in a thread -> another thread's Thread.join() on it
 *   5. Final-field freeze (end of constructor) -> reads of the reference by other threads
 *      (also: interrupt -> detecting the interrupt; default-init of a field -> first action)
 *
 * Happens-before is a PARTIAL ORDER and it is TRANSITIVE: if A -hb-> B and B -hb-> C then
 * A -hb-> C, even if A and C share no direct edge (see the transitivity demo below). The
 * rule that matters in practice: two actions on different threads are ordered ONLY when a
 * CHAIN of these edges connects them. No chain => no order, and the runtime is free to
 * reorder, cache, and stale-read as it pleases. Full stop.
 *
 * -------------------------------------------------------------------------------------
 * What is actually observable on this (x86) machine, and why the demos look the way they do
 * -------------------------------------------------------------------------------------
 * The FIVE positive edges are SPEC guarantees: they hold on every JVM and every CPU, so
 * demos B-E and G simply PASS - deterministically, forever. That determinism IS the
 * point; it is what an edge buys you.
 *
 * Demo A is the pure NEGATIVE: no edge, so no guarantee, so it is allowed to fail - and it
 * does. On strongly-ordered x86 the visible failure mode is a VISIBILITY one: the JIT proves a
 * plain field is loop-invariant and hoists the read into a register, so a spin-reader
 * never notices the writer's update and loops forever. (On weak hardware - ARM, Power -
 * you would also see ordering/torn-read failures. x86's store buffer gave us Concept #1's
 * (0,0); its strong ordering hides most of the rest, leaving visibility as the clean
 * negative to show here.) We run that reader as a DAEMON and join() with a timeout, so
 * "still alive" == "never saw the write" and the program always terminates.
 *
 * Demo F is BOTH at once, which is why it is last. Its final field is a spec guarantee and
 * holds everywhere. Its NON-final field, and the arrival of the racily-published reference
 * in the first place, are guaranteed by nothing at all and merely happen to work on x86 -
 * the reference because the store buffer drains in nanoseconds and a nanoTime() call in the
 * spin condition stops the JIT hoisting the load, the field because x86 will not reorder the
 * two stores. F therefore prints the SAME number for both fields and labels only one of them
 * as entitled to it. Identical output, one guarantee: that gap is the whole lesson, and it is
 * invisible to anyone reading results instead of reasoning about edges.
 * =====================================================================================
 */
public final class Concept02HappensBefore {

    private static final long WARMUP_MS = 300;        // let C2 compile+hoist the spin loop
    private static final long JOIN_TIMEOUT_MS = 2_000; // cap on waiting for a (maybe stuck) reader
    private static final long DEADLINE_MS = 2_000;     // cap on the lock/racy-publication spins

    // ---- Demo A/B fields: one plain flag, one volatile flag, same spin-reader shape.
    private boolean plainStop;            // NO edge: a write here may never be seen
    private volatile boolean volatileStop; // edge #2: volatile write -> read

    // ---- Demo C fields: guarded by `monitor`, published under the lock (edge #1).
    private final Object monitor = new Object();
    private int monitorPayload;
    private boolean monitorPublished;

    // ---- Demo F: final vs plain field under a genuinely racy (edge-free) publication.
    private Holder racyRef; // plain, NON-volatile: reader observes it through a data race

    private static final class Holder {
        final int fin; // edge #5: the final-field freeze protects this even under a race
        int plain;     // NOT final: under a racy publish the JMM guarantees nothing about this
        Holder(int v) {
            this.fin = v;
            this.plain = v;
        }
    }

    // =================================================================================
    // Demo A - NO EDGE: the write is allowed to never become visible (and here, doesn't)
    // =================================================================================
    private String demoNoEdge() throws InterruptedException {
        plainStop = false;
        // Pure spin on a PLAIN field. Once C2 compiles this loop it hoists the read of
        // plainStop into a register: the thread re-checks a stale cached copy forever.
        Thread reader = new Thread(() -> { while (!plainStop) { /* spin */ } }, "A-reader");
        reader.setDaemon(true); // so a stuck reader never blocks JVM shutdown
        reader.start();

        Thread.sleep(WARMUP_MS); // give C2 time to compile the loop and hoist the read
        plainStop = true;        // plain write: no release edge, may never be observed
        reader.join(JOIN_TIMEOUT_MS);

        boolean stuck = reader.isAlive();
        return stuck
                ? "reader NEVER saw the write (stuck) -> no edge, no visibility  [EXPECTED]"
                : "reader happened to see it (write landed before the JIT hoisted; re-run)";
    }

    // =================================================================================
    // Demo B - EDGE #2 volatile write -> read: the very same loop now terminates
    // =================================================================================
    private String demoVolatileEdge() throws InterruptedException {
        volatileStop = false;
        Thread reader = new Thread(() -> { while (!volatileStop) { /* spin */ } }, "B-reader");
        reader.start();

        Thread.sleep(WARMUP_MS);
        volatileStop = true; // volatile write: every reader's next volatile read sees it
        reader.join(JOIN_TIMEOUT_MS);

        return reader.isAlive()
                ? "UNEXPECTED: reader still stuck with a volatile flag"
                : "reader saw the write promptly -> volatile write->read edge holds";
    }

    // =================================================================================
    // Demo C - EDGE #1 monitor unlock -> lock: publish under a lock, read under the lock
    // =================================================================================
    private String demoMonitorEdge() throws InterruptedException {
        synchronized (monitor) {
            monitorPayload = 0;
            monitorPublished = false;
        }
        Thread writer = new Thread(() -> {
            synchronized (monitor) {      // ... unlock here -hb-> reader's lock below
                monitorPayload = 424242;
                monitorPublished = true;
            }
        }, "C-writer");
        writer.start();

        int seen = -1;
        long deadline = System.nanoTime() + DEADLINE_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            synchronized (monitor) {      // acquiring the SAME monitor imports the writer's writes
                if (monitorPublished) { seen = monitorPayload; break; }
            }
            Thread.onSpinWait();
        }
        writer.join(JOIN_TIMEOUT_MS);

        return seen == 424242
                ? "read 424242 under the lock -> monitor unlock->lock edge holds"
                : "UNEXPECTED: payload not observed (seen=" + seen + ")";
    }

    // =================================================================================
    // Demo D - EDGE #3 Thread.start(): writes done BEFORE start() are visible in the child
    // =================================================================================
    private String demoStartEdge() throws InterruptedException {
        final int[] childSaw = {-1};
        int[] payload = {0};
        payload[0] = 777;                 // written BEFORE start()
        Thread child = new Thread(() -> childSaw[0] = payload[0], "D-child");
        child.start();                    // start() -hb-> everything the child does
        child.join();                     // join to read childSaw[0] safely (edge #4)

        return childSaw[0] == 777
                ? "child saw 777 written before start() -> Thread.start() edge holds"
                : "UNEXPECTED: child saw " + childSaw[0];
    }

    // =================================================================================
    // Demo E - EDGE #4 Thread.join(): a child's writes are visible after join() returns
    // =================================================================================
    private String demoJoinEdge() throws InterruptedException {
        final int[] childWrote = {0};
        Thread child = new Thread(() -> childWrote[0] = 888, "E-child");
        child.start();
        child.join();                     // child's actions -hb-> return of join()

        return childWrote[0] == 888
                ? "saw 888 written by the child after join() -> Thread.join() edge holds"
                : "UNEXPECTED: saw " + childWrote[0];
    }

    // =================================================================================
    // Demo F - EDGE #5 final-field freeze: the final field is safe even under racy publish
    // =================================================================================
    private String demoFinalField() throws InterruptedException {
        racyRef = null;
        // writer and reader share NO happens-before edge with each other (both are merely
        // started by main). The reference is published through a plain field == a data race.
        Thread writer = new Thread(() -> racyRef = new Holder(123456), "F-writer");

        final int[] finSeen = {-1};
        final int[] plainSeen = {-1};
        final boolean[] observed = {false};
        Thread readerThread = new Thread(() -> {
            long deadline = System.nanoTime() + DEADLINE_MS * 1_000_000L;
            Holder h = null;
            // nanoTime() in the condition keeps this read from being hoisted, so we reliably
            // observe the reference; the POINT is what we can trust once we hold it.
            while ((h = racyRef) == null && System.nanoTime() < deadline) { Thread.onSpinWait(); }
            if (h != null) {
                finSeen[0] = h.fin;     // GUARANTEED == 123456 by the final-field freeze, even
                                        // though the reference arrived through a data race.
                plainSeen[0] = h.plain; // NOT guaranteed: may legally read the default 0.
                observed[0] = true;
            }
        }, "F-reader");

        writer.start();
        readerThread.start();
        writer.join(JOIN_TIMEOUT_MS);
        readerThread.join(JOIN_TIMEOUT_MS);

        if (!observed[0]) return "reference not observed this run (visibility) -> see Demo A";
        // Both fields print 123456, and only ONE of them is entitled to. `fin` is final, so the
        // freeze guarantees it on every JVM and every CPU. `plain` is not, so under this racy
        // publication the JMM promises nothing: the store publishing the reference may become
        // visible before the store to the field, leaving the reader holding a valid pointer to a
        // half-built object and reading the default 0. x86's strong store ordering hides that.
        // Identical output, one guarantee: "it worked once" is never proof of correctness.
        return "fin=" + finSeen[0] + " (guaranteed by the freeze), plain=" + plainSeen[0]
                + " (NOT guaranteed - x86 happened to cooperate)";
    }

    // =================================================================================
    // Demo G - TRANSITIVITY: main's write reaches a thread it shares NO direct edge with
    // =================================================================================
    private String demoTransitivity() throws InterruptedException {
        final int[] data = {0};
        final int[] more = {0};
        final int[] t1SawData = {-1};
        final int[] t2SawMore = {-1};
        final int[] t2SawOriginal = {-1};

        data[0] = 42;                                   // (main) the original write

        // main -start-> t1 : t1 sees data == 42
        Thread t1 = new Thread(() -> { t1SawData[0] = data[0]; more[0] = data[0] + 1; }, "G-t1");
        t1.start();
        t1.join();                                      // t1 -join-> main : main sees more == 43

        // main -start-> t2 : t2 sees more (and, transitively, the original data)
        Thread t2 = new Thread(() -> { t2SawMore[0] = more[0]; t2SawOriginal[0] = data[0]; }, "G-t2");
        t2.start();
        t2.join();

        // There is NO direct edge from main's `data = 42` to t2's read of data. The chain
        // main -start-> t1 -join-> main -start-> t2 orders them transitively, so t2 sees 42.
        boolean ok = t1SawData[0] == 42 && more[0] == 43 && t2SawMore[0] == 43 && t2SawOriginal[0] == 42;
        return ok
                ? "t2 saw the original 42 with no direct edge -> happens-before is transitive"
                : "UNEXPECTED: t1=" + t1SawData[0] + " more=" + more[0]
                    + " t2more=" + t2SawMore[0] + " t2orig=" + t2SawOriginal[0];
    }

    private void run() throws InterruptedException {
        System.out.println("Concept #2 - the happens-before edges that actually exist");
        System.out.println("=========================================================");
        line("A  no edge         ", demoNoEdge());
        line("B  volatile w->r   ", demoVolatileEdge());
        line("C  monitor unlk->lk", demoMonitorEdge());
        line("D  Thread.start()  ", demoStartEdge());
        line("E  Thread.join()   ", demoJoinEdge());
        line("F  final-field     ", demoFinalField());
        line("G  transitivity    ", demoTransitivity());
        System.out.println();
        System.out.println("Takeaway: the five edges are the ONLY source of cross-thread ordering.");
        System.out.println("B-E and G hold by spec on every CPU. F is split: fin is guaranteed by");
        System.out.println("the freeze; plain, and the arrival of the reference itself, are not.");
        System.out.println("A has no edge at all: its write was never seen. No order. Full stop.");
    }

    private static void line(String label, String verdict) {
        System.out.println("  [" + label + "] " + verdict);
    }

    public static void main(String[] args) throws InterruptedException {
        new Concept02HappensBefore().run();
    }
}
