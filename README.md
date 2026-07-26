# java-exercises-to-keep-it
These are Java exercises to keep the concepts also when the AIs are great is better to hold the knowledge of what is important in Java, we will go in a series of lessons to remain trained and fresh

Each track is a set of small, self-checking programs. They are not tests and they are not
snippets to read: every one of them runs, prints what it observed, and says whether that
outcome was guaranteed by the specification or merely what this machine happened to do. The
distinction between those two is the point of the whole repository.

## Tracks

| # | Track | Status |
|---|-------|--------|
| 1 | [JVM Memory Model & Concurrency](src/main/java/org/example/jmm/README.md) | 7 concepts, complete |
| 2 | [Generics Variance (PECS)](src/main/java/org/example/generics/README.md) | 5 concepts, complete |

## Track 1: JVM Memory Model & Concurrency

Seven runnable demos in [`org.example.jmm`](src/main/java/org/example/jmm), building from "why
reordering is legal at all" to a producer/consumer handoff traced edge by edge.

| # | Concept | Shows |
|---|---------|-------|
| 1 | Reordering | `(0,0)` in the Dekker shape: an outcome no sequentially-consistent interleaving can produce, observed 1.2M times in 10M rounds |
| 2 | Happens-before | the five edges plus transitivity, and one demo with no edge at all, which hangs |
| 3 | Volatile vs atomic | `volatile` buys visibility and ordering, not atomicity: `counter++` still loses 50-63% of increments |
| 4 | Double-checked locking | naive lazy init builds N instances; DCL needs `volatile`; the holder and enum idioms sidestep it |
| 5 | Concurrent primitives | why `java.util.concurrent` exists: compound operations need CAS or `ConcurrentHashMap`, and one big lock does not scale |
| 6 | Parallel streams | `parallel()` shares the common pool; shared mutable state races; four fixes ranked by whether they are worth writing |
| 7 | Producer/consumer | one handoff three ways (plain field, volatile flag, `BlockingQueue`) with each happens-before chain written out |

**Start with [the Track 1 study guide](src/main/java/org/example/jmm/README.md).** It has the
five-edge reference table, the three distinctions people reliably get wrong, a map of which
failures are visible on x86 and which are not, and a self-test.

## Track 2: Generics Variance (PECS)

Five runnable demos in [`org.example.generics`](src/main/java/org/example/generics), built so
that PECS can be *rebuilt* from erasure rather than recalled.

| # | Concept | Shows |
|---|---------|-------|
| 1 | Erasure | `List<String>` and `List<Integer>` are one class at runtime; a raw type smuggles an `Integer` into a `List<String>` and the `ClassCastException` lands at the read |
| 2 | Variance | arrays are covariant and pay with a runtime check on every store; generics were erased, so invariance is the only sound option |
| 3 | PECS | derived from the compiler's capture variable, not memorised: exactly one operation is provably sound in each direction |
| 4 | JDK signatures | `Collections.copy`, `List.sort`, `Stream.map` and friends, printed from the class files by reflection so they cannot drift |
| 5 | Bounded vs wildcard | when `<T extends ...>` is required, and why `Comparable<? super T>` is not decoration |

Plus [`wrong-turns/`](wrong-turns): six files that deliberately do **not** compile, kept for
their error messages. javac names the captured type variable and explains the rule better
than any mnemonic.

**Start with [the Track 2 study guide](src/main/java/org/example/generics/README.md).**

## Running

```bash
mvn compile exec:java                            # index of both tracks
mvn compile exec:java -Dexec.arguments=1.2       # one concept (track.concept)
mvn compile exec:java -Dexec.arguments=2         # a whole track
mvn compile exec:java -Dexec.arguments=all       # everything
```

Requires **JDK 21** and Maven. `.mvn/maven.config` pins resolution to Maven Central so a clone
builds the same way regardless of your global Maven configuration.

## A note on the output

Some demos are *supposed* to fail, and say so. Concept 2 Demo A deliberately hangs a reader
thread and reports `[EXPECTED]` when it does, because a plain field carries no happens-before
edge and the JIT is entitled to hoist the read forever. Others pass on x86 while remaining
incorrect: Concept 4 runs a real broken double-checked-locking race five million times, observes
zero failures, and reports that honestly as *zero is not the same as correct*.

Correctness under the Java Memory Model is a property of the program, established by finding the
chain of edges. It is never established by an observed run, however green.
