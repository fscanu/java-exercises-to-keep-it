<!-- ABOUTME: Study guide for the org.example.jmm concept demos: the five happens-before edges,
     ABOUTME: the distinctions people get wrong, a map of what each demo proves, and a self-test. -->

# Java Memory Model, by demonstration

Seven runnable demos, each self-checking. This file is the map: what the rule is, which
distinctions actually break people, which demo proves what, and a self-test to find out
whether you can reason to an answer instead of recalling one.

```bash
mvn compile exec:java                            # index
mvn compile exec:java -Dexec.arguments=1.2       # one concept (track.concept)
mvn compile exec:java -Dexec.arguments=1         # all seven
```

The JDK on `PATH` is 8; the project needs 21 at `/usr/lib/jvm/java-21-openjdk-amd64`. Maven
already targets it, so prefer the Maven commands over a hand-rolled `java`.

---

## The one rule

Two actions on **different threads** are ordered only when a **chain of happens-before edges**
connects them. No chain, no order. Not "usually works", not "unlikely to matter": no order.

Happens-before is a **partial** order (most pairs of actions are simply unordered) and it is
**transitive** (A→B and B→C gives A→C, with no direct edge between A and C).

Inside a single thread you always see your own actions in program order. That is *as-if-serial*,
and it is a promise made to **that thread only**. It constrains nothing another thread observes.

---

## The five edges

These are the whole supply. Everything else, including all of `java.util.concurrent`, is built
from them.

| # | Edge | You write | Demo |
|---|------|-----------|------|
| 1 | monitor unlock → a later lock of the **same** monitor | `synchronized` | `02-C` |
| 2 | volatile write → a later read of the **same** field | `volatile` | `02-B`, `07-B` |
| 3 | `Thread.start()` → every action in the started thread | `t.start()` | `02-D` |
| 4 | every action in a thread → another thread's `join()` on it | `t.join()` | `02-E` |
| 5 | final-field freeze (end of constructor) → other threads' reads of the reference | `final` | `02-F` |

Plus transitivity, which is what makes chains work (`02-G`), and the `java.util.concurrent`
package guarantee: actions before putting an element into a concurrent collection happen-before
actions after another thread removes it (`07-C`). That one is edge #1 underneath, but you are
entitled to it without knowing that.

---

## Three distinctions that break people

Get these three wrong and everything else stays foggy. Get them right and the rest is bookkeeping.

### 1. Liveness is not safety

Two separate questions with two separate answers:

- **"Does the signal ever arrive?"** Liveness. For a plain field: **no guarantee, ever.** The JIT
  may hoist the read into a register and spin on a stale copy forever.
- **"Once it arrives, what may I trust?"** Safety. This is what `volatile` and `final` answer.

`final` contributes **nothing** to the first question. It is a property of how an object was
constructed, and you cannot benefit from it until you already hold the reference. That is why
`Concept02:211` and `Concept07` Demo A both have an explicit "it never showed up" branch: those
branches are reachable, and on the wrong day they are what you get.

### 2. An edge has a direction and two named endpoints

`join()` gives an edge to the thread that **calls** it.

```java
writer.start();
reader.start();
writer.join();   // edge: writer -> main.  NOT writer -> reader.
```

The reader in that snippet shares no edge with the writer and never did. The check that never
fails you: **say both endpoints out loud.** If the two threads you care about are not the two
endpoints, this is not the edge you need.

### 3. The freeze covers fields, not objects

There is no such thing as "a final object".

```java
private static final class Holder {   // <- subclassing modifier. Zero JMM meaning.
    final int fin;                    // <- THIS gets the freeze
    int plain;                        // <- racy, guaranteed nothing
}
```

Edge #5 covers the **final fields**, plus everything reachable through them as of the end of the
constructor. A `final int[]` field means the array *contents* are guaranteed too, not merely a
non-null reference. Every non-final field stays racy.

One precondition voids all of it: **`this` must not escape the constructor.** Register a listener,
call an overridable method, assign `this` to a static, and another thread can capture the
reference before the freeze. Then the final fields are racy too.

---

## What each demo proves

| # | File | Proves | Visible on x86? |
|---|------|--------|-----------------|
| 1 | `Concept01Reordering` | `(0,0)` in the Dekker shape: an outcome **no** sequentially-consistent interleaving can produce | yes, 1.2M of 10M rounds (12%) |
| 2 | `Concept02HappensBefore` | the five edges + transitivity, and one no-edge negative | positives always; A hangs (visibility) |
| 3 | `Concept03VolatileVsAtomic` | `volatile` = visibility + ordering, **not** atomicity | yes, loses 50–63% of increments |
| 4 | `Concept04DoubleCheckedLocking` | naive lazy init builds N instances; DCL needs `volatile`; holder + enum sidestep it | Part A yes (13 instances); Part B's half-built object no (0 of 5M) |
| 5 | `Concept05ConcurrentPrimitives` | compound ops need CAS or `ConcurrentHashMap`; one big lock is correct but does not scale | yes, every failure |
| 6 | `Concept06ParallelStreams` | `parallel()` shares the commonPool; shared mutable state races; four fixes ranked | yes, every failure |
| 7 | `Concept07ProducerConsumer` | one handoff three ways, with each chain traced edge by edge | A hangs; B and C always pass |

