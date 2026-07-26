// ABOUTME: Covers the equality the JDK defines for you (collections, arrays, records) and the one
// ABOUTME: case records do not fix: an array component still compares by identity.
package org.example.equality;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/*
 * =====================================================================================
 * Concept #5 - "What the JDK already defines, and the one gap records leave"
 * =====================================================================================
 *
 * Most of the equality you need is already written, and written correctly. Knowing which
 * parts are done for you is most of the practical value of this track.
 *
 * COLLECTIONS. AbstractList and AbstractSet define equals for you, and they define it
 * ACROSS IMPLEMENTATIONS but never across types:
 *   - new ArrayList<>(List.of(1,2)).equals(new LinkedList<>(List.of(1,2)))  -> true
 *     Lists are equal when they have the same elements IN THE SAME ORDER, whatever class.
 *   - Set equality ignores order entirely: a HashSet equals a LinkedHashSet with the same
 *     members regardless of iteration order.
 *   - List.of(1,2).equals(Set.of(1,2)) -> FALSE. Different contracts, never equal, whatever
 *     they contain. Both are Collections, and Collection itself defines no equals at all.
 *
 * ARRAYS ARE THE EXCEPTION, AND THE ONE THAT BITES. An array is an Object whose equals and
 * hashCode are Object's: pure identity. `a1.equals(a2)` is `a1 == a2`, always. Two arrays
 * with identical contents are never equal and never share a hash code. Use Arrays.equals /
 * Arrays.hashCode, or Arrays.deepEquals / deepHashCode for nested arrays.
 *
 * RECORDS. Since Java 16 a record generates equals, hashCode and toString from its
 * components, and is IMPLICITLY FINAL - which means Concept #3's problem cannot arise at
 * all, because nothing can extend it to add a value component. For a value type, a record
 * is the correct default and hand-writing the pair is the exception.
 *
 * -------------------------------------------------------------------------------------
 * THE GAP: A RECORD WITH AN ARRAY COMPONENT IS BROKEN BY DEFAULT
 * -------------------------------------------------------------------------------------
 * The generated equals compares each component the way that component compares. For an
 * array component that means IDENTITY, so two records holding equal arrays are unequal
 * (Demo D). The record did not fail; it faithfully inherited the array's own semantics.
 *
 * This is worth knowing precisely because records are otherwise so reliable that people
 * stop checking. The fixes, in order of preference:
 *   1. Use List<T> instead of T[] as the component. Immutable, correct equality, no work.
 *   2. If the array is unavoidable, override equals/hashCode explicitly with Arrays.equals
 *      and Arrays.hashCode - and copy defensively in the constructor and accessor, since a
 *      record component holding an array is not immutable either.
 * The second point matters beyond equality: an array component makes the record mutable
 * through the back door, which is Concept #2's hazard wearing a record's clothes.
 * =====================================================================================
 */
public final class Equality05JdkAndRecords {

    record Point(int x, int y) { }

    /** A record whose generated equals cannot work, because a component is an array. */
    record Reading(String sensor, int[] samples) { }

    /** The same data, fixed by choosing List over an array. Nothing else changes. */
    record ReadingFixed(String sensor, List<Integer> samples) { }

    /** If the array is unavoidable: explicit equality plus defensive copies. */
    record ReadingWithArray(String sensor, int[] samples) {
        ReadingWithArray {
            samples = samples.clone();                 // compact constructor: copy in
        }
        @Override public int[] samples() { return samples.clone(); }   // copy out
        @Override public boolean equals(Object o) {
            return o instanceof ReadingWithArray r
                    && r.sensor.equals(sensor)
                    && Arrays.equals(r.samples, samples);
        }
        @Override public int hashCode() { return Objects.hash(sensor, Arrays.hashCode(samples)); }
    }

    // =================================================================================
    // Demo A - lists: equal across implementations, never across collection types
    // =================================================================================
    private String demoListEquality() {
        List<Integer> arrayList = new ArrayList<>(List.of(1, 2, 3));
        List<Integer> linkedList = new LinkedList<>(List.of(1, 2, 3));
        List<Integer> reordered = new ArrayList<>(List.of(3, 2, 1));

        boolean acrossImpls = arrayList.equals(linkedList);
        boolean orderMatters = arrayList.equals(reordered);
        boolean acrossTypes = List.of(1, 2, 3).equals(Set.of(1, 2, 3));

        return "ArrayList=LinkedList " + acrossImpls + ", same elements reordered " + orderMatters
                + ", List=Set " + acrossTypes + " : implementation is irrelevant, order and type are not";
    }

