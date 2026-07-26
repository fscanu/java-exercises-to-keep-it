// ABOUTME: Shows when a bounded type parameter is required and when a wildcard is the better tool,
// ABOUTME: including why Comparable<? super T> is not decoration and what Object & does to erasure.
package org.example.generics;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/*
 * =====================================================================================
 * Concept #5 - "Bounded type parameters vs wildcards"
 * =====================================================================================
 *
 * Both widen what a method accepts. They are not interchangeable, and the choice is
 * mechanical once you ask the right question:
 *
 *      Do I need to NAME this type - to return it, to relate two parameters to each
 *      other, or to declare a local of it?
 *
 *          YES -> bounded type parameter, <T extends ...>
 *          NO  -> wildcard, ? extends / ? super
 *
 * The practical rule of thumb, and it is a reliable one: IF A TYPE VARIABLE APPEARS
 * EXACTLY ONCE IN A SIGNATURE, IT SHOULD PROBABLY BE A WILDCARD. A name you use once is
 * a name you did not need. `void printAll(List<?> l)` says what it means; the equivalent
 * `<T> void printAll(List<T> l)` invents a T that nothing else refers to.
 *
 * Conversely, the moment two positions must agree - dest and src in a copy, or the list
 * and the return type in a max - a wildcard cannot express it. Each `?` is a SEPARATE
 * unknown. Two wildcards in one signature are two different captures, and nothing tells
 * the compiler they are the same type. That is what a named T is for: it is the only way
 * to say "this one and that one are the same".
 *
 * -------------------------------------------------------------------------------------
 * WHY Comparable<? super T> AND NOT Comparable<T>
 * -------------------------------------------------------------------------------------
 * This is the sharpest case, because `Comparable<T>` looks obviously right and is wrong
 * for a reason you can watch happen. Suppose:
 *
 *      class Animal implements Comparable<Animal> { ... }
 *      class Dog extends Animal { }                     // inherits compareTo(Animal)
 *
 * Dog is comparable - it inherited a perfectly good compareTo. But Dog implements
 * Comparable<ANIMAL>, not Comparable<Dog>. So a bound of `T extends Comparable<T>` cannot
 * be satisfied by T = Dog, and javac says so precisely (Demo A, real message):
 *
 *      reason: inference variable T has incompatible equality constraints Animal,Dog
 *
 * T must be Dog (it came from a List<Dog>) and must be Animal (that is what Dog is
 * Comparable to). Both, simultaneously. Contradiction.
 *
 * `Comparable<? super T>` states the actual requirement instead: "T must be comparable to
 * itself OR TO ANY SUPERTYPE OF ITSELF." Dog is Comparable<Animal>, Animal is a supertype
 * of Dog, so it fits, and T stays Dog. Apply the derivation from Concept #3 and you get
 * the same answer without the story: compareTo CONSUMES a T, and consumers take super.
 *
 * A NUANCE WORTH KNOWING, since it explains why some JDK methods look fine with the wrong
 * bound: if the PARAMETER is `List<? extends T>` rather than `List<T>`, inference has room
 * to escape - it can simply choose T = Animal and both constraints are satisfied. So
 * `<T extends Comparable<T>> T max(List<? extends T>)` compiles for a List<Dog> after all,
 * silently widening T to Animal. The bug only bites where T is pinned by an invariant
 * parameter. Demo A pins it deliberately to make the failure visible.
 *
 * -------------------------------------------------------------------------------------
 * THE `Object &` IN Collections.max, AND WHAT IT IS FOR
 * -------------------------------------------------------------------------------------
 * Concept #4 printed:
 *
 *      public static <T extends Object & Comparable<? super T>> T max(Collection<? extends T>)
 *
 * That `Object &` is not noise. A type variable erases to its FIRST bound (Concept #1).
 * With `<T extends Comparable<? super T>>` alone, T would erase to Comparable and the
 * method's descriptor would be (Collection)Comparable. Java 1.4 shipped this method as
 * `public static Object max(Collection)`, and changing a descriptor breaks every already
 * compiled caller. Naming Object as the first bound forces the erasure back to Object and
 * keeps the old binary signature. Demo E reads the descriptor and shows it really is
 * Object - a generics decision made entirely to serve erasure.
 * =====================================================================================
 */
public final class Generics05BoundedVsWildcard {

    static class Animal implements Comparable<Animal> {
        final String name; final int size;
        Animal(String n, int s) { name = n; size = s; }
        @Override public int compareTo(Animal other) { return Integer.compare(size, other.size); }
        @Override public String toString() { return name; }
    }
    static class Dog extends Animal { Dog(String n, int s) { super(n, s); } }

    // The correct bound: T comparable to itself or to any supertype.
    static <T extends Comparable<? super T>> T maxOf(List<T> list) {
        T best = list.get(0);
        for (T t : list) if (t.compareTo(best) > 0) best = t;
        return best;
    }

