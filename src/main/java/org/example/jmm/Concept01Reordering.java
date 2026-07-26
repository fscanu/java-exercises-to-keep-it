// ABOUTME: Demonstrates that the JMM lets one thread's stores/loads appear reordered to
// ABOUTME: another thread, producing an outcome no single-threaded execution could ever yield.
package org.example.jmm;

/*
 * =====================================================================================
 * Concept #1 - "Why reordering is legal at all"
 * =====================================================================================
 *
 * The Java Language Spec (JLS 17.4, the Java Memory Model) does NOT promise that the
 * statements of one thread become visible to ANOTHER thread in the order they were
 * written. It only promises a partial order called "happens-before". For an observer
 * running on a different thread, two memory actions are ordered ONLY when a chain of
 * happens-before edges connects them: monitor unlock -> lock, volatile write -> read,
 * Thread.start, Thread.join, final-field freeze, and their transitive closure.
 *
 * Inside a SINGLE thread the language still guarantees "as-if-serial" execution: a
 * thread always sees its OWN actions as if they ran in program order. But as-if-serial
 * is a single-thread promise; it constrains what THIS thread can observe and says
 * nothing about what a DIFFERENT thread may observe. The JIT compiler (instruction
 * scheduling), the CPU (store buffers, out-of-order execution) and the cache protocol
 * are all free to reorder independent accesses as long as the OWNING thread cannot
 * tell the difference. Another thread very much can.
 *
 * We make that reordering visible with the classic "store buffer" / Dekker shape:
 *
 *      shared:  x = 0, y = 0          (plain int fields; NO happens-before links them)
 *
 *      Thread A                 Thread B
 *      --------                 --------
 *      x = 1;       (A1)        y = 1;       (B1)
 *      aSeesY = y;  (A2)        bSeesX = x;  (B2)
 *
 * Enumerate EVERY interleaving of these four steps under a model that respects program
 * order (i.e. sequential consistency). Whatever the order, at least one read runs after
 * the other thread's write, so at least one reader observes 1. The pair
 *
 *      (aSeesY == 0 && bSeesX == 0)
 *
 * is therefore IMPOSSIBLE under sequential consistency. There is no legal interleaving
 * that produces it.
 *
 * Yet we observe it. On a normal x86 box it surfaces within a few hundred thousand
 * rounds. The only explanation: each thread's store (A1 / B1) became visible AFTER its
 * own load (A2 / B2) - a StoreLoad reorder in the CPU's store buffer - or the JIT
 * hoisted the load above the store. Both are legal for exactly one reason: nothing ties
 * A's actions to B's actions with a happens-before edge. No edge, no order, full stop.
 *
 * (The sequel - making (0,0) vanish by turning x/y volatile, i.e. adding the ordering
 * edge - belongs to the "what volatile actually buys you" concept, not this file.)
 *
 * -------------------------------------------------------------------------------------
 * A NOTE ON THE TEST HARNESS (why not just use a CyclicBarrier per round?)
 * -------------------------------------------------------------------------------------
 * To catch (0,0) the two 2-instruction pairs must execute in the SAME tiny window. A
 * heavyweight barrier (locks, park/unpark) hides the effect twice over: its internal
 * fences drain the store buffer, and its long, jittery wake-up path means the two
 * actors almost never reach their pair at the same instant. So we release both actors
 * with a SINGLE volatile "gate" write and spin-wait on it (Thread.onSpinWait). On x86 a
 * volatile read/write of the gate is a plain mov with no fence BETWEEN the store and the
 * load under test - the pair stays unfenced, and a shared gate write lands in both
 * actors' caches at nearly the same moment, so their pairs actually overlap. This is the
 * same trick the OpenJDK jcstress tool uses.
 * =====================================================================================
 */
public final class Concept01Reordering {

    /** Rounds to run. The forbidden (0,0) is rare, so we need a lot of them. */
    private static final int ITERATIONS = 10_000_000;

    // --- The shared state under test. Plain (non-volatile) on purpose: plain accesses
    //     carry NO happens-before guarantee to another thread, which is the whole point.
    private int x;
    private int y;