The pattern worth noticing: **concepts 3, 5 and 6 fail on every architecture** (a lost update or a
corrupted container is not a memory-ordering bug). Concepts 1, 2 and 4 are where x86 either shows
you the bug or politely hides it.

---

## Wrong mental models

| The belief | Why it is wrong | Where |
|---|---|---|
| "`volatile` makes it atomic" | `counter++` is read, add, write. Each is atomic and visible; the trio is not. Another thread interleaves between your read and your write | `03` |
| "`final` makes the object visible to other threads" | It makes the *contents* trustworthy once you hold the reference. Whether the reference arrives is a separate, unguaranteed question | `02-F` |
| "Once I have the reference, the object is fully built" | Only its final fields are, plus what is reachable through them. Everything else is racy | `02-F`, `04` |
| "`synchronized` on the writer is enough" | The unlock→lock edge exists only between threads that **both** enter the block. A fast-path reader that skips the lock shares no edge | `04` |
| "`join()` published the data" | `join()` gives the edge to its **caller**. Joining from `main` does nothing for a third thread | `02-F`, `07` |
| "It printed the right answer, so it is correct" | x86 hides most ordering bugs. `02-F` prints the same number for a guaranteed and an unguaranteed field | `01`, `02-F`, `04-B` |
| "Thread-safe means correct" | `synchronizedList` + `parallel().forEach` is thread-safe and **30× slower than not parallelizing at all**: every add queues on one monitor | `06-F` |
| "`reduce()` is the functional, therefore right, way to build a list" | Honouring its contract forces a copy per element, O(n²). Mutating the accumulator instead is a race | `06-G` |
| "Just put one big lock around everything" | Correct, and it serialises every thread onto one monitor | `05` capstone |
| "`parallel()` gets its own threads" | It shares `ForkJoinPool.commonPool()` with every parallel stream in the JVM, and the **calling thread** runs tasks too | `06-C` |

---

## Self-test

Reason your way to each answer before opening it. If you can only recall the result, you have not
got it yet.

<details>
<summary><b>1.</b> Two plain ints, <code>x = y = 0</code>. Thread A: <code>x = 1; r1 = y;</code> Thread B: <code>y = 1; r2 = x;</code> Can both reads return 0?</summary>

Yes. Enumerate every sequentially-consistent interleaving and at least one read always follows
the other thread's write, so `(0,0)` is impossible under SC. It is legal under the JMM anyway:
nothing links A's actions to B's, so each store may become visible after that thread's own load
(x86 store buffer), or the JIT may hoist the load above the store. `Concept01` observes it
1,215,072 times in 10,000,000 rounds on this box: not a curiosity, 12% of runs.
</details>

<details>
<summary><b>2.</b> Producer: <code>payload = 42; ready = true;</code> Consumer: <code>while (!ready) {} read payload;</code> All plain. Name the <b>two distinct</b> ways this fails.</summary>

- **Liveness:** the consumer may never observe `ready` at all. C2 proves the plain read
  loop-invariant, hoists it into a register, and the loop spins on a stale copy forever. This is
  what x86 actually shows you, and what `Concept07` Demo A reproduces.
- **Ordering:** even if it *does* observe `ready == true`, nothing orders the payload write before
  the payload read. It may legally read 0. Needs weak hardware (ARM, Power) to observe.

Two failures, two different axes. Most people name only the second.
</details>

<details>
<summary><b>3.</b> Same pair, but <code>ready</code> is now <code>volatile</code> and <code>payload</code> is still plain. Is the consumer guaranteed to see 42?</summary>

Yes.

```
(1) payload = 42      -po->  (2) volatile write ready
(2) volatile write    -hb->  (3) volatile read ready      <- edge #2
(3) volatile read     -po->  (4) read payload
therefore (1) -hb-> (4) by transitivity
```

The payload stays plain and that is the point: **you mark the door, not the payload.** One
volatile write is a release over everything written before it. Marking each payload field
volatile instead would be slower and would still not order them against each other.
</details>

<details>
<summary><b>4.</b> <code>writer.join()</code> is called from <code>main</code>. Which two threads does that edge connect? Does it help a reader thread that also read the writer's data?</summary>

Writer → main. Only. It does nothing for the reader, which never called `join`.

If you wanted writer → reader, the call would have to be *inside the reader*. Edges belong to the
thread that performs the acquiring action.
</details>

<details>
<summary><b>5.</b> <code>Holder</code> has <code>final int fin</code> and <code>int plain</code>, both set in the constructor, published through a plain non-volatile field. A reader observes a non-null reference. What is it entitled to see?</summary>

