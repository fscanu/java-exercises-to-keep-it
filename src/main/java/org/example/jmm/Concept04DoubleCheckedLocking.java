// ABOUTME: Demonstrates double-checked locking done right (and wrong): why the unsynchronized
// ABOUTME: read is the dangerous one, why synchronized alone won't fix it, plus holder & enum.
package org.example.jmm;

import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/*
 * =====================================================================================
 * Concept #4 - "Double-checked locking, correctly"
 * =====================================================================================
 *
 * Lazy singleton, the naive way:
 *
 *      if (instance == null) instance = new Singleton();   // NO synchronization
 *
 * Under a race, several threads read `instance == null` at once, and EACH constructs
 * one. You do not get a singleton - you get N of them. (Part A, variant "no-sync",
 * reproduces this reliably even on x86.) So you clearly need synchronization somewhere.
 *
 * The cheap-looking cure is double-checked locking: check without the lock (fast), and
 * only lock + re-check when it looks unset:
 *
 *      if (instance == null) {                 // (1) UNSYNCHRONIZED read  <-- the danger
 *          synchronized (Lock.class) {
 *              if (instance == null) {         // (2) synchronized re-check
 *                  instance = new Singleton(); // (3) construct + publish
 *              }
 *          }
 *      }
 *      return instance;
 *
 * WHY THE UNSYNCHRONIZED READ (1) IS THE DANGEROUS ONE.
 *   `instance = new Singleton()` is not atomic. It is: allocate, run the constructor
 *   (write the object's fields), then publish the reference into `instance`. With a
 *   PLAIN (non-volatile) field there is no happens-before edge forcing the field writes
 *   to land before the reference write as seen by ANOTHER thread. A second thread taking
 *   the fast path at (1) can therefore read a non-null `instance` yet see the object's
 *   fields at their DEFAULTS - a half-built object. It returns garbage, no exception.
 *
 * WHY synchronized ALONE (without volatile) DOES NOT FIX IT.
 *   The synchronized block creates an unlock -> lock edge ONLY between threads that both
 *   enter it. The fast-path reader at (1) never takes the lock, so it shares NO edge with
 *   the constructing thread. Adding synchronized to the slow path does nothing for the
 *   reader that skips it. The field itself must carry the edge: it must be `volatile`.
 *   volatile write (3) is a release over the constructor's writes; volatile read (1) is
 *   an acquire - so if (1) sees non-null, it sees a fully-built object. (Part A variant
 *   "correct-dcl".)
 *
 * TWO IDIOMS THAT SIDESTEP THE WHOLE PROBLEM.
 *   - Initialization-on-demand HOLDER: put the instance in a static nested class. The JVM
 *     already serialises class initialization with a lock and publishes the results
 *     safely; referencing the holder for the first time triggers it. Lazy, thread-safe,
 *     zero explicit synchronization, no volatile. (variant "holder".)
 *   - ENUM singleton: a single-constant enum. The JVM constructs the constant exactly
 *     once during class init and publishes it safely; also free from the reflection /
 *     serialization attacks that break hand-rolled singletons. (variant "enum".)
 *
 * -------------------------------------------------------------------------------------
 * WHAT IS OBSERVABLE HERE. Part A's "no-sync makes N instances" fires on x86 - it is a
 * check-then-act race, not a memory-ordering one. The half-built-object bug (Part B) is
 * a memory-ordering bug that strongly-ordered x86 hides: Part B runs millions of race
 * rounds and reports the real count (0 on this box). The code is broken regardless -
 * "0 on x86" is not "correct", it is "this CPU happens not to expose it". On ARM/Power it
 * does. (Footnote: had Payload's fields been `final`, the final-field freeze would make
 * even the plain-field publish safe - see Concept #2 - which is why the canonical broken
 * DCL uses non-final fields.)
 * =====================================================================================
 */
public final class Concept04DoubleCheckedLocking {

    private static final int RACERS = 32;                 // Part A: simultaneous getInstance() callers
    private static final long PUBLICATION_ROUNDS = 5_000_000; // Part B: publication race rounds

    // ---------------------------------------------------------------------------------
    // Part A - the singleton variants. Each counts its own constructions.
    // ---------------------------------------------------------------------------------

