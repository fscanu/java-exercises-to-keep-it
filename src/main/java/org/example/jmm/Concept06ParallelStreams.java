// ABOUTME: Demonstrates that parallel streams run on the shared commonPool and that any shared
// ABOUTME: mutable state in the lambda is a data race; shows the correct reduce/collect patterns.
package org.example.jmm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/*
 * =====================================================================================
 * Concept #6 - "Parallel streams, specifically"
 * =====================================================================================
 *
 * `stream.parallel()` does not spin up threads for you in isolation. It splits the work
 * across ForkJoinPool.commonPool() - ONE pool shared by every parallel stream (and every
 * CompletableFuture default task) in the whole JVM. Its parallelism defaults to
 * availableProcessors() - 1, and - importantly - the SUBMITTING thread also runs tasks,
 * so a slow parallel stream blocks its caller too, and a blocking task in one parallel
 * stream can starve every other. (Demo C shows the pool.)
 *
 * The trap: a parallel stream just runs your lambda on many threads at once. If that
 * lambda touches SHARED MUTABLE STATE - a field, a plain collection, an accumulator array
 * - you have a data race. This is true REGARDLESS of how unlikely contention feels: the
 * JMM gives you nothing without a happens-before edge (Concept #2), so "it printed the
 * right number on my laptop" proves nothing. Demos A and B corrupt shared state reliably.
 *
 * The correct patterns - none of which share mutable state across threads:
 *   - REDUCE / built-in aggregates: reduce(identity, op) and sum()/count() accumulate
 *     per-thread and combine associatively. The framework owns the merging. (Demo D)
 *   - COLLECT with a combiner: collect(supplier, accumulator, combiner) gives each worker
 *     its OWN container (thread-confined), then merges them. Same ArrayList that raced in
 *     Demo A is correct here because it is never shared. (Demo E)
 *   - Or DON'T parallelize: for small N, or IO-bound / non-associative / not-cleanly-
 *     splittable work, the split+merge+commonPool overhead loses to a plain sequential
 *     loop. Parallelism is for large, CPU-bound, associatively-decomposable workloads.
 *
 * THREAD-SAFE IS NOT THE SAME AS CORRECT-TO-WRITE. Demos F and G fix Demo A's race in ways
 * that pass every test and that you should still reject in review. F wraps the list in
 * Collections.synchronizedList: the adds are now ordered by one monitor, so the workers
 * take turns and the parallelism buys nothing but contention. G honours reduce()'s actual
 * contract - the accumulator may NOT mutate its input, since the framework can call it from
 * several threads in any grouping - which forces a fresh copy per element and turns an O(n)
 * job into O(n^2). Both produce the right answer; neither is the right code. That gap is
 * why collect() exists: it hands each worker its own container and merges at the end.
 *
 * These races are NOT x86-specific - a lost update or a corrupted ArrayList races on
 * every architecture.
 * =====================================================================================
 */
public final class Concept06ParallelStreams {

    private static final int N = 1_000_000;
    private static final long EXPECTED_SUM = (long) N * (N - 1) / 2; // sum of 0..N-1

    // =================================================================================
    // Demo A - shared mutable collection in a parallel lambda: corrupts (size/null/throw)
    // =================================================================================
    private String demoSharedList() {
        List<Integer> shared = new ArrayList<>();               // NOT thread-safe
        try {
            IntStream.range(0, N).parallel().forEach(i -> shared.add(i)); // concurrent add == race
        } catch (RuntimeException e) {
            return "threw " + e.getClass().getSimpleName() + " -> shared ArrayList.add() is a data race [BUG]";
        }
        long nulls = countNullsSafely(shared);
        boolean broken = shared.size() != N || nulls > 0;
        return String.format("size %,d/%,d, %,d nulls -> %s", shared.size(), N, nulls,
                broken ? "corrupted: shared ArrayList.add() is a data race [BUG]"
                       : "intact THIS run (rare; still a race)");
    }

    private static long countNullsSafely(List<Integer> list) {
        try {
            long nulls = 0;
            for (Integer v : list) if (v == null) nulls++;      // a corrupt list can even throw here
            return nulls;
        } catch (RuntimeException e) {
            return -1;                                          // "couldn't even iterate it"
        }
    }