- `fin`: the constructor's value, guaranteed on every JVM and every CPU (edge #5), plus anything
  reachable through final fields as of the freeze.
- `plain`: **nothing.** It may legally read 0.
- And note the reader was never entitled to observe the reference at all.

`Concept02` Demo F prints `fin=123456, plain=123456` on this box. That proves nothing about
`plain`. Identical output, one guarantee.
</details>

<details>
<summary><b>6.</b> Broken DCL has a <code>synchronized</code> block around the construction. Why does adding synchronization there not fix it?</summary>

The unlock→lock edge exists only between threads that **both** enter the block. The fast-path
reader tests `instance == null` outside the lock and, when it sees non-null, returns immediately
without ever locking. It shares no edge with the constructing thread, so it can see a non-null
reference to a half-built object.

The field itself must carry the edge: `volatile`. Then the write is a release over the
constructor's writes and the fast-path read is the matching acquire.
</details>

<details>
<summary><b>7.</b> <code>volatile int counter;</code> incremented with <code>counter++</code> by 4 threads, 1M each. Final value?</summary>

Far below 4,000,000. `Concept03` loses 50–63% every run (1,481,836 observed on the run behind
this README, and `AtomicInteger` recovers the exact 4,000,000 in *less* time: 44 ms vs 62 ms).

`counter++` is three actions: volatile read, add, volatile write. Each is individually atomic and
visible. Nothing stops another thread completing its own read-add-write *between* your read and
your write: both read the same value, both add 1, both store the same result. Two increments, one
survivor.

The fix is not more `volatile`. It is an atomic read-modify-write: CAS (`AtomicInteger`) or mutual
exclusion (`synchronized`).
</details>

<details>
<summary><b>8.</b> <code>IntStream.range(0, N).parallel().forEach(i -> list.add(i))</code> where <code>list</code> is a <code>Collections.synchronizedList</code>. Is it correct? Is it good?</summary>

Correct: size is exactly N, no nulls, no exception. Every `add` takes the same monitor, so nothing
is lost.

Not good: the workers spend their time queueing on that one monitor. Measured in `Concept06`
Demo F on a 24-cpu box: **150 ms**, against 9 ms for `parallel().collect(toList())` and **5 ms for
a plain sequential collect.** The thread-safe fix is 30× slower than not parallelizing at all.

"Passes the test" and "is the right code" are different bars.
</details>

<details>
<summary><b>9.</b> Why can you not use <code>reduce()</code> to build a List efficiently?</summary>

`reduce`'s accumulator must be associative and non-interfering: it may **not** mutate its input,
because the framework can call it from several threads in any grouping. Honouring that for a List
means copying the accumulator on every element, which is O(n²). `Concept06` Demo G measures it:
5× the input costs **23×** the time (linear predicts 5, quadratic predicts 25).

Mutating the accumulator instead makes it fast and puts you straight back into a data race, since
the single identity instance is shared by every worker. It also breaks the identity contract.

`collect()` exists precisely because `reduce()` cannot mutate containers: it hands each worker its
own container and merges at the end.
</details>

<details>
<summary><b>10.</b> Which edge underlies <code>BlockingQueue.put()</code> / <code>take()</code>?</summary>

At the level you are entitled to rely on: the `java.util.concurrent` package specification says
actions in a thread before placing an element into a concurrent collection happen-before actions
after another thread removes that element.

Underneath, `ArrayBlockingQueue` is a `ReentrantLock` plus `Condition`s, so the primitive is edge
#1, unlock→lock. You do not need to know that, and code that depends on knowing it is depending on
an implementation detail.

Two bonuses that are not about memory at all: the consumer **blocks** instead of spinning, so it
burns no CPU waiting, and the value travels *in the queue*, so there is no shared mutable field
left to get wrong. `Concept07` Demo B still has three fields both threads touch, correct only
because of the flag.
</details>

<details>
<summary><b>11.</b> A colleague says "I ran the concurrency test 10,000 times on my laptop and it always passed." What have they established?</summary>

That their laptop is x86 and their JIT made a particular set of choices that day.

x86's strong store ordering hides most reordering bugs. `Concept04` Part B runs a genuine
broken-DCL publication race five million times and reports **zero** half-built objects on this
box, and reports it honestly as "0 ≠ correct". `Concept02` Demo F prints an identical number for a
guaranteed and an unguaranteed field.

Correctness under the JMM is a property of the *program*, established by finding the chain of
edges. It is not a property of an observed run, and no number of green runs substitutes for it.
</details>

---

## Reading order

1, 2, 3 in order: they build on each other and 2 is the spine. Then 7, which is 2 applied to the
one shape you will actually write. Then 4, 5, 6 in any order as the applied cases.

When a demo surprises you, the question to ask is never "why did it print that?" It is **"which
chain of edges entitles anyone to that answer?"** If you cannot name the chain, the program is
wrong even when the output is right.
