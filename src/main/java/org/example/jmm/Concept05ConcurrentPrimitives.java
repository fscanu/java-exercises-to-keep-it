// ABOUTME: Demonstrates why java.util.concurrent exists instead of "volatile everywhere": atomic
// ABOUTME: compound ops (CAS, ConcurrentHashMap) that plain maps, synchronizedMap, and volatile can't give.
package org.example.jmm;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

/*
 * =====================================================================================
 * Concept #5 - "java.util.concurrent primitives, and why they exist"
 * =====================================================================================
 *
 * Concept #3 showed volatile buys VISIBILITY + ORDERING but not ATOMICITY of a
 * read-modify-write. "Just make everything volatile" therefore cannot make counter++,
 * or get-then-put on a map, atomic. And "just wrap one big lock around everything" is
 * correct but serialises every thread onto a single monitor - it does not scale. j.u.c
 * exists to fill exactly that gap: atomic compound operations that are also concurrent.
 *
 *   - AtomicInteger & friends: a hardware COMPARE-AND-SWAP (CAS) makes read-modify-write
 *     a single atomic, LOCK-FREE step. compareAndSet(expected, new) succeeds only if the
 *     value is still `expected`; if another thread moved it, you retry. Optimistic, no
 *     blocking, no lost updates. (Demo D builds increment from raw CAS and counts retries.)
 *
 *   - ConcurrentHashMap: thread-safe AND scalable (the table is split so writers to
 *     different bins don't contend), PLUS atomic compound methods - merge/compute/
 *     computeIfAbsent/putIfAbsent - that do the get-and-update as ONE atomic step.
 *
 * The four stages below walk the motivation:
 *   A  plain HashMap under concurrent puts       -> corrupts / loses entries (not safe at all)
 *   B  Collections.synchronizedMap, get-then-put -> each CALL is atomic, the PAIR is not
 *   C  ConcurrentHashMap.merge                    -> the compound op IS atomic: exact counts
 *   D  AtomicInteger via a raw CAS retry loop     -> exact counts, lock-free, retries counted
 *   Capstone: one global lock vs ConcurrentHashMap on the same workload -> why not "one big lock".
 *
 * These failures are NOT x86-specific: lost updates and structural corruption of a
 * non-thread-safe container race on every architecture.
 * =====================================================================================
 */
public final class Concept05ConcurrentPrimitives {

    private static final int MAP_THREADS = 8;
    private static final int KEYS_PER_THREAD = 10_000;        // distinct keys per thread (Demo A)

    private static final int CNT_THREADS = 8;
    private static final int CNT_ITERS = 100_000;             // increments per thread (Demo B/C, capstone)
    private static final int CNT_KEYS = 16;                   // shared key space they fight over
    private static final long TOTAL_INCREMENTS = (long) CNT_THREADS * CNT_ITERS;

    private static final int CAS_THREADS = 8;
    private static final int CAS_ITERS = 100_000;
    private static final long CAS_TOTAL = (long) CAS_THREADS * CAS_ITERS;

    private static final long WORKER_TIMEOUT_MS = 5_000;      // safety net; a corrupt worker shouldn't wedge the demo

    // Busy-spin release (same reliable start gate as Concept #4): all workers begin together.
    private static volatile boolean startGate;