    // =================================================================================
    // Demo B - shared primitive accumulator in a parallel lambda: lost updates
    // =================================================================================
    private String demoSharedAccumulator() {
        long[] acc = {0};                                       // shared mutable cell
        IntStream.range(0, N).parallel().forEach(i -> acc[0] += i); // read-modify-write race
        long lost = EXPECTED_SUM - acc[0];
        return String.format("expected %,d, got %,d (lost %,d) -> %s",
                EXPECTED_SUM, acc[0], lost,
                acc[0] == EXPECTED_SUM ? "no loss THIS run (rare; still a race)"
                                       : "lost updates: acc[0]+=i is not atomic [BUG]");
    }

    // =================================================================================
    // Demo C - where does it run? ForkJoinPool.commonPool() (+ the calling thread)
    // =================================================================================
    private String demoCommonPool() {
        Set<String> threads = ConcurrentHashMap.newKeySet();
        IntStream.range(0, N).parallel().forEach(i -> threads.add(Thread.currentThread().getName()));
        long poolWorkers = threads.stream().filter(n -> n.startsWith("ForkJoinPool.commonPool")).count();
        boolean callerHelped = threads.contains(Thread.currentThread().getName()); // "main" does work too
        return String.format("ran on %d threads (%d commonPool workers, caller helped=%b); pool parallelism=%d, cpus=%d",
                threads.size(), poolWorkers, callerHelped,
                ForkJoinPool.commonPool().getParallelism(), Runtime.getRuntime().availableProcessors());
    }

    // =================================================================================
    // Demo D - CORRECT: built-in reduction owns the per-thread accumulate + combine
    // =================================================================================
    private String demoReduce() {
        long sum = IntStream.range(0, N).parallel().asLongStream().sum();           // associative reduce
        List<Integer> list = IntStream.range(0, N).parallel().boxed()
                .collect(Collectors.toList());                                       // safe parallel collect
        boolean ok = sum == EXPECTED_SUM && list.size() == N;
        return String.format("parallel sum=%,d (expected %,d), toList size=%,d -> %s",
                sum, EXPECTED_SUM, list.size(),
                ok ? "OK: no shared state, framework merges" : "UNEXPECTED");
    }

    // =================================================================================
    // Demo E - CORRECT: collect(supplier, accumulator, combiner) - per-thread ArrayList
    // =================================================================================
    private String demoThreadConfinedCollect() {
        // Same ArrayList that raced in Demo A, but each worker gets its OWN via the supplier
        // (thread-confined), accumulates locally, and the combiner (addAll) merges them.
        List<Integer> result = IntStream.range(0, N).parallel().boxed()
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        long nulls = countNullsSafely(result);
        boolean ok = result.size() == N && nulls == 0;
        return String.format("size %,d/%,d, %,d nulls -> %s", result.size(), N, nulls,
                ok ? "OK: thread-confined accumulators + addAll combiner (no sharing)" : "UNEXPECTED");
    }

    // =================================================================================
    // Demo F - CORRECT BUT POINTLESS: Collections.synchronizedList + parallel forEach
    // =================================================================================
    private String demoSynchronizedList() {
        // Fixes Demo A's race the blunt way: every add() takes the SAME monitor, so the adds
        // are ordered and no update is lost. The list is genuinely thread-safe now. What it is
        // not is parallel: the workers spend their time queueing on one lock, and the split +
        // merge + contention overhead is pure loss over just doing it on one thread.
        List<Integer> shared = Collections.synchronizedList(new ArrayList<>());
        long locked = timeMs(() -> IntStream.range(0, N).parallel().forEach(i -> shared.add(i)));
        long collect = timeMs(() -> IntStream.range(0, N).parallel().boxed().collect(Collectors.toList()));
        long sequential = timeMs(() -> IntStream.range(0, N).boxed().collect(Collectors.toList()));

        boolean ok = shared.size() == N && countNullsSafely(shared) == 0;
        return String.format("size %,d/%,d %s | synchronizedList %,d ms, parallel collect %,d ms, sequential %,d ms",
                shared.size(), N, ok ? "(correct)" : "[UNEXPECTED: not thread-safe?]",
                locked, collect, sequential);
    }

