<!-- ABOUTME: Study guide for the org.example.equality demos: the equals/hashCode contract, mutable
     ABOUTME: keys, inheritance, compareTo-based collections, records, and a self-test. -->

# equals/hashCode and collection behaviour, by demonstration

Five runnable demos.

```bash
mvn compile exec:java -Dexec.arguments=3.1     # one concept
mvn compile exec:java -Dexec.arguments=3       # the whole track
```

---

## This track is different from the other two

| Track | When the mistake surfaces |
|---|---|
| 1, memory model | at runtime, sometimes, on some hardware, under load |
| 2, generics | **at compile time, every time.** Reversed wildcards do not compile |
| **3, equality** | **never.** It compiles, runs, throws nothing, returns the wrong answer |

There is no compiler here and no stack trace. A broken `equals` produces a lookup that
quietly fails, a row that quietly vanishes, an entry that can never be removed. The only
defence is knowing what the collection is actually doing, which is why Concept 3.1 starts
with the lookup algorithm rather than with the rule.

---

## The one rule

**`hashCode` picks the bucket. `equals` decides inside it.**

A `HashMap` does not search. It computes:

1. `h = key.hashCode()`, then spreads the bits
2. `bucket = h & (table.length - 1)` — **one** bucket, chosen arithmetically
3. walk that bucket only, comparing with `==` then `equals`

It never looks in any other bucket. So two objects that are `equals` but hash differently
are filed apart and **never compared** — the failure is not a wrong answer from `equals`, it
is a question `equals` was never asked.

### The contract, in the order it matters

**hashCode**

- **(h1)** equal objects **must** return equal hash codes ← break this and lookups vanish
- **(h2)** hashCode must stay consistent while the object is in use ← Concept 3.2
- **(h3)** unequal objects **may** share a hash code ← collisions are *legal*

**equals** — reflexive, symmetric, transitive, consistent, and `x.equals(null)` is false.

Note the asymmetry people invert: **(h1) is a requirement, (h3) is a permission.** A
`hashCode` returning a constant is perfectly *correct*, merely slow: Concept 3.1 Demo D
stores 20,000 keys correctly with `return 1`, taking 1,495 ms against 5 ms. A `hashCode`
that is beautifully distributed but inconsistent with `equals` is *incorrect*, and nothing
will tell you.

---

## The four ways it goes wrong

### 1. Only half the pair

`equals` without `hashCode` → equal objects, different buckets, never compared. A `HashSet`
holds both, and `contains` on an equal object returns false.

`hashCode` without `equals` → right bucket, but `equals` is still identity, so it says no.

Both produce duplicates in a Set. Your IDE and `-Xlint:overrides` will both warn about the
first; take the warning.

### 2. A mutable key

The bucket is computed **once**, at insertion, and never revisited. Mutate a field the hash
reads and the object is filed under an address that no longer matches it:

```
contains(theVeryObject) -> false
remove(theVeryObject)   -> false, and it stays
size()                  -> still 1
for (x : set)           -> yields it perfectly happily
```

All four at once. Iteration walks every bucket so it finds the object; lookup goes to one
bucket so it does not. **You cannot get it out through the API that put it in** — the entry
is pinned for the collection's lifetime. `TreeSet` has the identical hazard via `compareTo`.

The fix is not vigilance. It is immutability: a record, a `String`, an enum, or equality
anchored to a `final` id while the rest of the object moves freely.

### 3. Inheritance

You cannot extend an instantiable class, **add a value component**, and keep the contract.
Three attempts, three different broken clauses:

| Attempt | Keeps | Breaks |
|---|---|---|
| `instanceof` + compare the new field | transitivity | **symmetry** — and `list.contains` answers differently depending on argument position |
| compare blind when the other side is the base type | symmetry | **transitivity** |
| `getClass() != o.getClass()` | symmetry, transitivity | **substitutability** — a subclass adding *no* state is now unequal, which is what breaks runtime proxies |

The way out is not a cleverer `equals`. It is composition: hold the object instead of
extending it, or make the class `final` so the question cannot arise. Extending *without*
adding a value component is fine.

### 4. Assuming sorted collections use equals

They do not. `TreeSet` and `TreeMap` define identity as `compareTo() == 0` and **never call
`equals`, for any operation.** So "duplicate" is decided by the collection, not by your type.

`Comparable`'s javadoc calls consistency with `equals` "strongly recommended" and then says
it is not required. Nothing enforces it. What it costs is specified precisely: a `SortedSet`
with an inconsistent comparator *works correctly but no longer obeys the `Set` contract* —
and it will still be handed to code that expects a `Set`.

---

## What the JDK already gives you

- **Lists** are equal across implementations (`ArrayList` equals `LinkedList`) but never
  across types (`List` never equals `Set`). Order matters.
- **Sets** ignore order entirely. **Maps** compare keys *and* values.
- **Arrays are the exception**: `equals` and `hashCode` are pure identity. Two arrays with
  identical contents are never equal. Use `Arrays.equals` / `Arrays.hashCode`, or
  `deepEquals` / `deepHashCode` when nested.
- **Records** generate a correct pair and are **implicitly final**, so problem 3 cannot
  arise. For a value type this is the right default.

**The one gap:** a record with an **array component** is broken by default. The generated
`equals` compares each component the way that component compares, and for an array that is
identity. It also makes the record mutable through the back door. Prefer `List<T>`; if you
cannot, write `equals` with `Arrays.equals` and copy defensively in both the compact
constructor and the accessor.

---

## Self-test

<details>
<summary><b>1.</b> You override <code>equals</code> and forget <code>hashCode</code>. A <code>HashSet</code> now contains two objects that are <code>equals</code>. Why was the duplicate not detected — what specifically failed?</summary>

