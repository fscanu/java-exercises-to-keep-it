// ABOUTME: Shows that sorted collections decide identity with compareTo and never call equals, so a
// ABOUTME: comparator inconsistent with equals silently changes what counts as a duplicate.
package org.example.equality;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/*
 * =====================================================================================
 * Concept #4 - "Sorted collections do not call equals"
 * =====================================================================================
 *
 * A HashSet asks hashCode, then equals (Concept #1). A TreeSet asks NEITHER. It asks
 * compareTo - or the Comparator you handed it - and treats "compares equal to zero" as
 * "the same element". equals is never consulted, not once, for any operation.
 *
 * So the definition of "duplicate" is not a property of your type. It is a property of the
 * collection you put it in. The same two objects can be one element or two depending
 * entirely on which Set implementation holds them, and no exception marks the difference.
 *
 * THE CANONICAL CASE. BigDecimal deliberately makes equals stricter than compareTo:
 *
 *      new BigDecimal("1.0").equals(new BigDecimal("1.00"))   -> false   (scale differs)
 *      new BigDecimal("1.0").compareTo(new BigDecimal("1.00")) ->  0     (value is equal)
 *
 * This is documented and intentional: equals distinguishes 1.0 from 1.00 because their
 * scale differs and scale is meaningful for money. The consequence is that a HashSet of
 * those two values has size 2 and a TreeSet has size 1 (Demo A). Neither is wrong. They are
 * answering different questions, and you chose which by picking a collection.
 *
 * -------------------------------------------------------------------------------------
 * "CONSISTENT WITH EQUALS" IS A RECOMMENDATION, NOT A RULE
 * -------------------------------------------------------------------------------------
 * Comparable's javadoc says compareTo should be consistent with equals - that
 * `x.compareTo(y) == 0` should agree with `x.equals(y)` - and then says, plainly, that this
 * is "strongly recommended" but not required. Nothing enforces it. No annotation, no
 * runtime check, no warning.
 *
 * What it costs when you ignore it is precise: SortedSet and SortedMap are specified to
 * behave "correctly but fail to obey the general contract of Set/Map", because those
 * contracts are defined in terms of equals while the implementation uses compareTo. So a
 * TreeSet with an inconsistent comparator is not a broken TreeSet. It is a working TreeSet
 * that is no longer a conforming Set - and it will be handed to code that expects a Set.
 *
 * Demo C is the sharpest form: TreeSet.contains(x) returns true for an x that is equal to
 * NOTHING in the set. The set contains no element for which equals(x) holds, and contains()
 * says yes anyway, because it asked a different question.
 * =====================================================================================
 */
public final class Equality04ComparableVsEquals {

    /** equals uses both fields; the natural ordering uses only one. Inconsistent on purpose. */
    static final class Employee implements Comparable<Employee> {
        final String name;
        final int salary;
        Employee(String name, int salary) { this.name = name; this.salary = salary; }
        @Override public boolean equals(Object o) {
            return o instanceof Employee e && e.name.equals(name) && e.salary == salary;
        }
        @Override public int hashCode() { return Objects.hash(name, salary); }
        @Override public int compareTo(Employee o) { return Integer.compare(salary, o.salary); }
        @Override public String toString() { return name + "/" + salary; }
    }

    // =================================================================================
    // Demo A - BigDecimal: same two values, two different set sizes
    // =================================================================================
    private String demoBigDecimal() {
        BigDecimal a = new BigDecimal("1.0");
        BigDecimal b = new BigDecimal("1.00");

        boolean equal = a.equals(b);
        int compared = a.compareTo(b);

        Set<BigDecimal> hash = new HashSet<>(List.of(a, b));
        Set<BigDecimal> tree = new TreeSet<>(List.of(a, b));

        return "equals=" + equal + ", compareTo=" + compared
                + " -> HashSet size=" + hash.size() + " " + hash
                + ", TreeSet size=" + tree.size() + " " + tree
                + " : the same pair, counted differently";
    }

