// ABOUTME: Derives PECS from the capture mechanism rather than reciting the mnemonic, then applies
// ABOUTME: the derivation to five signatures by asking "do I read from this, or write to it?".
package org.example.generics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/*
 * =====================================================================================
 * Concept #3 - "PECS, derived"
 * =====================================================================================
 *
 * The mnemonic is Producer Extends, Consumer Super. Mnemonics fail under pressure and
 * they fail in the same way every time: you remember that there are two of them and you
 * pick the wrong one. So do not memorise this. DERIVE it, from one question:
 *
 *          For this parameter, does data come OUT of it, or go INTO it?
 *
 *          comes OUT (I read from it)  -> it PRODUCES for me -> ? extends T
 *          goes IN  (I write to it)    -> it CONSUMES from me -> ? super T
 *          both                        -> no wildcard. Use plain T.
 *
 * Say it as a sentence about DATA FLOW, never as a fact about the parameter's name. The
 * direction of the data is observable by reading the method body; the mnemonic is not.
 *
 * -------------------------------------------------------------------------------------
 * WHY, MECHANICALLY. This is the part that makes it reconstructible.
 * -------------------------------------------------------------------------------------
 * When the compiler meets a wildcard it performs CAPTURE: it invents a fresh, nameless
 * type variable standing for the one specific type that is actually there. You can see it
 * in javac's own error text, where it is called CAP#1.
 *
 * For `List<? extends Number>` the captured variable is:
 *
 *          CAP#1 extends Number            (javac's exact wording)
 *
 *   READ:  get() returns CAP#1. Everything CAP#1 could possibly be is a Number, so
 *          `Number n = list.get(0)` is always sound. READING IS ALLOWED.
 *   WRITE: add(x) requires an argument of type CAP#1 - and nobody, including the
 *          compiler, knows what CAP#1 is. The list might really be a List<Integer>; then
 *          adding a Double corrupts it. It might be a List<Double>; then adding an
 *          Integer corrupts it. There is no value you can prove is a CAP#1, so nothing
 *          may be added. WRITING IS FORBIDDEN. (Except null, which is every type.)
 *
 *          javac, wrong-turns/W1AddToProducer.java, verbatim:
 *              error: no suitable method found for add(int)
 *                  method List.add(CAP#1) is not applicable
 *                    (argument mismatch; int cannot be converted to CAP#1)
 *                where CAP#1 is a fresh type-variable:
 *                  CAP#1 extends Number from capture of ? extends Number
 *
 * For `List<? super Integer>` the captured variable is:
 *
 *          CAP#1 extends Object super: Integer     (javac's exact wording)
 *
 *   WRITE: add(x) requires a CAP#1. Whatever CAP#1 is, it is a SUPERTYPE of Integer, so
 *          an Integer is assignable to it, always. WRITING AN INTEGER IS ALLOWED.
 *   READ:  get() returns CAP#1, and the only thing known about CAP#1 is its upper bound,
 *          which is Object. The list could be List<Number>, List<Serializable>,
 *          List<Object>. So you get an Object and nothing better. TYPED READING IS
 *          FORBIDDEN.
 *
 *          javac, wrong-turns/W2ReadTypedFromConsumer.java, verbatim:
 *              error: incompatible types: CAP#1 cannot be converted to Number
 *                where CAP#1 is a fresh type-variable:
 *                  CAP#1 extends Object super: Number from capture of ? super Number
 *
 * Notice the symmetry, and notice that neither rule was chosen for elegance. In each case
 * exactly one operation is provably sound and the other is provably not, given that no
 * runtime check exists to catch a mistake (Concept #1). The wildcard keeps the sound
 * operation and deletes the other. That is all PECS is.
 *
 * -------------------------------------------------------------------------------------
 * THE FAILURE MODE THIS PREVENTS
 * -------------------------------------------------------------------------------------
 * Getting it backwards does not produce a subtle bug; it produces a method nobody can
 * call, or one whose body will not compile. wrong-turns/W4PecsBackwards.java declares a
 * copy() with the wildcards reversed, and javac names the problem precisely:
 *
 *      error: method set in interface List<E> cannot be applied to given types;
 *          required: int,CAP#1
 *          found:    int,CAP#2
 *          reason: argument mismatch; Object cannot be converted to CAP#1
 *        where CAP#1,CAP#2 are fresh type-variables:
 *          CAP#1 extends T from capture of ? extends T
 *          CAP#2 extends Object super: T from capture of ? super T
 *
 * Read that as: "you are trying to write into the thing you declared readable, using a
 * value out of the thing you declared writable." Both halves are backwards, and the
 * compiler will tell you so every time - which is the good news. This mistake cannot
 * reach production.
 * =====================================================================================
 */