    // =================================================================================
    // Demo B - sets ignore order; maps compare their entry sets
    // =================================================================================
    private String demoSetAndMapEquality() {
        Set<String> hash = new HashSet<>(List.of("a", "b", "c"));
        Set<String> linked = new LinkedHashSet<>(List.of("c", "b", "a"));
        boolean setsEqual = hash.equals(linked);

        Map<String, Integer> m1 = new HashMap<>(Map.of("x", 1, "y", 2));
        Map<String, Integer> m2 = new HashMap<>(Map.of("y", 2, "x", 1));
        boolean mapsEqual = m1.equals(m2);

        Map<String, Integer> m3 = new HashMap<>(Map.of("x", 1, "y", 99));
        boolean valuesMatter = m1.equals(m3);

        return "HashSet=LinkedHashSet (different order) " + setsEqual + ", maps with same pairs "
                + mapsEqual + ", one value changed " + valuesMatter
                + " : sets are unordered by contract, maps compare keys AND values";
    }

    // =================================================================================
    // Demo C - arrays compare by identity, and that is not a bug
    // =================================================================================
    private String demoArrays() {
        int[] a = {1, 2, 3};
        int[] b = {1, 2, 3};

        boolean objectEquals = a.equals(b);
        boolean sameHash = a.hashCode() == b.hashCode();
        boolean arraysEquals = Arrays.equals(a, b);

        int[][] nested1 = {{1}, {2}};
        int[][] nested2 = {{1}, {2}};
        boolean shallow = Arrays.equals(nested1, nested2);   // compares inner arrays by identity
        boolean deep = Arrays.deepEquals(nested1, nested2);

        // The practical consequence: an array is a hopeless map key.
        Set<int[]> set = new HashSet<>(List.of(a, b));

        return "a.equals(b)=" + objectEquals + ", sameHash=" + sameHash + ", Arrays.equals=" + arraysEquals
                + "; nested shallow=" + shallow + " deep=" + deep
                + "; HashSet of two identical arrays holds " + set.size();
    }

    // =================================================================================
    // Demo D - records: correct for free, and the array component that is not
    // =================================================================================
    private String demoRecords() {
        boolean plainRecordWorks = new Point(1, 2).equals(new Point(1, 2))
                && new Point(1, 2).hashCode() == new Point(1, 2).hashCode();

        // Records are implicitly final, so Concept #3's subclass problem cannot occur.
        boolean isFinal = java.lang.reflect.Modifier.isFinal(Point.class.getModifiers());

        Reading r1 = new Reading("temp", new int[]{1, 2});
        Reading r2 = new Reading("temp", new int[]{1, 2});
        boolean arrayRecordBroken = r1.equals(r2);

        ReadingFixed f1 = new ReadingFixed("temp", List.of(1, 2));
        ReadingFixed f2 = new ReadingFixed("temp", List.of(1, 2));
        boolean listRecordWorks = f1.equals(f2);

        ReadingWithArray w1 = new ReadingWithArray("temp", new int[]{1, 2});
        ReadingWithArray w2 = new ReadingWithArray("temp", new int[]{1, 2});
        boolean explicitWorks = w1.equals(w2) && w1.hashCode() == w2.hashCode();

        return "record Point works=" + plainRecordWorks + " and is implicitly final=" + isFinal
                + "; record with int[] component: equal=" + arrayRecordBroken
                + " (BROKEN); with List instead=" + listRecordWorks
                + "; with explicit Arrays.equals=" + explicitWorks;
    }

    // =================================================================================
    // Demo E - the array component is also a mutability hole, not just an equality one
    // =================================================================================
    private String demoArrayComponentMutability() {
        int[] samples = {1, 2, 3};

        Reading leaky = new Reading("temp", samples);
        samples[0] = 999;                          // the caller still holds the array
        int leakedFirst = leaky.samples()[0];      // the record changed underneath

        int[] safeInput = {1, 2, 3};
        ReadingWithArray guarded = new ReadingWithArray("temp", safeInput);
        safeInput[0] = 999;                        // mutating the caller's copy
        int guardedFirst = guarded.samples()[0];   // unaffected
        guarded.samples()[1] = 888;                // mutating the returned copy
        int stillGuarded = guarded.samples()[1];

        return "plain record: caller mutated its array -> record now reads " + leakedFirst
                + "; defensive-copy record: still " + guardedFirst
                + " after input mutation and " + stillGuarded + " after output mutation"
                + " : a record is only as immutable as its components";
    }

    private void run() {
        System.out.println("Track 3, Concept #5 - what the JDK defines, and the record gap");
        System.out.println("=============================================================");
        line("A  list equality   ", demoListEquality());
        line("B  set / map       ", demoSetAndMapEquality());
        line("C  arrays: identity", demoArrays());
        line("D  records         ", demoRecords());
        line("E  array component ", demoArrayComponentMutability());
        System.out.println();
        System.out.println("Takeaway: for value types, reach for a record. It generates a correct pair, it is");
        System.out.println("implicitly final so Concept #3 cannot happen, and it removes the Concept #2");
        System.out.println("hazard by being immutable. The one exception is an array component, which stays");
        System.out.println("identity-compared and mutable: prefer List, and if you cannot, write equals with");
        System.out.println("Arrays.equals and copy in the constructor and the accessor.");
    }

    private static void line(String label, String verdict) {
        System.out.println("  [" + label + "] " + verdict);
    }

    public static void main(String[] args) {
        new Equality05JdkAndRecords().run();
    }
}
