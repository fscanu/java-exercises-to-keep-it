// ABOUTME: Demonstrates the mutable key: change a field the hash depends on and the object becomes
// ABOUTME: unfindable and unremovable inside the very collection that still contains it.
package org.example.equality;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/*
 * =====================================================================================
 * Concept #2 - "The mutable key, or: lost in a set that contains it"
 * =====================================================================================
 *
 * Concept #1: the map computes a bucket from hashCode ONCE, when you insert. It does not
 * recompute it afterwards, and it has no way to be told the key changed. Nothing observes
 * your object.
 *
 * So mutate a field that hashCode depends on, while the object sits in a HashSet, and the
 * object is now filed under an address that no longer matches its own hash. Every
 * subsequent lookup computes the NEW hash, goes to the NEW bucket, and finds nothing:
 *
 *      contains(theVeryObject)  ->  false
 *      remove(theVeryObject)    ->  false, and it stays
 *      set.size()               ->  still 1
 *      for (x : set)            ->  yields it, quite happily
 *
 * All four at once. The object is simultaneously present and unreachable: iteration walks
 * every bucket so it finds it, while lookup goes to one bucket so it does not. You cannot
 * get it out through the API that put it in. This is a genuine leak - the entry is pinned
 * for the lifetime of the collection.
 *
 * No exception is thrown at any point. Contrast Track 2, where the equivalent mistake was a
 * compile error: here the language offers nothing. The JDK cannot detect it, because
 * detecting it would mean re-hashing every key on every access, which is the cost the hash
 * table exists to avoid.
 *
 * -------------------------------------------------------------------------------------
 * WHAT TO DO ABOUT IT, IN ORDER OF PREFERENCE
 * -------------------------------------------------------------------------------------
 *   1. MAKE KEYS IMMUTABLE. A record with final components, a String, an Integer, an enum.
 *      This is not a discipline you have to remember; it is a property the type has. By far
 *      the best answer and the reason records exist (Concept #5).
 *   2. If the key must be mutable, base equals/hashCode on the IMMUTABLE SUBSET of its
 *      fields - typically an id assigned at construction. Demo C does this: the object
 *      still mutates, but never in a way the map can see.
 *   3. If neither is possible, remove before mutating and re-insert after. Easy to state,
 *      easy to forget, and the failure is silent, so treat this as a last resort.
 *
 * The same hazard applies to a TreeSet through compareTo instead of hashCode (Demo D), and
 * to any key held in a HashMap. It is a property of hash- and order-based lookup, not of
 * HashSet specifically.
 * =====================================================================================
 */
public final class Equality02MutableKey {

    /** The hazard: equals/hashCode read a field anyone can change. */
    static final class MutablePoint {
        int x, y;
        MutablePoint(int x, int y) { this.x = x; this.y = y; }
        @Override public boolean equals(Object o) {
            return o instanceof MutablePoint p && p.x == x && p.y == y;
        }
        @Override public int hashCode() { return Objects.hash(x, y); }
        @Override public String toString() { return "(" + x + "," + y + ")"; }
    }

    /** The fix from note 2: mutable state, but identity anchored to an immutable id. */
    static final class StableId {
        final int id;          // final, assigned once, the only thing equality looks at
        String mutableLabel;   // free to change; invisible to the map
        StableId(int id, String label) { this.id = id; this.mutableLabel = label; }
        @Override public boolean equals(Object o) {
            return o instanceof StableId s && s.id == id;
        }
        @Override public int hashCode() { return Integer.hashCode(id); }
        @Override public String toString() { return "#" + id + "(" + mutableLabel + ")"; }
    }

    /** Ordered collections have the same problem via compareTo. */
    static final class MutableRank implements Comparable<MutableRank> {
        int rank;
        final String name;
        MutableRank(String name, int rank) { this.name = name; this.rank = rank; }
        @Override public int compareTo(MutableRank o) { return Integer.compare(rank, o.rank); }
        @Override public String toString() { return name + ":" + rank; }
    }

