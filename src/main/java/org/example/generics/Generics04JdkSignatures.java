// ABOUTME: Prints the canonical wildcard signatures straight out of the JDK class files via
// ABOUTME: reflection, then uses each one, so the examples cannot drift from the real declarations.
package org.example.generics;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/*
 * =====================================================================================
 * Concept #4 - "The canonical JDK signatures"
 * =====================================================================================
 *
 * Every signature below is printed by REFLECTION, read out of the shipped class file's
 * Signature attribute at runtime (Concept #1, Demo C: declarations keep their generics
 * even though instances do not). Nothing here is transcribed by hand, so nothing here can
 * be a paraphrase, a typo, or a half-remembered version of the real thing. Run it and the
 * JDK tells you itself.
 *
 * The exercise: write each signature from memory FIRST, on paper, then run this and diff.
 * The wildcards are the part to check. Everyone gets `Collections.copy` almost right.
 *
 * -------------------------------------------------------------------------------------
 * READING THEM WITH THE DERIVATION, NOT THE MNEMONIC
 * -------------------------------------------------------------------------------------
 *   Collections.copy(List<? super T> dest, List<? extends T> src)
 *       dest is written INTO by copy  -> consumer -> ? super T
 *       src  is read OUT of by copy   -> producer -> ? extends T
 *       The parameter ORDER is (dest, src), which is the reverse of the reading order and
 *       is precisely why this one gets misremembered. Do not memorise the order; look at
 *       which one the method assigns to.
 *
 *   Collections.max(Collection<? extends T>), with <T extends Comparable<? super T>>
 *       the collection produces elements   -> ? extends T
 *       Comparable CONSUMES a T in compareTo -> ? super T
 *       Two wildcards, two independent applications of the same question. Concept #5
 *       shows what breaks if the second one is written Comparable<T> instead.
 *
 *   List.sort(Comparator<? super E>)
 *       the comparator CONSUMES elements -> ? super E. A Comparator<Object> can compare
 *       anything, so it must be usable to sort a List<String>.
 *
 *   Stream.map(Function<? super T, ? extends R>)
 *       ONE parameter, BOTH rules, applied to its two type arguments separately: the
 *       function consumes a T and produces an R. If you can read this signature and say
 *       why each side is what it is, you have the concept.
 *
 *   Consumer.andThen(Consumer<? super T>) / Predicate.and(Predicate<? super T>)
 *       the argument consumes the same values this one does -> ? super T.
 *
 * Note the asymmetry worth remembering: `Supplier<T>` has NO wildcard in `T get()`,
 * because a Supplier is a pure producer and the type parameter is already in the producing
 * position. Wildcards go on PARAMETERS, to widen what callers may pass. A return type
 * almost never wants one, because it only makes the result harder for the caller to use.
 * =====================================================================================
 */
public final class Generics04JdkSignatures {

    private static String sig(Class<?> owner, String name, Class<?>... params) {
        try {
            Method m = owner.getMethod(name, params);
            return m.toGenericString()
                    .replace("java.util.function.", "")
                    .replace("java.util.stream.", "")
                    .replace("java.util.", "")
                    .replace("java.lang.", "");
        } catch (NoSuchMethodException e) {
            return "NOT FOUND: " + owner.getName() + "." + name;
        }
    }

    // =================================================================================
    // The signatures, read from the class files
    // =================================================================================
    private void printRealSignatures() {
        System.out.println("  Read from the JDK class files at runtime:");
        System.out.println("    " + sig(Collections.class, "copy", List.class, List.class));
        System.out.println("    " + sig(Collections.class, "max", Collection.class));
        System.out.println("    " + sig(Collections.class, "fill", List.class, Object.class));
        System.out.println("    " + sig(List.class, "sort", Comparator.class));
        System.out.println("    " + sig(List.class, "addAll", Collection.class));
        System.out.println("    " + sig(Stream.class, "map", Function.class));
        System.out.println("    " + sig(Stream.class, "forEach", Consumer.class));
        System.out.println("    " + sig(Consumer.class, "andThen", Consumer.class));
        System.out.println("    " + sig(Function.class, "compose", Function.class));
        System.out.println("    " + sig(java.util.function.Supplier.class, "get"));
    }

