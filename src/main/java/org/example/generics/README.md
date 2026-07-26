<!-- ABOUTME: Study guide for the org.example.generics demos: erasure, variance, PECS derived from
     ABOUTME: capture, the canonical JDK signatures, bounded parameters, and a self-test. -->

# Generics variance, by demonstration

Five runnable demos. The goal is not to remember PECS. It is to be able to **rebuild** it
from erasure in about thirty seconds, so that when the mnemonic evaporates under pressure
the answer is still reachable.

```bash
mvn compile exec:java -Dexec.arguments=2.1     # one concept
mvn compile exec:java -Dexec.arguments=2       # the whole track
```

---

## The one rule

**Generics are checked at compile time and erased before runtime.** Everything else follows.

The compiler is the last checkpoint before the type information is destroyed. There is no
second chance, no runtime check to fall back on, no exception that can catch a mistake
later. A checker in that position must be **conservative**: it has to reject anything it
cannot prove, because "reject it" is the only tool it has left.

Every rule in this track is that conservatism applied to a specific case. None of them are
arbitrary, and none of them need memorising once you see what the compiler is protecting.

---

## What erasure erases

Precision here prevents a lot of confusion:

| | Erased? | Evidence |
|---|---|---|
| An **instance's** type arguments | **Yes, completely.** An `ArrayList<String>` object has no memory of `String` | `2.1` Demo A: `getClass()` returns the identical `Class` object for both |
| A **declaration's** generic signature | **No.** Field types, method signatures and supertypes survive in the class file | `2.1` Demo C reads `List<String>` back off a field; `2.4` reads the whole JDK this way |

So generic information is not absent from class files: it is absent from *objects*. Which
is exactly what a runtime check would have needed.

Three consequences you cannot program around, all in `wrong-turns/W6GenericArray.java`:
`new T[10]`, `o instanceof List<String>`, and two overloads differing only in type arguments.

---

## Variance, and why arrays got a different answer

| | Java's choice | Paid for with |
|---|---|---|
| **Arrays** | covariant: `Dog[]` **is an** `Animal[]` | a runtime check on **every store**, throwing `ArrayStoreException` |
| **Generics** | invariant: `List<Dog>` is **not** a `List<Animal>` | compile-time rejection, because no runtime check is possible |

Arrays can be covariant because an array knows its component type at runtime.
`new String[2]` really is a `String[]`, and `getClass().getComponentType()` will say so.
Generics were erased, so the equivalent check has nothing to check against. Invariance is
not a design preference; it is the only sound option left once you erase.

Wildcards then hand back one direction of flexibility each, and pay for it by **deleting
the operation that would break**. Which operation gets deleted is the whole of PECS.

---

## PECS, derived

Do not recite it. Ask one question, per parameter:

> **Does data come OUT of this parameter, or go INTO it?**
>
> - comes **out** (I read from it) → it **produces** for me → `? extends T`
> - goes **in** (I write to it) → it **consumes** from me → `? super T`
> - **both** → no wildcard is possible. Use a plain `T`.

Say it as a sentence about data flow. Data flow is visible in the method body; the mnemonic
is not, which is why the mnemonic is the thing that fails.

### The mechanism, so you can rebuild it

When the compiler meets a wildcard it performs **capture**: it invents a fresh nameless type
variable for the one specific type that is actually there. javac calls it `CAP#1`, and will
show it to you in any error message.

**`List<? extends Number>`** captures as `CAP#1 extends Number`:

- **read** → `get()` returns `CAP#1`, and everything `CAP#1` could be is a `Number`. Sound. **Allowed.**
- **write** → `add()` wants a `CAP#1`, and *nobody knows what `CAP#1` is*. The list might really be a `List<Integer>`, might be a `List<Double>`. No value can be proven to be a `CAP#1`. **Forbidden.** (Except `null`, which is every type.)

**`List<? super Integer>`** captures as `CAP#1 extends Object super: Integer`:

- **write** → `add()` wants a `CAP#1`, which is *some supertype of `Integer`*, so an `Integer` is assignable to it whatever it turns out to be. **Allowed.**
- **read** → `get()` returns `CAP#1`, and the only thing known about `CAP#1` is its upper bound, `Object`. **Forbidden** to type it as anything narrower.

In each case exactly one operation is provably sound and the other is provably not. The
wildcard keeps the sound one. That is all PECS is: a name for which half survives.

### The five signatures, derived

| Method | Question | Answer |
|---|---|---|
| `sum(List<? extends Number>)` | `doubleValue()` reads elements out | producer → `extends` |
| `addIntegers(List<? super Integer>)` | elements are written in | consumer → `super` |
| `printAll(List<?>)` vs `List<Object>` | reads only, and `List<?>` ≡ `List<? extends Object>` | wildcard accepts any list; `List<Object>` accepts only `List<Object>` |
| `<T extends Comparable<? super T>> T max(List<? extends T>)` | list produces; `compareTo` consumes a `T` | `extends` on the list, `super` on `Comparable` |
| `forEachDo(List<? extends T>, Consumer<? super T>)` | source produces, action consumes | one of each, in one signature |

