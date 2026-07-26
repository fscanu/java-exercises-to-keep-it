// ABOUTME: Shows what a HashMap actually does to find a key (hash, then bucket, then equals) and
// ABOUTME: what breaks when you override only one half of the equals/hashCode pair.
package org.example.equality;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/*
 * =====================================================================================
 * Concept #1 - "The contract, and the lookup it exists to serve"
 * =====================================================================================
 *
 * A HashMap does not search. Searching a million entries for every get() would defeat the
 * point. It computes, in this order:
 *
 *      1. h = key.hashCode(), then spreads the bits          -> an int
 *      2. bucket = h & (table.length - 1)                    -> ONE bucket, chosen arithmetically
 *      3. walk that bucket only, comparing with == then equals()
 *
 * Step 2 is the whole reason the contract exists. The map never looks in any other bucket.
 * So if two objects are equal() but return different hashCodes, they are sent to different
 * buckets and NOTHING will ever compare them - not because equals said no, but because
 * equals was never asked. That is the failure in Demo A: not a wrong answer, an unasked
 * question.
 *
 * -------------------------------------------------------------------------------------
 * THE CONTRACT, IN THE ORDER IT MATTERS
 * -------------------------------------------------------------------------------------
 * hashCode:
 *   (h1) equal objects MUST return equal hash codes.        <- break this and lookups vanish
 *   (h2) hashCode must be consistent while the object is in use.  <- Concept #2
 *   (h3) unequal objects MAY return the same hash code.     <- collisions are LEGAL, see Demo D
 *
 * equals:
 *   reflexive     x.equals(x)
 *   symmetric     x.equals(y) == y.equals(x)                <- Concept #3 breaks this
 *   transitive    x=y and y=z implies x=z                   <- Concept #3 breaks this too
 *   consistent    repeated calls agree, absent mutation
 *   null-hostile  x.equals(null) is false, never an NPE
 *
 * Note the asymmetry people invert: (h1) is a hard requirement, (h3) is an explicit
 * permission. hashCode is NOT required to be unique and never was. A hashCode that returns
 * a constant is perfectly CORRECT - it is only slow (Demo D). A hashCode that is unique but
 * inconsistent with equals is INCORRECT, and silently so.
 *
 * -------------------------------------------------------------------------------------
 * WHY THIS TRACK IS DIFFERENT FROM TRACK 2
 * -------------------------------------------------------------------------------------
 * Track 2's mistakes cannot reach production: reversed wildcards do not compile. Every
 * mistake in THIS track compiles perfectly, runs without an exception, and returns a wrong
 * answer quietly. There is no compiler here and no stack trace. The only defence is knowing
 * what the collection is actually doing, which is why Demo A starts with the lookup and not
 * with the rule.
 * =====================================================================================
 */
public final class Equality01Contract {

    /** Overrides equals only. Two equal instances, two different hash codes. */
    // javac's -Xlint:overrides flags exactly this mistake, and it is RIGHT: in real code the
    // warning is the free fix. Suppressed here only because breaking the pair is the demo.
    @SuppressWarnings("overrides")
    static final class EqualsOnly {
        final String id;
        EqualsOnly(String id) { this.id = id; }
        @Override public boolean equals(Object o) {
            return o instanceof EqualsOnly other && id.equals(other.id);
        }
        // hashCode inherited from Object: identity-based. Contract clause (h1) broken.
        @Override public String toString() { return "EqualsOnly(" + id + ")"; }
    }

    /** Overrides hashCode only. Same bucket, but equals() is still identity. */
    static final class HashOnly {
        final String id;
        HashOnly(String id) { this.id = id; }
        @Override public int hashCode() { return id.hashCode(); }
        // equals inherited from Object: reference identity.
        @Override public String toString() { return "HashOnly(" + id + ")"; }
    }

    /** Both, agreeing on the same field. The only correct combination. */
    static final class Both {
        final String id;
        Both(String id) { this.id = id; }
        @Override public boolean equals(Object o) {
            return o instanceof Both other && id.equals(other.id);
        }
        @Override public int hashCode() { return Objects.hash(id); }
        @Override public String toString() { return "Both(" + id + ")"; }
    }

    /** Correct, and deliberately terrible: every instance lands in the same bucket. */
    static final class ConstantHash {
        final String id;
        ConstantHash(String id) { this.id = id; }
        @Override public boolean equals(Object o) {
            return o instanceof ConstantHash other && id.equals(other.id);
        }
        @Override public int hashCode() { return 1; }   // legal. clause (h3) permits it.
    }