    /** V0 - no synchronization: a check-then-act race that builds multiple instances. */
    static final class NoSync {
        static final AtomicInteger constructions = new AtomicInteger();
        private static NoSync instance;
        private NoSync() { constructions.incrementAndGet(); }
        static NoSync getInstance() {
            if (instance == null) instance = new NoSync();   // race: many see null at once
            return instance;
        }
    }

    /** V1 - synchronized accessor: correct, but every call pays for the lock. */
    static final class SyncMethod {
        static final AtomicInteger constructions = new AtomicInteger();
        private static SyncMethod instance;
        private SyncMethod() { constructions.incrementAndGet(); }
        static synchronized SyncMethod getInstance() {
            if (instance == null) instance = new SyncMethod();
            return instance;
        }
    }

    /** V2 - DCL WITHOUT volatile: single instance on x86, but publication is unsafe (Part B). */
    static final class BrokenDcl {
        static final AtomicInteger constructions = new AtomicInteger();
        private static BrokenDcl instance;                   // NOT volatile - the defect
        private BrokenDcl() { constructions.incrementAndGet(); }
        static BrokenDcl getInstance() {
            if (instance == null) {                          // unsynchronized read (dangerous)
                synchronized (BrokenDcl.class) {
                    if (instance == null) instance = new BrokenDcl();
                }
            }
            return instance;
        }
    }

    /** V3 - CORRECT DCL: volatile field carries the publication edge; fast path stays lock-free. */
    static final class CorrectDcl {
        static final AtomicInteger constructions = new AtomicInteger();
        private static volatile CorrectDcl instance;         // volatile = safe publication
        private CorrectDcl() { constructions.incrementAndGet(); }
        static CorrectDcl getInstance() {
            CorrectDcl local = instance;                     // read the volatile once (perf idiom)
            if (local == null) {
                synchronized (CorrectDcl.class) {
                    local = instance;
                    if (local == null) instance = local = new CorrectDcl();
                }
            }
            return local;
        }
    }

    /** V4 - initialization-on-demand HOLDER: classloading does the synchronization for you. */
    // WHY IT IS SAFE, in the vocabulary of Concept #2: the edges are spent on your behalf.
    // Class initialization runs under the JVM's own init lock (JLS 12.4.2), which serialises
    // every concurrent first-touch, and INSTANCE is static final, so the final-field freeze
    // covers its publication exactly as it covers Holder.fin in Concept #2 Demo F. That is
    // the whole trick: V3 buys one edge with `volatile`, V4 inherits two for free and there
    // is no chain left to get backwards.
    static final class HolderSingleton {
        static final AtomicInteger constructions = new AtomicInteger();
        private HolderSingleton() { constructions.incrementAndGet(); }
        private static final class Holder {                  // initialized on first use, under the JVM's class-init lock
            static final HolderSingleton INSTANCE = new HolderSingleton();
        }
        static HolderSingleton getInstance() { return Holder.INSTANCE; }
    }

    /** V5 - ENUM singleton: the JVM guarantees one instance, safely published, once. */
    // NOTE: the construction counter lives OUTSIDE the enum on purpose. Enum constants are
    // initialized before any other static field, so a counter declared inside the enum
    // would still be null when the constructor runs (a classic enum init-order trap).
    private static final AtomicInteger enumConstructions = new AtomicInteger();
    enum EnumSingleton {
        INSTANCE;
        EnumSingleton() { enumConstructions.incrementAndGet(); }
    }

    // Busy-spin release flag: parking threads (CountDownLatch) wakes them staggered enough
    // that the first racer can finish constructing before the others read - hiding V0's bug.
    // A spin flag releases all racers within nanoseconds, so their null-checks truly overlap.
    private static volatile boolean raceGo;

    /** Fire RACERS threads at getInstance() simultaneously; return how many DISTINCT objects came back. */
    private static int distinctInstances(Supplier<Object> getInstance) throws InterruptedException {
        raceGo = false;
        Object[] out = new Object[RACERS];
        Thread[] ts = new Thread[RACERS];
        for (int i = 0; i < RACERS; i++) {
            final int idx = i;
            ts[i] = new Thread(() -> {
                while (!raceGo) Thread.onSpinWait();          // all racers park HERE, spinning
                out[idx] = getInstance.get();
            }, "racer-" + i);
            ts[i].start();
        }
        Thread.sleep(20);                                     // let every racer reach the spin
        raceGo = true;                                        // release them all at once
        for (Thread t : ts) t.join();
        IdentityHashMap<Object, Object> distinct = new IdentityHashMap<>();
        for (Object o : out) distinct.put(o, o);
        return distinct.size();
    }