    /** Run `threads` daemon workers, each running body.accept(threadIndex) once, released together.
     *  Returns wall-clock nanos from release to all-joined (for the capstone timing). */
    private static long runWorkers(int threads, IntConsumer body) throws InterruptedException {
        startGate = false;
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            ts[i] = new Thread(() -> {
                while (!startGate) Thread.onSpinWait();
                body.accept(idx);
            }, "worker-" + i);
            ts[i].setDaemon(true);                            // a corrupt run can't wedge JVM shutdown
            ts[i].start();
        }
        Thread.sleep(10);                                     // let every worker reach the spin
        long start = System.nanoTime();
        startGate = true;
        long deadline = System.nanoTime() + WORKER_TIMEOUT_MS * 1_000_000L;
        for (Thread t : ts) {
            long remainingMs = Math.max(1, (deadline - System.nanoTime()) / 1_000_000L);
            t.join(remainingMs);
        }
        return System.nanoTime() - start;
    }

    private static long sumValues(Map<Integer, Integer> map) {
        long sum = 0;
        for (int v : map.values()) sum += v;
        return sum;
    }

    // =================================================================================
    // Demo A - plain HashMap is not thread-safe: concurrent puts lose entries / corrupt
    // =================================================================================
    private void demoPlainHashMap() throws InterruptedException {
        // Pre-size beyond the entry count so the table never RESIZES: a concurrent resize can
        // splice a bucket's linked list into a cycle and send a thread into an infinite loop,
        // which would just hang the demo. Even with no resize, racing puts to the same bucket
        // drop entries - that alone proves HashMap is not thread-safe.
        Map<Integer, Integer> map = new HashMap<>(1 << 17);   // threshold ~98k > 80k inserts => no resize
        AtomicInteger errors = new AtomicInteger();
        int expected = MAP_THREADS * KEYS_PER_THREAD;
        runWorkers(MAP_THREADS, idx -> {
            int base = idx * KEYS_PER_THREAD;                 // disjoint key ranges: every put is a NEW key
            for (int k = 0; k < KEYS_PER_THREAD; k++) {
                try {
                    map.put(base + k, base + k);
                } catch (RuntimeException e) {
                    errors.incrementAndGet();                 // resize races can even throw
                }
            }
        });
        int size = map.size();
        boolean broken = size != expected || errors.get() > 0;
        System.out.printf("  [A plain HashMap  ] expected %,d entries, got %,d (lost %,d), %d exceptions -> %s%n",
                expected, size, (long) expected - size, errors.get(),
                broken ? "BUG: HashMap is not thread-safe" : "no corruption THIS run (rare; still unsafe)");
    }

    // =================================================================================
    // Demo B - synchronizedMap: each CALL is atomic, but get-then-put (a PAIR) is not
    // =================================================================================
    private void demoSynchronizedMapRace() throws InterruptedException {
        Map<Integer, Integer> map = Collections.synchronizedMap(new HashMap<>());
        for (int k = 0; k < CNT_KEYS; k++) map.put(k, 0);
        runWorkers(CNT_THREADS, idx -> {
            for (int i = 0; i < CNT_ITERS; i++) {
                int k = i % CNT_KEYS;
                int cur = map.get(k);                         // atomic call #1
                map.put(k, cur + 1);                          // atomic call #2 - another thread slips between
            }
        });
        long sum = sumValues(map);
        System.out.printf("  [B synchronizedMap] expected %,d, counted %,d (lost %,d) -> %s%n",
                TOTAL_INCREMENTS, sum, TOTAL_INCREMENTS - sum,
                "BUG: per-call safety != atomic get-then-put (merge() would be atomic; the hand-rolled pair isn't)");
    }

    // =================================================================================
    // Demo C - ConcurrentHashMap.merge: the compound get-and-update is ONE atomic step
    // =================================================================================
    private void demoConcurrentHashMap() throws InterruptedException {
        ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>();
        runWorkers(CNT_THREADS, idx -> {
            for (int i = 0; i < CNT_ITERS; i++) {
                map.merge(i % CNT_KEYS, 1, Integer::sum);     // atomic compound op, no external lock
            }
        });
        long sum = sumValues(map);
        System.out.printf("  [C ConcurrentHashM] expected %,d, counted %,d (lost %,d) -> %s%n",
                TOTAL_INCREMENTS, sum, TOTAL_INCREMENTS - sum,
                sum == TOTAL_INCREMENTS ? "OK: atomic merge(), exact counts" : "UNEXPECTED loss");
    }

    // =================================================================================
    // Demo D - AtomicInteger from raw CAS: lock-free read-modify-write, retries counted
    // =================================================================================
    private void demoCas() throws InterruptedException {
        AtomicInteger value = new AtomicInteger(0);
        AtomicLong retries = new AtomicLong(0);
        runWorkers(CAS_THREADS, idx -> {
            for (int i = 0; i < CAS_ITERS; i++) {
                for (;;) {                                    // this loop IS what incrementAndGet() does internally
                    int cur = value.get();
                    if (value.compareAndSet(cur, cur + 1)) break; // succeeds only if nobody moved it
                    retries.incrementAndGet();                // a conflicting thread won the race; try again
                }
            }
        });
        System.out.printf("  [D CAS increment  ] expected %,d, got %,d, %,d CAS retries -> %s%n",
                CAS_TOTAL, value.get(), retries.get(),
                value.get() == CAS_TOTAL ? "OK: lock-free, no lost updates (retries prove real contention)" : "UNEXPECTED loss");
    }

    // =================================================================================
    // Capstone - correctness is not enough: one global lock vs ConcurrentHashMap throughput
    // =================================================================================
    private void capstone() throws InterruptedException {
        // Correct global-lock counter: the compound op is made atomic by an EXTERNAL lock,
        // which serialises ALL threads onto one monitor.
        Map<Integer, Integer> locked = Collections.synchronizedMap(new HashMap<>());
        for (int k = 0; k < CNT_KEYS; k++) locked.put(k, 0);
        final Object lock = new Object();
        long lockedNs = runWorkers(CNT_THREADS, idx -> {
            for (int i = 0; i < CNT_ITERS; i++) {
                int k = i % CNT_KEYS;
                synchronized (lock) { locked.put(k, locked.get(k) + 1); }
            }
        });

        // Correct ConcurrentHashMap counter: atomic merge, no global lock.
        ConcurrentHashMap<Integer, Integer> chm = new ConcurrentHashMap<>();
        long chmNs = runWorkers(CNT_THREADS, idx -> {
            for (int i = 0; i < CNT_ITERS; i++) chm.merge(i % CNT_KEYS, 1, Integer::sum);
        });

        boolean bothCorrect = sumValues(locked) == TOTAL_INCREMENTS && sumValues(chm) == TOTAL_INCREMENTS;
        System.out.printf("  global lock: %d ms   ConcurrentHashMap: %d ms   (%.1fx)   both correct=%b%n",
                lockedNs / 1_000_000, chmNs / 1_000_000,
                (double) lockedNs / Math.max(1, chmNs), bothCorrect);
    }

    private void run() throws InterruptedException {
        System.out.println("Concept #5 - j.u.c primitives: why not just volatile / one big lock?");
        System.out.println("===================================================================");
        System.out.println(CNT_THREADS + " threads x " + String.format("%,d", CNT_ITERS)
                + " increments over " + CNT_KEYS + " keys = " + String.format("%,d", TOTAL_INCREMENTS) + " expected");
        System.out.println();
        demoPlainHashMap();
        demoSynchronizedMapRace();
        demoConcurrentHashMap();
        demoCas();
        System.out.println();
        System.out.println("Capstone - same correct result, very different scalability:");
        capstone();
        System.out.println();
        System.out.println("Takeaway: volatile can't make a compound op atomic; one global lock can, but");
        System.out.println("serialises everyone. j.u.c gives atomic-AND-concurrent primitives - CAS for");
        System.out.println("read-modify-write, ConcurrentHashMap.merge/compute for maps. That's why they exist.");
    }

    public static void main(String[] args) throws InterruptedException {
        new Concept05ConcurrentPrimitives().run();
    }
}