    // =================================================================================
    // Demo A - equals without hashCode: equal objects, different buckets, never compared
    // =================================================================================
    private String demoEqualsOnly() {
        EqualsOnly a = new EqualsOnly("x");
        EqualsOnly b = new EqualsOnly("x");

        boolean areEqual = a.equals(b);
        boolean sameHash = a.hashCode() == b.hashCode();

        Set<EqualsOnly> set = new HashSet<>();
        set.add(a);
        set.add(b);                       // sent to a different bucket, so never compared to a
        boolean found = set.contains(new EqualsOnly("x"));

        return "a.equals(b)=" + areEqual + " but sameHash=" + sameHash
                + " -> HashSet size=" + set.size() + " (expected 1), contains(equal)=" + found
                + " : equals was never even called";
    }

    // =================================================================================
    // Demo B - hashCode without equals: right bucket, wrong question
    // =================================================================================
    private String demoHashOnly() {
        HashOnly a = new HashOnly("x");
        HashOnly b = new HashOnly("x");

        boolean sameHash = a.hashCode() == b.hashCode();
        boolean areEqual = a.equals(b);   // Object.equals: identity. false.

        Set<HashOnly> set = new HashSet<>();
        set.add(a);
        set.add(b);                       // same bucket this time, but equals() says no

        return "sameHash=" + sameHash + " but a.equals(b)=" + areEqual
                + " -> HashSet size=" + set.size() + " (expected 1)"
                + " : right bucket, and equals refused";
    }

    // =================================================================================
    // Demo C - both, agreeing: the lookup works
    // =================================================================================
    private String demoBoth() {
        Set<Both> set = new HashSet<>();
        set.add(new Both("x"));
        set.add(new Both("x"));
        boolean found = set.contains(new Both("x"));

        return "HashSet size=" + set.size() + " (expected 1), contains(a fresh equal object)="
                + found + " : hash routes to the bucket, equals settles it";
    }

    // =================================================================================
    // Demo D - a constant hashCode is CORRECT, and only slow
    // =================================================================================
    private String demoConstantHash() {
        final int n = 20_000;

        Set<ConstantHash> awful = new HashSet<>();
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) awful.add(new ConstantHash("k" + i));
        long awfulMs = (System.nanoTime() - t0) / 1_000_000;

        Set<Both> good = new HashSet<>();
        t0 = System.nanoTime();
        for (int i = 0; i < n; i++) good.add(new Both("k" + i));
        long goodMs = (System.nanoTime() - t0) / 1_000_000;

        boolean bothCorrect = awful.size() == n && good.size() == n;

        return "both stored all " + String.format("%,d", n) + " distinct keys correctly="
                + bothCorrect + ", constant hash " + awfulMs + " ms vs spread hash " + goodMs
                + " ms : collisions cost speed, never correctness";
    }

    // =================================================================================
    // Demo E - the reverse reading: unequal objects sharing a hash is fine
    // =================================================================================
    private String demoCollisionsAreFine() {
        // Two genuinely different Strings with the same hashCode. This is not a bug in
        // String; it is clause (h3) working as designed.
        String s1 = "Aa", s2 = "BB";
        boolean sameHash = s1.hashCode() == s2.hashCode();
        boolean equal = s1.equals(s2);

        Set<String> set = new HashSet<>(List.of(s1, s2));
        List<String> found = new ArrayList<>();
        for (String s : List.of("Aa", "BB")) if (set.contains(s)) found.add(s);

        return "\"Aa\".hashCode()==\"BB\".hashCode() is " + sameHash + ", equals=" + equal
                + " -> set holds " + set.size() + ", both still findable " + found
                + " : the bucket is a filter, not an answer";
    }

    private void run() {
        System.out.println("Track 3, Concept #1 - the contract, and the lookup it serves");
        System.out.println("===========================================================");
        line("A  equals only     ", demoEqualsOnly());
        line("B  hashCode only   ", demoHashOnly());
        line("C  both, agreeing  ", demoBoth());
        line("D  constant hash   ", demoConstantHash());
        line("E  collisions OK   ", demoCollisionsAreFine());
        System.out.println();
        System.out.println("Takeaway: hashCode picks the bucket, equals decides inside it. Break the link");
        System.out.println("between them and equals is never consulted, so the object is not 'wrongly");
        System.out.println("compared' - it is never found at all. Note which direction is mandatory: equal");
        System.out.println("objects MUST share a hash; unequal objects MAY. Collisions are legal and cheap");
        System.out.println("to survive. An inconsistent hash is neither, and nothing will tell you.");
    }

    private static void line(String label, String verdict) {
        System.out.println("  [" + label + "] " + verdict);
    }

    public static void main(String[] args) {
        new Equality01Contract().run();
    }
}