    // =================================================================================
    // Demo B - a comparator collapses distinct objects, and the extra ones are DROPPED
    // =================================================================================
    private String demoComparatorCollapses() {
        Employee ann = new Employee("ann", 100);
        Employee bob = new Employee("bob", 100);   // different person, identical salary
        Employee cat = new Employee("cat", 200);

        boolean annEqualsBob = ann.equals(bob);

        Set<Employee> hash = new HashSet<>(List.of(ann, bob, cat));
        Set<Employee> tree = new TreeSet<>(List.of(ann, bob, cat));  // natural order: salary

        // Same input, and the TreeSet silently discarded a person.
        List<String> keptByTree = new ArrayList<>();
        for (Employee e : tree) keptByTree.add(e.name);

        return "ann.equals(bob)=" + annEqualsBob + " (different people) but compareTo=0 -> HashSet "
                + hash.size() + " employees, TreeSet " + tree.size() + " keeping " + keptByTree
                + " : one person dropped, no error";
    }

    // =================================================================================
    // Demo C - contains() says yes about an element the set does not contain
    // =================================================================================
    private String demoContainsLies() {
        Employee stored = new Employee("ann", 100);
        Set<Employee> tree = new TreeSet<>(List.of(stored));

        Employee neverAdded = new Employee("zoe", 100);   // same salary, different person

        boolean treeSaysYes = tree.contains(neverAdded);
        boolean anyActuallyEqual = false;
        for (Employee e : tree) if (e.equals(neverAdded)) anyActuallyEqual = true;

        Set<Employee> hash = new HashSet<>(List.of(stored));
        boolean hashSaysYes = hash.contains(neverAdded);

        return "TreeSet.contains(zoe/100)=" + treeSaysYes + " although no element equals it ("
                + anyActuallyEqual + "); HashSet.contains=" + hashSaysYes
                + " : the tree compared, it never asked equals";
    }

    // =================================================================================
    // Demo D - TreeMap looks keys up the same way, so a "different" key overwrites
    // =================================================================================
    private String demoTreeMapKeys() {
        TreeMap<Employee, String> map = new TreeMap<>();
        map.put(new Employee("ann", 100), "ann's record");
        map.put(new Employee("bob", 100), "bob's record");   // compares 0 -> REPLACES the value

        // Note what survived: the original KEY is kept, the new VALUE wins. That is
        // Map.put's specified behaviour, and here it produces a genuinely mixed entry.
        Employee survivingKey = map.firstKey();
        String survivingValue = map.get(survivingKey);

        return "put(ann/100) then put(bob/100) -> map size " + map.size() + ", key=" + survivingKey
                + " value=\"" + survivingValue + "\" : bob's value filed under ann's key";
    }

    // =================================================================================
    // Demo E - a case-insensitive comparator, which is inconsistent with String.equals
    // =================================================================================
    private String demoCaseInsensitive() {
        Set<String> hash = new HashSet<>(List.of("apple", "APPLE"));
        Set<String> tree = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        tree.addAll(List.of("apple", "APPLE"));

        // And a comparator that IS consistent with equals, for contrast.
        Set<String> consistent = new TreeSet<>(Comparator.naturalOrder());
        consistent.addAll(List.of("apple", "APPLE"));

        return "HashSet " + hash.size() + " " + hash + ", CASE_INSENSITIVE TreeSet " + tree.size()
                + " " + tree + ", natural-order TreeSet " + consistent.size() + " " + consistent
                + " : the comparator decided what a duplicate is";
    }

    private void run() {
        System.out.println("Track 3, Concept #4 - sorted collections do not call equals");
        System.out.println("==========================================================");
        line("A  BigDecimal      ", demoBigDecimal());
        line("B  collapsed rows  ", demoComparatorCollapses());
        line("C  contains() lies ", demoContainsLies());
        line("D  TreeMap keys    ", demoTreeMapKeys());
        line("E  case-insensitive", demoCaseInsensitive());
        System.out.println();
        System.out.println("Takeaway: TreeSet and TreeMap define identity as compareTo()==0 and never call");
        System.out.println("equals. So 'duplicate' is decided by the collection, not by your type, and an");
        System.out.println("inconsistent comparator gives you a working TreeSet that is no longer a");
        System.out.println("conforming Set. The failure mode is not an exception - it is a row that quietly");
        System.out.println("is not there. Before using a comparator, ask what it makes indistinguishable.");
    }

    private static void line(String label, String verdict) {
        System.out.println("  [" + label + "] " + verdict);
    }

    public static void main(String[] args) {
        new Equality04ComparableVsEquals().run();
    }
}