Nothing failed. `equals` was **never called**.

`add` computes `hashCode`, derives one bucket, and compares only against what is already in
*that* bucket. The inherited `Object.hashCode` is identity-based, so the two equal objects
produced different hashes and landed in different buckets. The second `add` looked in a
bucket the first object was not in, found nothing to compare against, and stored a second
entry.

This is the distinction worth holding: not "equals gave the wrong answer" but "equals was
never asked". It is also why the fix has to be `hashCode` — no amount of improving `equals`
can help a comparison that does not happen.
</details>

<details>
<summary><b>2.</b> An object is in a <code>HashSet</code>. You mutate a field its <code>hashCode</code> reads. Describe all four observable effects.</summary>

- `contains(theObject)` → **false**
- `remove(theObject)` → **false**, and the element stays
- `size()` → **unchanged**, it is still counted
- iterating the set → **yields it**

Present and unreachable simultaneously. Iteration walks every bucket, so it finds the
object; lookup computes the *new* hash and goes to the *new* bucket, where the object is
not, because it was filed under the old one at insertion time and nothing re-files it.

The practical consequence is a leak: the entry cannot be removed through the API that
inserted it, so it is pinned for the collection's lifetime. And nothing throws.
</details>

<details>
<summary><b>3.</b> Why can't <code>ColorPoint extends Point</code> have a correct <code>equals</code> that includes the colour?</summary>

Because every available strategy breaks a different clause.

With `instanceof`, `point.equals(colorPoint)` is true (Point only looks at x,y) while
`colorPoint.equals(point)` is false. **Symmetry gone** — and observably so, since
`ArrayList.contains(o)` calls `o.equals(element)`, making the answer depend on which object
you passed.

Compare blind when the other side is a plain `Point` and symmetry returns, but now
`red = plain` and `plain = blue` while `red ≠ blue`. **Transitivity gone.**

Use `getClass()` and both hold — at the cost of a subclass that adds *no* state being
unequal to its parent. **Substitutability gone.** This is what breaks Hibernate and Mockito
proxies, which are generated subclasses adding no value component.

The escape is not a fourth strategy. It is composition, or a `final` class. Records are
implicitly final, which is one reason they are the right default for value types.
</details>

<details>
<summary><b>4.</b> <code>new BigDecimal("1.0")</code> and <code>new BigDecimal("1.00")</code>: what is the size of a <code>HashSet</code> containing both, and of a <code>TreeSet</code>?</summary>

`HashSet` → **2**. `TreeSet` → **1**.

`equals` is false (the scales differ, and scale is meaningful for money) while `compareTo`
returns 0 (the values are numerically equal). `HashSet` asks `equals`; `TreeSet` asks
`compareTo` and never calls `equals` at all.

Neither is wrong. They answer different questions, and you chose which by picking a
collection. The sharpest form of this, in Concept 3.4 Demo C: `TreeSet.contains(x)` returns
**true** for an `x` that is `equals` to *nothing* in the set.
</details>

<details>
<summary><b>5.</b> Is a <code>hashCode</code> that always returns 1 correct?</summary>

**Yes.** Correct, and awful.

Clause (h3) explicitly permits unequal objects to share a hash code, so a constant satisfies
the contract: every lookup lands in one bucket and `equals` sorts it out from there. Concept
3.1 Demo D stores 20,000 distinct keys with complete accuracy in **1,495 ms**, against 5 ms
for a spread hash — roughly 300×, because the bucket degenerates into a list (a tree, above
the treeify threshold).

Worth internalising because it separates the two failure modes cleanly: **collisions cost
speed, inconsistency costs correctness.** Only one of those is a bug.
</details>

<details>
<summary><b>6.</b> You write <code>record Reading(String sensor, int[] samples)</code>. Two instances with identical contents. Equal?</summary>

**No**, and the record is behaving correctly.

The generated `equals` compares each component the way that component compares. Arrays
inherit `Object.equals`, which is identity, so two arrays with the same contents are never
equal and never share a hash code.

There is a second problem in the same line: the component is a live reference to the
caller's array, so the "immutable" record is mutable through the back door. Mutate the array
you passed in and the record's contents change with it — Concept 3.2's hazard wearing a
record's clothes.

Fix by using `List<Integer>` instead. If the array is unavoidable, override `equals` and
`hashCode` with `Arrays.equals` / `Arrays.hashCode` **and** copy in the compact constructor
and the accessor.
</details>

<details>
<summary><b>7.</b> <code>new ArrayList&lt;&gt;(List.of(1,2))</code> vs <code>new LinkedList&lt;&gt;(List.of(1,2))</code>: equal? And <code>List.of(1,2)</code> vs <code>Set.of(1,2)</code>?</summary>

`ArrayList` equals `LinkedList` → **true**. `List` equals `Set` → **false**.

`AbstractList.equals` is defined by the `List` *contract*, not by the implementing class:
same elements in the same order means equal, whatever the class. Sets do the same thing
order-independently, so a `HashSet` equals a `LinkedHashSet` with the same members.

Across types it is always false, and deliberately: `List` and `Set` have different contracts,
and `Collection` itself defines **no** `equals` at all, leaving it to `Object`'s identity.
That is why there is no such thing as comparing two `Collection`s for content equality
without picking a type first.
</details>

---

## The short version

For a value type, **write a record.** It generates a correct pair, it is implicitly final so
the inheritance problem cannot occur, and it is immutable so the mutable-key problem cannot
occur. Three of this track's four hazards disappear by construction.

The fourth, sorted collections using `compareTo`, is not about your type at all. Before
handing a comparator to a `TreeSet`, ask what it makes indistinguishable, because whatever
that is will be silently discarded.