    // =================================================================================
    // Demo A - Collections.copy, used. dest is the consumer, src is the producer.
    // =================================================================================
    private String demoCopy() {
        List<Integer> src = Arrays.asList(1, 2, 3);          // produces Integers
        List<Number> dest = new ArrayList<>(Arrays.asList(0, 0, 0)); // consumes Integers

        // T infers to Integer. dest is List<Number>, which IS a List<? super Integer>.
        // src is List<Integer>, which IS a List<? extends Integer>. Neither would be
        // accepted if both parameters were plain List<T> (invariance, Concept #2).
        Collections.copy(dest, src);

        return "copy(List<Number> dest, List<Integer> src) -> " + dest
                + " : dest widened by ? super, src widened by ? extends";
    }

    // =================================================================================
    // Demo B - List.sort with a Comparator over a SUPERTYPE of the element
    // =================================================================================
    private String demoSortWithSuperComparator() {
        List<String> words = new ArrayList<>(Arrays.asList("pear", "fig", "apple"));

        // A Comparator<Object> can compare anything, including Strings. `? super E` is
        // what allows it here; a plain Comparator<E> parameter would reject it.
        Comparator<Object> byText = Comparator.comparing(Object::toString);
        words.sort(byText);

        return "List<String>.sort(Comparator<Object>) -> " + words
                + " : accepted because the parameter is Comparator<? super E>";
    }

    // =================================================================================
    // Demo C - Stream.map: ? super on the input, ? extends on the output
    // =================================================================================
    private String demoStreamMap() {
        List<Integer> ints = Arrays.asList(1, 2, 3);

        // Declared over Number (wider than Integer) and returning String. Accepted
        // because the parameter is Function<? super Integer, ? extends String>.
        Function<Number, String> fn = n -> "#" + n.intValue();
        List<String> out = ints.stream().map(fn).toList();

        return "Stream<Integer>.map(Function<Number,String>) -> " + out
                + " : ? super took the wider input, ? extends took the narrower output";
    }

    // =================================================================================
    // Demo D - Consumer.andThen, and why the argument is ? super T
    // =================================================================================
    private String demoConsumerAndThen() {
        StringBuilder sb = new StringBuilder();
        Consumer<String> first = s -> sb.append(s.toUpperCase());
        Consumer<Object> second = o -> sb.append('(').append(o).append(')');

        // second is a Consumer<Object>: it can handle anything a Consumer<String> can.
        // `andThen(Consumer<? super T>)` is what lets it be composed in here.
        Consumer<String> both = first.andThen(second);
        both.accept("ok");

        return "Consumer<String>.andThen(Consumer<Object>) -> \"" + sb
                + "\" : ? super T accepts any consumer that can handle a String";
    }

    // =================================================================================
    // Demo E - the counter-example: Supplier<T> has no wildcard, on purpose
    // =================================================================================
    private String demoSupplierHasNoWildcard() {
        java.util.function.Supplier<String> supplier = () -> "produced";
        String value = supplier.get();

        // `T get()` - no wildcard. A wildcard on a RETURN type would only make the result
        // less usable to the caller (they would receive a capture, not a String).
        // Wildcards exist to widen what callers may PASS IN, not what they get back.
        return "Supplier<String>.get() returns a plain T (\"" + value
                + "\") - wildcards belong on parameters, not return types";
    }

    private void run() {
        System.out.println("Track 2, Concept #4 - the canonical JDK signatures");
        System.out.println("==================================================");
        printRealSignatures();
        System.out.println();
        line("A  Collections.copy ", demoCopy());
        line("B  sort ? super E   ", demoSortWithSuperComparator());
        line("C  Stream.map       ", demoStreamMap());
        line("D  Consumer.andThen ", demoConsumerAndThen());
        line("E  Supplier: none   ", demoSupplierHasNoWildcard());
        System.out.println();
        System.out.println("Takeaway: none of these were designed to be memorised. Each one is the same");
        System.out.println("question answered per parameter - written into, or read out of? Check your");
        System.out.println("from-memory version against the block above; if a wildcard is reversed, work out");
        System.out.println("which direction the data flows in that parameter rather than re-memorising it.");
    }

    private static void line(String label, String verdict) {
        System.out.println("  [" + label + "] " + verdict);
    }

    public static void main(String[] args) {
        new Generics04JdkSignatures().run();
    }
}