    private void partA() throws InterruptedException {
        System.out.println("Part A - one instance, or many? (" + RACERS + " racers)");
        row("V0 no-sync      ", distinctInstances(NoSync::getInstance),       NoSync.constructions.get());
        row("V1 synchronized ", distinctInstances(SyncMethod::getInstance),   SyncMethod.constructions.get());
        row("V2 DCL no-vol   ", distinctInstances(BrokenDcl::getInstance),    BrokenDcl.constructions.get());
        row("V3 DCL volatile ", distinctInstances(CorrectDcl::getInstance),   CorrectDcl.constructions.get());
        row("V4 holder idiom ", distinctInstances(HolderSingleton::getInstance), HolderSingleton.constructions.get());
        row("V5 enum         ", distinctInstances(() -> EnumSingleton.INSTANCE), enumConstructions.get());
    }

    private static void row(String label, int distinct, int constructions) {
        String verdict = distinct == 1
                ? (constructions == 1 ? "OK: exactly one instance" : "one instance (constructed " + constructions + "x)")
                : "BUG: " + distinct + " distinct instances built";
        System.out.printf("  [%s] distinct=%d constructions=%d  -> %s%n", label, distinct, constructions, verdict);
    }

    // ---------------------------------------------------------------------------------
    // Part B - the real publication race: can a fast-path reader see a half-built object?
    // ---------------------------------------------------------------------------------

    static final class Payload {
        int a, b, c;                                        // PLAIN (non-final): partial construction is permitted
        Payload() { a = 1; b = 2; c = 3; }
    }

    /** A fresh broken-DCL box each round, so we can rerun the publication race millions of times. */
    static final class LazyBox {
        private Payload instance;                           // NOT volatile - the defect under test
        Payload get() {
            if (instance == null) {                         // unsynchronized read: may see a half-built Payload
                synchronized (this) {
                    if (instance == null) instance = new Payload();
                }
            }
            return instance;
        }
    }

    private volatile long gate;                             // round release for the two racers
    private volatile long doneA, doneB;
    private LazyBox currentBox;                             // delivered fresh each round via the gate
    private final AtomicLong partialObservations = new AtomicLong();

    private Runnable racer(boolean isA) {
        return () -> {
            long round = 0;
            while (round < PUBLICATION_ROUNDS) {
                long next = round + 1;
                while (gate < next) Thread.onSpinWait();     // wait for main to open the round
                Payload p = currentBox.get();                // race with the other thread's get()
                if (p.a != 1 || p.b != 2 || p.c != 3) {      // saw the reference but not the fields => half-built
                    partialObservations.incrementAndGet();
                }
                if (isA) doneA = next; else doneB = next;
                round = next;
            }
        };
    }

    private void partB() throws InterruptedException {
        Thread a = new Thread(racer(true), "pub-A");
        Thread b = new Thread(racer(false), "pub-B");
        a.start();
        b.start();
        for (long round = 1; round <= PUBLICATION_ROUNDS; round++) {
            currentBox = new LazyBox();                      // fresh, unset box (published to racers by gate below)
            gate = round;
            while (doneA < round || doneB < round) Thread.onSpinWait();
        }
        a.join();
        b.join();

        long partial = partialObservations.get();
        System.out.println();
        System.out.printf("Part B - broken-DCL publication race: %,d rounds, %,d half-built observations%n",
                PUBLICATION_ROUNDS, partial);
        if (partial == 0) {
            System.out.println("  0 on this x86 box (strong store ordering hides it) - but the code is still");
            System.out.println("  broken: on ARM/Power a fast-path reader CAN see instance!=null with fields=0.");
        } else {
            System.out.println("  Caught the half-built object " + partial + " time(s): DCL without volatile is broken.");
        }
    }

    private void run() throws InterruptedException {
        System.out.println("Concept #4 - double-checked locking, correctly");
        System.out.println("==============================================");
        partA();
        partB();
        System.out.println();
        System.out.println("Takeaway: naive lazy init races into N instances; DCL needs the field VOLATILE");
        System.out.println("(synchronized on the slow path can't order the unsynchronized fast-path read).");
        System.out.println("Prefer the holder idiom or an enum - the JVM's class-init lock does it for you.");
    }

    public static void main(String[] args) throws InterruptedException {
        new Concept04DoubleCheckedLocking().run();
    }
}