And the one that puts both on a single parameter, `Stream.map`:

```java
<R> Stream<R> map(Function<? super T, ? extends R> mapper)
```

The function **consumes** a `T` and **produces** an `R`. Two type arguments, the same
question asked twice, independently. If you can read that signature and say why each side
is what it is, you have the concept.

---

## Bounded type parameter, or wildcard?

> Do I need to **name** this type: to return it, to say two parameters share it, or to
> declare a local of it?
>
> **Yes** → `<T extends ...>`  **No** → `? extends` / `? super`

The reliable rule of thumb: **a type variable that appears exactly once in a signature
should probably be a wildcard.** A name used once is a name you did not need.

And the converse matters just as much: **each `?` is a separate unknown.** Two wildcards in
one signature are two different captures, and nothing tells the compiler they are related.
When `dest` and `src` must agree, only a named `T` can say so.

### `Comparable<? super T>` is not decoration

```java
class Animal implements Comparable<Animal> { }
class Dog extends Animal { }          // inherits compareTo(Animal)
```

`Dog` is perfectly comparable, but it implements `Comparable<Animal>`, **not**
`Comparable<Dog>`. So a bound of `T extends Comparable<T>` cannot be satisfied by `T = Dog`:

```
reason: inference variable T has incompatible equality constraints Animal,Dog
```

`T` would have to be `Dog` (it came from a `List<Dog>`) and `Animal` (that is what `Dog` is
comparable to) simultaneously. `Comparable<? super T>` states the real requirement, and
the derivation gets you there anyway: `compareTo` consumes a `T`, consumers take `super`.

**A nuance that hides the bug:** if the parameter is `List<? extends T>` instead of
`List<T>`, inference can escape by choosing `T = Animal`, and even the wrong bound
compiles. The failure only shows where `T` is pinned by an invariant parameter. Demo `2.5A`
pins it deliberately.

---

## Hands-on

1. **Cold recall.** Write `Collections.copy`'s real signature on paper, from memory, before
   reading anything here. Then run `2.4`, which prints it from the class file, and diff.
   Getting the parameter order right is not the test; getting the wildcards right is.

2. **The five methods.** Write `sum`, `addIntegers`, `printAll`, a generic `max`, and a
   `Consumer`-based method from scratch. For each parameter, say the data-flow sentence out
   loud before you type the wildcard. Check against `2.3`.

3. **Take the wrong turn on purpose.** `cd` to [`wrong-turns/`](../../../../../../wrong-turns)
   and compile them. Read what javac actually says, especially W4 with `-Xdiags:verbose`.
   Seeing the capture variable named in an error is what makes the rule reconstructible.

---

## Self-test

The four core questions first. Reason to the answer before opening it; if the only answer
you can produce is "PECS says so", that is the signal to re-read the capture section.

<details>
<summary><b>1.</b> Why can't you <code>add()</code> to a <code>List&lt;? extends T&gt;</code> — mechanically, not "PECS says so"?</summary>

Because `add` requires an argument of the **captured** type, and nothing is known about that
type except its upper bound.

`? extends T` captures as a fresh `CAP#1 extends T`. `add(x)` needs `x` to be a `CAP#1`. But
`CAP#1` might really be `T`, or any subtype of `T`, and the compiler cannot tell which. If
the list is really a `List<Dog>` and you add a `Cat`, nothing at runtime would catch it
(erasure), and the failure would surface later in unrelated code.

So there is **no value the compiler can prove is a `CAP#1`** — and it refuses. The single
exception is `null`, which is a member of every reference type.

javac, verbatim, from `wrong-turns/W1AddToProducer.java -Xdiags:verbose`:

```
error: no suitable method found for add(int)
    method List.add(CAP#1) is not applicable
      (argument mismatch; int cannot be converted to CAP#1)
  where CAP#1 is a fresh type-variable:
    CAP#1 extends Number from capture of ? extends Number
```
</details>

<details>
<summary><b>2.</b> Why can't you <code>get()</code> a <code>T</code> — only <code>Object</code> — from a <code>List&lt;? super T&gt;</code>?</summary>

Because the captured type's **upper** bound is `Object`, and a read is typed by the upper
bound.

`? super T` captures as `CAP#1 extends Object super: T`. All you know is that `CAP#1` sits
somewhere *above* `T` in the hierarchy: it could be `T` itself, or `Number`, or
`Serializable`, or `Object`. Since `Object` is the only thing every candidate has in common,
`get()` can only be typed as `Object`.

Note the pleasing symmetry with question 1. Writing needs a **lower** bound (is my value
assignable *to* the unknown type?); reading needs an **upper** bound (is the unknown type
assignable *to* my variable?). `? extends` gives you an upper bound and so permits reads;
`? super` gives you a lower bound and so permits writes.