public final class Generics03Pecs {

    // =================================================================================
    // 1. sum - reads Numbers out. Pure PRODUCER -> ? extends
    // =================================================================================
    // "Does data come out of nums, or go in?" It comes out: doubleValue() reads.
    // So: producer, so: extends. Without the wildcard this method would reject
    // List<Integer> outright (wrong-turns/W5NoWildcardTooStrict.java).
    static double sum(List<? extends Number> nums) {
        double total = 0;
        for (Number n : nums) total += n.doubleValue();
        return total;
    }

    // =================================================================================
    // 2. addIntegers - writes Integers in. Pure CONSUMER -> ? super
    // =================================================================================
    // Data goes IN. So: consumer, so: super. The gain: this accepts List<Integer>,
    // List<Number>, AND List<Object>, because an Integer is assignable to all of them.
    static void addIntegers(List<? super Integer> sink, int count) {
        for (int i = 0; i < count; i++) sink.add(i);
    }

    // =================================================================================
    // 3. printAll: List<? extends Object> vs List<Object> - the call-site difference
    // =================================================================================
    // These two look interchangeable and are not. `List<? extends Object>` (spelled
    // `List<?>` in practice, they mean the same thing) accepts a list of ANYTHING.
    // `List<Object>` accepts a List<Object> and nothing else - invariance, Concept #2.
    static String printAll(List<?> anyList) {          // == List<? extends Object>
        StringBuilder sb = new StringBuilder();
        for (Object o : anyList) sb.append(o).append(' ');
        return sb.toString().trim();
    }

    static String printAllStrict(List<Object> onlyObjectLists) {
        StringBuilder sb = new StringBuilder();
        for (Object o : onlyObjectLists) sb.append(o).append(' ');
        return sb.toString().trim();
    }

    // =================================================================================
    // 4. max - the producer is the list; Comparable is a CONSUMER of T
    // =================================================================================
    // Two wildcards, two separate questions, answered independently:
    //   - `list`: elements come OUT   -> producer -> ? extends T
    //   - `Comparable<? super T>`: compareTo CONSUMES a T -> consumer -> ? super T
    // The second one is why Collections.max is declared with Comparable<? super T> and
    // not Comparable<T>: it lets you take the max of a List<Dog> when only Animal
    // implements Comparable. Concept #5 shows that case failing without the ? super.
    static <T extends Comparable<? super T>> T max(List<? extends T> list) {
        if (list.isEmpty()) throw new IllegalArgumentException("empty");
        T best = list.get(0);
        for (T candidate : list) if (candidate.compareTo(best) > 0) best = candidate;
        return best;
    }

    // =================================================================================
    // 5. forEachDo - a Consumer<? super T> parameter
    // =================================================================================
    // `action` CONSUMES the elements this method hands it -> ? super T.
    // Concretely: a Consumer<Object> can handle Strings, so it must be accepted where a
    // Consumer<String> is expected. `src` produces -> ? extends T. Both in one signature.
    static <T> void forEachDo(List<? extends T> src, Consumer<? super T> action) {
        for (T t : src) action.accept(t);
    }

    // =================================================================================
    // 6. BONUS - the shape of Stream.map: consume T, produce R, in one parameter
    // =================================================================================
    // `Function<? super T, ? extends R>`: the function CONSUMES a T (so ? super on the
    // input) and PRODUCES an R (so ? extends on the output). One parameter, both rules,
    // applied to its two type arguments independently. This is the real JDK signature;
    // Concept #4 prints it from the class file to prove it.
    static <T, R> List<R> mapAll(List<? extends T> src, Function<? super T, ? extends R> fn) {
        List<R> out = new ArrayList<>();
        for (T t : src) out.add(fn.apply(t));
        return out;
    }