    // =================================================================================
    // Demo G - CORRECT ONLY IF QUADRATIC: reduce() with an immutable accumulator
    // =================================================================================
    private String demoReduceIntoList() {
        // reduce()'s contract requires the accumulator to be ASSOCIATIVE and NON-INTERFERING:
        // it must not mutate its input, because the framework may call it on the same value
        // from several threads and in any grouping. Honouring that for a List means copying
        // the accumulator on every element, which is O(n^2). It is correct, and it is the
        // wrong tool - collect() exists precisely because reduce() cannot mutate containers.
        // Timed at N/5 and at N. If the cost were linear, 5x the input would cost 5x the time;
        // quadratic predicts 25x. The measured ratio is the whole argument, so we warm the
        // lambdas first - otherwise the small run pays for JIT compilation and flatters the curve.
        final int small = N / 5;
        reduceIntoList(small);                                  // warm-up, untimed
        long smallMs = timeMs(() -> reduceIntoList(small));
        long t0 = System.nanoTime();
        List<Integer> result = reduceIntoList(N);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        double growth = smallMs == 0 ? Double.NaN : (double) ms / smallMs;

        // The tempting "optimisation" is to mutate the accumulator instead of copying it:
        //     .reduce(new ArrayList<>(), (acc, i) -> { acc.add(i); return acc; },
        //                                (a, b)   -> { a.addAll(b); return a; })
        // That is Demo A again with extra steps: the single identity instance is shared by
        // every worker, so the adds race. It also breaks the identity contract, since the
        // identity is supposed to be reusable and this one accumulates state.
        boolean ok = result.size() == N && countNullsSafely(result) == 0;
        return String.format("size %,d/%,d %s | %,d ms at N vs %,d ms at N/5: 5x the input cost %.0fx the time"
                        + " (linear predicts 5x, quadratic 25x)",
                result.size(), N, ok ? "(correct)" : "[UNEXPECTED]", ms, smallMs, growth);
    }

    private static List<Integer> reduceIntoList(int n) {
        return IntStream.range(0, n).parallel().boxed()
                .reduce(new ArrayList<Integer>(),
                        (acc, i) -> { ArrayList<Integer> next = new ArrayList<>(acc); next.add(i); return next; },
                        (a, b) -> { ArrayList<Integer> merged = new ArrayList<>(a); merged.addAll(b); return merged; });
    }

    private static long timeMs(Runnable body) {
        long t0 = System.nanoTime();
        body.run();
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private void run() {
        System.out.println("Concept #6 - parallel streams: shared state races, and the right patterns");
        System.out.println("=======================================================================");
        System.out.println("N = " + String.format("%,d", N));
        System.out.println();
        System.out.println("  [A shared ArrayList ] " + demoSharedList());
        System.out.println("  [B shared accum +=  ] " + demoSharedAccumulator());
        System.out.println("  [C where it runs    ] " + demoCommonPool());
        System.out.println("  [D reduce / collect ] " + demoReduce());
        System.out.println("  [E confined collect ] " + demoThreadConfinedCollect());
        System.out.println("  [F synchronizedList ] " + demoSynchronizedList());
        System.out.println("  [G reduce into List ] " + demoReduceIntoList());
        System.out.println();
        System.out.println("Takeaway: parallel() runs your lambda on the shared commonPool. Touching shared");
        System.out.println("mutable state from it is a race, however unlikely it looks. Four fixes for Demo A,");
        System.out.println("all correct, only two worth writing:");
        System.out.println("  1. collect(Collectors.toList())  - idiomatic. Say what you want, not how.");
        System.out.println("  2. collect(new, add, addAll)     - idiomatic when you need a specific container.");
        System.out.println("  3. synchronizedList + forEach    - correct, but serialises on one lock (Demo F).");
        System.out.println("  4. reduce() copying accumulators - correct, but O(n^2) (Demo G). Mutate it to");
        System.out.println("     make it fast and you are back to Demo A's race.");
        System.out.println("3 and 4 are the tell: 'no exception, right answer' is not the bar. Ask what the");
        System.out.println("threads are DOING - queueing on a monitor, or copying the accumulator n times.");
    }

    public static void main(String[] args) {
        new Concept06ParallelStreams().run();
    }
}