```
error: incompatible types: CAP#1 cannot be converted to Number
  where CAP#1 is a fresh type-variable:
    CAP#1 extends Object super: Number from capture of ? super Number
```
</details>

<details>
<summary><b>3.</b> In <code>Collections.copy(List&lt;? super T&gt; dest, List&lt;? extends T&gt; src)</code>, which is the producer and which the consumer?</summary>

- `src` is **read from** by `copy` → it *produces* elements → `? extends T`
- `dest` is **written into** by `copy` → it *consumes* elements → `? super T`

The wildcards follow directly, and note what makes this signature the one people
misremember: **the parameter order is `(dest, src)`, the reverse of the order the data
flows.** You read from the second and write to the first.

That is why memorising "copy is super-then-extends" is fragile and deriving it is not. Do
not recall the order. Look at which parameter the method assigns into.

The payoff is real: `copy(List<Number>, List<Integer>)` is accepted. With plain `List<T>`
for both, invariance would reject it outright.
</details>

<details>
<summary><b>4.</b> Why doesn't Java's (unsound) array covariance carry over to generics?</summary>

Because array covariance is only survivable thanks to a runtime check that generics cannot
have.

`Dog[]` is an `Animal[]`, so through the `Animal[]` reference you can attempt to store a
`Cat`. Java permits the *assignment* and then checks **every array store at runtime**,
throwing `ArrayStoreException` on mismatch. It can do that because an array carries its
component type into the running program.

A `List<Dog>` does not know it is a `List<Dog>` — erasure removed it. So the equivalent
check is not merely expensive, it is **impossible**: there is nothing to compare against and
no `ListStoreException` to throw. If generics were covariant, `objects.add(42)` on an
aliased `List<String>` would succeed silently and blow up later in innocent code, exactly
like `2.1` Demo B.

Arrays chose *allow it, check at runtime*. Generics could not choose that, so they chose
*reject it at compile time*. Same problem, different information available, opposite answer.

(Array covariance is generally regarded as a Java 1.0 mistake, made before generics existed
so that methods like `Arrays.sort(Object[])` could be written at all. Generics are what that
problem should have been solved with in the first place.)
</details>

Three more, on the parts that are easy to half-know:

<details>
<summary><b>5.</b> Why does <code>Supplier&lt;T&gt;</code> declare <code>T get()</code> with no wildcard, when a Supplier is the purest producer there is?</summary>

Because wildcards belong on **parameters**, not return types.

A wildcard widens what a *caller may pass in*. On a return type it does the opposite: it
hands the caller a capture instead of a usable type, so they must cast to do anything. See
`2.5` Demo C: `firstOf` returns a typed `T`, `firstOfLossy` returns `Object` and forces the
caller to cast.

"Producer extends" describes how to declare a parameter you will read from. `Supplier`'s `T`
is already in producing position *for the caller* — there is nothing to widen.
</details>

<details>
<summary><b>6.</b> When should a type variable be a wildcard rather than a named parameter?</summary>

When it appears **exactly once** in the signature. A name used once is a name you did not
need: `int countNonNull(Collection<?> items)` says everything, and `<T> int countNonNull(Collection<T> items)` invents a `T` nothing else refers to.

You need the name when two positions must **agree**: the return type and a parameter
(`<T> T firstOf(List<? extends T>)`), or two parameters (`copy`'s `dest` and `src`). Each
`?` is an independent capture, so wildcards can never express agreement between positions.
</details>

<details>
<summary><b>7.</b> <code>Collections.max</code> is declared <code>&lt;T extends Object &amp; Comparable&lt;? super T&gt;&gt;</code>. What is the <code>Object &amp;</code> doing?</summary>

Controlling **erasure**, for binary compatibility.

A type variable erases to its *first* bound. With `<T extends Comparable<? super T>>` alone,
`T` would erase to `Comparable` and the method descriptor would be
`(Collection)Comparable`. But Java 1.4 shipped this method as
`public static Object max(Collection)`, and changing a descriptor breaks every already
compiled caller.

Naming `Object` first forces the erasure back to `Object`. `2.5` Demo E reads the erased
return type at runtime and confirms it is `java.lang.Object`.

A generics declaration written entirely to satisfy erasure — which is a fitting place to
end a track that began with erasure explaining everything else.
</details>

---

## If you get it backwards again

Do not re-memorise. Do this instead, in order:

1. Name the parameter and open the method body.
2. Say out loud: *"data comes out of this one"* or *"data goes into this one."*
3. Out → `? extends`. In → `? super`. Both → no wildcard.
4. If still unsure, write it the other way and compile it. javac will name the capture
   variable and tell you exactly which half is unsound, in about two seconds.

Step 4 is not a defeat. The reversed version does not compile, ever, which means this is one
of the few mistakes in Java that genuinely cannot reach production.