    // --- Per-round results. Each actor publishes what it read here; the main thread
    //     reads them back only AFTER the round's happens-before edge (see `done*`), so
    //     THESE reads are safe. Only x and y are deliberately left racy.
    private int aSeesY;
    private int bSeesX;

    // --- Lightweight spin coordination (no locks, no park/unpark).
    //     gate: main advances it to release BOTH actors into round N at once.
    //           volatile => actors' spin re-reads it and the release edge orders main's
    //           field reset (below) before the actors' pair.
    //     doneA/doneB: each actor publishes the round number it just finished.
    //           volatile => main's read of aSeesY/bSeesX happens-after the actor's pair.
    private volatile long gate;
    private volatile long doneA;
    private volatile long doneB;

    private Runnable actorA() {
        return () -> {
            long round = 0;
            while (round < ITERATIONS) {
                long next = round + 1;
                while (gate < next) Thread.onSpinWait();  // wait for main to open round
                x = 1;          // A1: store
                aSeesY = y;     // A2: load  (may be reordered before A1 for observers)
                doneA = next;   // publish completion (release edge to main)
                round = next;
            }
        };
    }

    private Runnable actorB() {
        return () -> {
            long round = 0;
            while (round < ITERATIONS) {
                long next = round + 1;
                while (gate < next) Thread.onSpinWait();
                y = 1;          // B1: store
                bSeesX = x;     // B2: load  (may be reordered before B1 for observers)
                doneB = next;
                round = next;
            }
        };
    }

    private void run() throws InterruptedException {
        Thread a = new Thread(actorA(), "actor-A");
        Thread b = new Thread(actorB(), "actor-B");
        a.start();
        b.start();

        long bothOne = 0;   // (1,1): each saw the other's write - fully ordered case
        long aOnly = 0;     // (1,0): only A saw B's write
        long bOnly = 0;     // (0,1): only B saw A's write
        long neither = 0;   // (0,0): the "impossible under SC" case - reordering caught

        for (long round = 1; round <= ITERATIONS; round++) {
            // Reset before the actors run. Ordered before their pair by the `gate`
            // release edge below, so both actors are guaranteed to start this round at 0.
            x = 0;
            y = 0;
            aSeesY = 0;
            bSeesX = 0;

            gate = round;                                   // open the round for both actors
            while (doneA < round || doneB < round) Thread.onSpinWait();  // await both

            // Safe reads: doneA/doneB acquire happens-after each actor's pair.
            int a1 = aSeesY;
            int b1 = bSeesX;
            if (a1 == 1 && b1 == 1) bothOne++;
            else if (a1 == 1) aOnly++;
            else if (b1 == 1) bOnly++;
            else neither++;    // a1 == 0 && b1 == 0
        }

        a.join();
        b.join();

        report(bothOne, aOnly, bOnly, neither);
    }

    private static void report(long bothOne, long aOnly, long bOnly, long neither) {
        System.out.println("Rounds run : " + ITERATIONS);
        System.out.println("(1,1) both saw the other's write : " + bothOne);
        System.out.println("(1,0) only A saw B's write        : " + aOnly);
        System.out.println("(0,1) only B saw A's write        : " + bOnly);
        System.out.println("(0,0) NEITHER saw the other       : " + neither
                + "   <-- forbidden under sequential consistency");
        System.out.println();
        if (neither > 0) {
            System.out.println("REORDERING OBSERVED: " + neither + " time(s).");
            System.out.println("No program-order interleaving can produce (0,0). Because no");
            System.out.println("happens-before edge links actor A to actor B, the JMM lets their");
            System.out.println("stores and loads be reordered - and another thread saw the result.");
        } else {
            System.out.println("Not observed this run. The JMM PERMITS a sequentially-consistent");
            System.out.println("execution too, and store buffers do not always leak. Re-run, raise");
            System.out.println("ITERATIONS, or try different hardware; the outcome stays legal either way.");
        }
    }

    public static void main(String[] args) throws InterruptedException {
        new Concept01Reordering().run();
    }
}