    // The tempting-but-wrong bound, kept for the record. Uncomment the call in Demo A.
    // static <T extends Comparable<T>> T maxOfStrict(List<T> list) { ... }

    // =================================================================================
    // Demo A - Comparable<? super T> accepts a Dog; Comparable<T> cannot
    // =================================================================================
    private String demoComparableSuper() {
        List<Dog> dogs = new ArrayList<>(Arrays.asList(
                new Dog("chihuahua", 2), new Dog("mastiff", 9), new Dog("beagle", 5)));

        Dog biggest = maxOf(dogs);   // T = Dog. Dog is Comparable<Animal>, Animal super Dog. Fits.

        // With `<T extends Comparable<T>> T maxOfStrict(List<T>)` the same call fails:
        //   error: method maxOfStrict cannot be applied to given types;
        //     required: List<T>
        //     found:    List<Dog>
        //     reason: inference variable T has incompatible equality constraints Animal,Dog
        // T would have to be Dog and Animal at the same time.

        return "maxOf(List<Dog>) = " + biggest + " (T stays Dog); Comparable<T> would demand "
                + "T be Dog and Animal at once";
    }

    // =================================================================================
    // Demo B - two parameters must AGREE: only a named T can say that
    // =================================================================================
    // Each `?` is its own unknown. `void copy(List<? super ?>, List<? extends ?>)` is not
    // merely bad style, it cannot express the constraint at all. T is what links them.
    static <T> void copyInto(List<? super T> dest, List<? extends T> src) {
        for (int i = 0; i < src.size(); i++) dest.set(i, src.get(i));
    }

    private String demoLinkedParameters() {
        List<Integer> src = Arrays.asList(1, 2, 3);
        List<Number> dest = new ArrayList<>(Arrays.asList(0, 0, 0));
        copyInto(dest, src);   // T = Integer, linking a List<Number> to a List<Integer>

        return "copyInto(List<Number>, List<Integer>) -> " + dest
                + " : T is what states that dest can hold what src yields";
    }

    // =================================================================================
    // Demo C - the RETURN type needs a name; a wildcard loses it
    // =================================================================================
    static <T> T firstOf(List<? extends T> list) { return list.get(0); }  // keeps the type
    static Object firstOfLossy(List<?> list) { return list.get(0); }      // throws it away

    private String demoReturnType() {
        List<String> words = Arrays.asList("alpha", "beta");

        String typed = firstOf(words);            // no cast needed
        Object untyped = firstOfLossy(words);     // caller must cast to do anything useful
        int lengthAfterCast = ((String) untyped).length();

        return "firstOf -> \"" + typed + "\" (typed, length " + typed.length()
                + "); firstOfLossy -> Object, caller forced to cast before length ("
                + lengthAfterCast + ")";
    }

    // =================================================================================
    // Demo D - appears once, so a wildcard is the simpler and better choice
    // =================================================================================
    static int countNonNull(Collection<?> items) {          // ? used once: no name needed
        int n = 0;
        for (Object o : items) if (o != null) n++;
        return n;
    }

    private String demoSingleOccurrence() {
        int n = countNonNull(Arrays.asList("a", null, "c"));
        return "countNonNull(Collection<?>) = " + n
                + " : the type appears once, so naming it would add nothing";
    }

    // =================================================================================
    // Demo E - Object & Comparable: a bound written to control ERASURE
    // =================================================================================
    private String demoErasureOfBound() {
        String descriptor;
        try {
            Method max = Collections.class.getMethod("max", Collection.class);
            descriptor = max.getReturnType().getName();   // the ERASED return type
        } catch (NoSuchMethodException e) {
            return "UNEXPECTED: Collections.max not found";
        }
        return "Collections.max declares <T extends Object & Comparable<? super T>> and erases its "
                + "return to " + descriptor + " - not Comparable - preserving the 1.4 binary signature";
    }

    private void run() {
        System.out.println("Track 2, Concept #5 - bounded type parameters vs wildcards");
        System.out.println("=========================================================");
        line("A  Comparable<? super>", demoComparableSuper());
        line("B  linked parameters  ", demoLinkedParameters());
        line("C  named return type  ", demoReturnType());
        line("D  used once: wildcard", demoSingleOccurrence());
        line("E  Object & erasure   ", demoErasureOfBound());
        System.out.println();
        System.out.println("Takeaway: use a wildcard unless you need to NAME the type. You need to name it");
        System.out.println("to return it, or to say that two parameters share it - each ? is a separate");
        System.out.println("unknown and can never express agreement. And when you do write a bound, write");
        System.out.println("the one the code actually needs: Comparable<? super T>, because compareTo is a");
        System.out.println("consumer, and consumers take super. The same question, one level up.");
    }

    private static void line(String label, String verdict) {
        System.out.println("  [" + label + "] " + verdict);
    }

    public static void main(String[] args) {
        new Generics05BoundedVsWildcard().run();
    }
}