    // ---------------------------------------------------------------------------------

    private String demoProducer() {
        List<Integer> ints = Arrays.asList(1, 2, 3);
        List<Double> doubles = Arrays.asList(1.5, 2.5);
        return "sum(List<Integer>)=" + sum(ints) + ", sum(List<Double>)=" + sum(doubles)
                + " -> one method, both lists; List<Number> alone would accept neither";
    }

    private String demoConsumer() {
        List<Integer> ints = new ArrayList<>();
        List<Number> nums = new ArrayList<>();
        List<Object> objs = new ArrayList<>();
        addIntegers(ints, 3);
        addIntegers(nums, 3);
        addIntegers(objs, 3);
        return "addIntegers accepted List<Integer>, List<Number> and List<Object> -> "
                + ints + " " + nums + " " + objs;
    }

    private String demoWildcardVsObject() {
        List<String> strings = Arrays.asList("a", "b");
        List<Object> objects = Arrays.asList((Object) "x", 1);

        String viaWildcard = printAll(strings);        // fine
        String viaStrict = printAllStrict(objects);    // fine
        // printAllStrict(strings);  // <-- DOES NOT COMPILE:
        //   error: incompatible types: List<String> cannot be converted to List<Object>

        return "printAll(List<String>)=\"" + viaWildcard + "\" OK; printAllStrict needs exactly "
                + "List<Object> (\"" + viaStrict + "\") and REJECTS List<String>";
    }

    private String demoMax() {
        List<Integer> ints = Arrays.asList(3, 9, 4);
        List<String> strings = Arrays.asList("pear", "apple", "fig");
        return "max(" + ints + ")=" + max(ints) + ", max(" + strings + ")=" + max(strings);
    }

    private String demoConsumerParam() {
        List<String> words = Arrays.asList("alpha", "beta");
        StringBuilder sb = new StringBuilder();
        Consumer<Object> anyObject = o -> sb.append('[').append(o).append(']');
        // A Consumer<Object> is accepted where a Consumer<? super String> is wanted:
        // it can handle anything, so it can certainly handle Strings.
        forEachDo(words, anyObject);
        return "forEachDo(List<String>, Consumer<Object>) -> " + sb;
    }

    private String demoMapAll() {
        List<Integer> ints = Arrays.asList(1, 2, 3);
        Function<Number, String> numberToText = n -> "<" + n.intValue() + ">";
        // T=Integer, R=String. The Function is declared over Number (a supertype of
        // Integer) and returns String: accepted by ? super T / ? extends R.
        List<String> mapped = mapAll(ints, numberToText);
        return "mapAll(List<Integer>, Function<Number,String>) -> " + mapped
                + " : ? super T accepted the wider function";
    }

    private void run() {
        System.out.println("Track 2, Concept #3 - PECS, derived from capture");
        System.out.println("================================================");
        System.out.println("For each parameter: does data come OUT (producer, extends)");
        System.out.println("or go IN (consumer, super)? Never recite - always ask.");
        System.out.println();
        line("1 producer  extends", demoProducer());
        line("2 consumer  super  ", demoConsumer());
        line("3 List<?> vs Object", demoWildcardVsObject());
        line("4 max, both rules  ", demoMax());
        line("5 Consumer<? super>", demoConsumerParam());
        line("6 map: super+extends", demoMapAll());
        System.out.println();
        System.out.println("Takeaway: read the body, follow the data. Out of the parameter means it is");
        System.out.println("producing for you, and only reads are sound, so `? extends`. Into the parameter");
        System.out.println("means it is consuming from you, and only writes are sound, so `? super`. If you");
        System.out.println("do both, no wildcard is possible - and that is not a limitation, it is the");
        System.out.println("compiler telling you the truth: nothing safe could be assumed either way.");
    }

    private static void line(String label, String verdict) {
        System.out.println("  [" + label + "] " + verdict);
    }

    public static void main(String[] args) {
        new Generics03Pecs().run();
    }
}
