<!-- ABOUTME: Deliberately broken generics examples that must NOT compile, with the real javac
     ABOUTME: output, so the compiler's own diagnosis is the lesson rather than a paraphrase. -->

# Wrong turns

Six files that **do not compile, on purpose**. They live outside `src/main/java`, so Maven
never sees them and `mvn compile` stays green.

The point is not the mistake. It is the *error message*: javac names the captured type
variable, tells you what it knows about it, and thereby explains the rule mechanically.
Reading `CAP#1 extends Number from capture of ? extends Number` teaches more than any
mnemonic, because it is the actual reason.

## Running them

```bash
javac wrong-turns/W1AddToProducer.java          # expect: 1 error
javac -Xdiags:verbose wrong-turns/W1AddToProducer.java   # the fuller diagnosis
```

Compile them one at a time. `-Xdiags:verbose` is worth it on W1 and W4: javac says
"some messages have been simplified" and the unsimplified version is the interesting one.

## The six

| File | The mistake | What javac says |
|------|-------------|-----------------|
| `W1AddToProducer` | `add()` into a `List<? extends Number>` | `no suitable method found for add(int)`, `CAP#1 extends Number` |
| `W2ReadTypedFromConsumer` | expecting a typed `get()` from `List<? super Number>` | `CAP#1 cannot be converted to Number`, `CAP#1 extends Object super: Number` |
| `W3Invariance` | `List<Number> = new ArrayList<Integer>()` | `incompatible types: ArrayList<Integer> cannot be converted to List<Number>` |
| `W4PecsBackwards` | PECS reversed on a copy method | `required: int,CAP#1 / found: int,CAP#2` |
| `W5NoWildcardTooStrict` | no wildcard at all, so the call site is rejected | `incompatible types: List<Integer> cannot be converted to List<Number>` |
| `W6GenericArray` | `new T[10]` and `o instanceof List<String>` | `generic array creation`, `Object cannot be safely cast to List<String>` |

## W4 is the one to study

It is the mistake this whole track exists to prevent: the wildcards written the wrong way
round. Compile it with `-Xdiags:verbose` and read the output slowly.

```
error: method set in interface List<E> cannot be applied to given types;
    dest.set(i, src.get(i));
  required: int,CAP#1
  found:    int,CAP#2
  reason: argument mismatch; Object cannot be converted to CAP#1
  where CAP#1,CAP#2 are fresh type-variables:
    CAP#1 extends T from capture of ? extends T
    CAP#2 extends Object super: T from capture of ? super T
```

In words: *you are trying to write into the thing you declared readable, using a value out
of the thing you declared writable.* Both halves are backwards, and each half fails for its
own reason.

The reassuring part: this mistake cannot reach production. Reversed wildcards produce a
method whose body will not compile, or one that no caller can satisfy. The compiler catches
it every single time. What it costs you is the ten minutes spent staring at `CAP#1` wondering
what went wrong, which is exactly what this directory is for.