    // =================================================================================
    // Demo A - present, and unreachable, and unremovable
    // =================================================================================
    private String demoLostInSet() {
        MutablePoint p = new MutablePoint(1, 1);
        Set<MutablePoint> set = new HashSet<>();
        set.add(p);

        boolean foundBefore = set.contains(p);
        p.x = 99;                                   // the one line that does the damage
        boolean foundAfter = set.contains(p);
        boolean removed = set.remove(p);

        List<MutablePoint> byIteration = new ArrayList<>();
        for (MutablePoint each : set) byIteration.add(each);

        return "before: contains=" + foundBefore + " | after mutating x: contains=" + foundAfter
                + ", remove=" + removed + ", size=" + set.size()
                + ", iteration still yields " + byIteration + " -> present but unreachable";
    }

    // =================================================================================
    // Demo B - the same thing in a HashMap, where it strands a value
    // =================================================================================
    private String demoLostInMap() {
        MutablePoint key = new MutablePoint(2, 2);
        Map<MutablePoint, String> map = new HashMap<>();
        map.put(key, "the payload");

        key.y = 77;
        String direct = map.get(key);               // null: wrong bucket
        String viaEntry = map.entrySet().iterator().next().getValue(); // still right there

        // And now the leak: putting an "equal" key does not replace the old entry, because
        // the old one is filed elsewhere. The map grows instead.
        map.put(new MutablePoint(2, 2), "a second payload");

        return "get(theSameKeyObject)=" + direct + " while the entry still holds \"" + viaEntry
                + "\"; re-putting the original coordinates grew the map to " + map.size()
                + " entries -> the first value can never be retrieved or overwritten";
    }

    // =================================================================================
    // Demo C - the fix: mutate all you like, as long as identity does not move
    // =================================================================================
    private String demoStableIdentity() {
        StableId s = new StableId(1, "before");
        Set<StableId> set = new HashSet<>();
        set.add(s);

        s.mutableLabel = "after";                   // mutation that equality cannot see

        boolean found = set.contains(s);
        boolean removed = set.remove(s);

        return "mutated the label to \"" + s.mutableLabel + "\" -> contains=" + found
                + ", remove=" + removed + ", size now " + set.size()
                + " : equality reads only the final id, so the bucket never moves";
    }

    // =================================================================================
    // Demo D - TreeSet has the identical hazard, through compareTo
    // =================================================================================
    private String demoOrderedCollectionToo() {
        MutableRank a = new MutableRank("a", 1);
        MutableRank b = new MutableRank("b", 2);
        MutableRank c = new MutableRank("c", 3);
        TreeSet<MutableRank> tree = new TreeSet<>(List.of(a, b, c));

        boolean foundBefore = tree.contains(c);
        c.rank = -5;                                // now sorts before everything, but sits last

        boolean foundAfter = tree.contains(c);
        boolean removed = tree.remove(c);

        return "TreeSet before: contains(c)=" + foundBefore + " | after changing its rank: contains="
                + foundAfter + ", remove=" + removed + ", order is now " + tree
                + " -> the tree's shape encodes the OLD comparison; the search takes the new one";
    }

    private void run() {
        System.out.println("Track 3, Concept #2 - the mutable key");
        System.out.println("=====================================");
        line("A  lost in a HashSet ", demoLostInSet());
        line("B  stranded in a Map ", demoLostInMap());
        line("C  stable identity   ", demoStableIdentity());
        line("D  TreeSet, same bug ", demoOrderedCollectionToo());
        System.out.println();
        System.out.println("Takeaway: the bucket is computed once, at insertion, and never revisited. Mutate");
        System.out.println("what the hash reads and the object is filed under an address that no longer");
        System.out.println("matches it: findable by iteration, invisible to lookup, impossible to remove.");
        System.out.println("Nothing throws. The fix is not vigilance, it is immutability - make the key a");
        System.out.println("record, or anchor equality to a final id and let the rest of the object move.");
    }

    private static void line(String label, String verdict) {
        System.out.println("  [" + label + "] " + verdict);
    }

    public static void main(String[] args) {
        new Equality02MutableKey().run();
    }
}
