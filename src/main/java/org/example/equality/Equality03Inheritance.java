// ABOUTME: Shows why adding a field to equals in a subclass cannot preserve both symmetry and
// ABOUTME: substitutability, and that composition rather than a cleverer equals is the way out.
package org.example.equality;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/*
 * =====================================================================================
 * Concept #3 - "Inheritance breaks equals, and there is no clever fix"
 * =====================================================================================
 *
 * There is no way to extend an instantiable class, ADD a value component to it, and keep
 * the equals contract. Not "it is hard" - it is impossible, and the impossibility is worth
 * seeing rather than believing, because every attempted workaround fails in a different
 * clause of the contract.
 *
 * The setup, which is Effective Java's and remains the clearest:
 *
 *      class Point      { int x, y; }
 *      class ColorPoint extends Point { Color color; }
 *
 * ATTEMPT 1 - ColorPoint.equals uses instanceof and compares the colour.
 *      point.equals(colorPoint)  -> true   (Point only looks at x, y)
 *      colorPoint.equals(point)  -> false  (the colour does not match)
 *   SYMMETRY BROKEN. And it is not academic: a collection's answer now depends on which
 *   object you passed as the argument, because ArrayList.contains(o) calls o.equals(element)
 *   rather than the other way round. Demo A shows the same list giving both answers.
 *
 * ATTEMPT 2 - "fix" it by comparing blind when the other side is a plain Point.
 *      redPoint.equals(plainPoint)  -> true
 *      plainPoint.equals(bluePoint) -> true
 *      redPoint.equals(bluePoint)   -> false
 *   Symmetry restored, TRANSITIVITY BROKEN. You have moved the violation, not removed it.
 *
 * ATTEMPT 3 - use getClass() instead of instanceof, requiring exact class identity.
 *   Symmetric and transitive at last, and it costs you the Liskov Substitution Principle: a
 *   subclass that adds NO state at all - a marker type, a debugging subclass, a Hibernate or
 *   Mockito proxy - is now unequal to the plain instance it is otherwise identical to.
 *   Demo C shows a subclass that adds nothing and is nonetheless rejected. Frameworks that
 *   generate proxies at runtime break here routinely.
 *
 * -------------------------------------------------------------------------------------
 * WHAT ACTUALLY WORKS
 * -------------------------------------------------------------------------------------
 *   - COMPOSITION. Do not extend Point; HOLD one, and expose it. ColorPoint is then its own
 *     type with its own equals, and no cross-type comparison is even expressible. Demo D.
 *   - Make the class FINAL, so the question cannot arise. A record is implicitly final,
 *     which is one of several reasons Concept #5 recommends them.
 *   - Add state only via a sibling type, never a subtype.
 *
 * You may of course extend a class WITHOUT adding a value component, and equality stays
 * intact - abstract base classes with concrete subclasses that add only behaviour are fine.
 * The rule is about adding STATE THAT EQUALITY READS, not about inheritance as such.
 * =====================================================================================
 */
public final class Equality03Inheritance {

    // ---- Attempt 1: instanceof, asymmetric --------------------------------------------
    static class Point {
        final int x, y;
        Point(int x, int y) { this.x = x; this.y = y; }
        @Override public boolean equals(Object o) {
            return o instanceof Point p && p.x == x && p.y == y;
        }
        @Override public int hashCode() { return Objects.hash(x, y); }
        @Override public String toString() { return "Point(" + x + "," + y + ")"; }
    }

    static class ColorPoint extends Point {
        final String color;
        ColorPoint(int x, int y, String color) { super(x, y); this.color = color; }
        @Override public boolean equals(Object o) {
            return o instanceof ColorPoint c && super.equals(c) && c.color.equals(color);
        }
        @Override public int hashCode() { return Objects.hash(x, y, color); }
        @Override public String toString() { return "ColorPoint(" + x + "," + y + "," + color + ")"; }
    }

    // ---- Attempt 2: blind comparison, symmetric but not transitive ---------------------
    static class BlindColorPoint extends Point {
        final String color;
        BlindColorPoint(int x, int y, String color) { super(x, y); this.color = color; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Point)) return false;
            if (!(o instanceof BlindColorPoint other)) return o.equals(this); // colour-blind
            return super.equals(other) && other.color.equals(color);
        }
        @Override public int hashCode() { return Objects.hash(x, y, color); }
        @Override public String toString() { return "Blind(" + x + "," + y + "," + color + ")"; }
    }

    // ---- Attempt 3: getClass, symmetric and transitive, not substitutable --------------
    static class StrictPoint {
        final int x, y;
        StrictPoint(int x, int y) { this.x = x; this.y = y; }
        @Override public boolean equals(Object o) {
            if (o == null || o.getClass() != getClass()) return false;
            StrictPoint p = (StrictPoint) o;
            return p.x == x && p.y == y;
        }
        @Override public int hashCode() { return Objects.hash(x, y); }
        @Override public String toString() { return getClass().getSimpleName() + "(" + x + "," + y + ")"; }
    }

    /** Adds NO state whatsoever. Behaviourally a StrictPoint in every way. */
    static final class LoggingPoint extends StrictPoint {
        LoggingPoint(int x, int y) { super(x, y); }
    }

    // ---- The fix: composition ----------------------------------------------------------
    static final class ComposedColorPoint {
        private final Point point;
        private final String color;
        ComposedColorPoint(int x, int y, String color) { this.point = new Point(x, y); this.color = color; }
        Point asPoint() { return point; }                    // explicit view, no pretence
        @Override public boolean equals(Object o) {
            return o instanceof ComposedColorPoint c && c.point.equals(point) && c.color.equals(color);
        }
        @Override public int hashCode() { return Objects.hash(point, color); }
        @Override public String toString() { return "Composed" + point + "+" + color; }
    }

    // =================================================================================
    // Demo A - symmetry broken, and a collection that answers two ways
    // =================================================================================
    private String demoSymmetryBroken() {
        Point plain = new Point(1, 2);
        ColorPoint red = new ColorPoint(1, 2, "red");

        boolean forward = plain.equals(red);
        boolean backward = red.equals(plain);

        // ArrayList.contains(o) calls o.equals(element), so the ARGUMENT decides.
        List<Point> holdingPlain = new ArrayList<>(List.of(plain));
        List<Point> holdingRed = new ArrayList<>(List.of(red));
        boolean redInPlainList = holdingPlain.contains(red);   // red.equals(plain)
        boolean plainInRedList = holdingRed.contains(plain);   // plain.equals(red)

        return "plain.equals(red)=" + forward + " but red.equals(plain)=" + backward
                + " -> [plain].contains(red)=" + redInPlainList
                + ", [red].contains(plain)=" + plainInRedList
                + " : same pair, opposite answers, decided by argument position";
    }

    // =================================================================================
    // Demo B - symmetry restored, transitivity destroyed
    // =================================================================================
    private String demoTransitivityBroken() {
        BlindColorPoint red = new BlindColorPoint(1, 2, "red");
        Point plain = new Point(1, 2);
        BlindColorPoint blue = new BlindColorPoint(1, 2, "blue");

        boolean redPlain = red.equals(plain);
        boolean plainBlue = plain.equals(blue);
        boolean redBlue = red.equals(blue);

        return "red=plain is " + redPlain + ", plain=blue is " + plainBlue
                + ", so transitivity demands red=blue - and it is " + redBlue
                + " : the violation moved from symmetry to transitivity, it did not go away";
    }

    // =================================================================================
    // Demo C - getClass is sound, and rejects a subclass that adds nothing
    // =================================================================================
    private String demoGetClassBreaksSubstitution() {
        StrictPoint plain = new StrictPoint(1, 2);
        LoggingPoint logging = new LoggingPoint(1, 2);   // identical state, zero new fields

        boolean forward = plain.equals(logging);
        boolean backward = logging.equals(plain);
        boolean sameHash = plain.hashCode() == logging.hashCode();

        List<StrictPoint> list = new ArrayList<>(List.of(plain));
        boolean found = list.contains(logging);

        return "symmetric now (" + forward + "/" + backward + ") and hashes agree (" + sameHash
                + "), yet a subclass adding NO state is unequal -> contains=" + found
                + " : this is what breaks runtime-generated proxies";
    }

    // =================================================================================
    // Demo D - composition: the cross-type comparison stops existing
    // =================================================================================
    private String demoComposition() {
        ComposedColorPoint red = new ComposedColorPoint(1, 2, "red");
        ComposedColorPoint alsoRed = new ComposedColorPoint(1, 2, "red");
        ComposedColorPoint blue = new ComposedColorPoint(1, 2, "blue");
        Point plain = new Point(1, 2);

        boolean equalPair = red.equals(alsoRed);
        boolean differentColor = red.equals(blue);
        boolean acrossTypes = red.equals(plain);           // simply false, and unambiguously so
        boolean viewMatches = red.asPoint().equals(plain); // the comparison you actually meant

        return "red=alsoRed " + equalPair + ", red=blue " + differentColor
                + ", red=plainPoint " + acrossTypes + " (no relationship claimed), and the explicit"
                + " view red.asPoint()=plain is " + viewMatches + " : ambiguity removed, not hidden";
    }

    private void run() {
        System.out.println("Track 3, Concept #3 - inheritance breaks equals");
        System.out.println("==============================================");
        line("A  instanceof: asymmetric ", demoSymmetryBroken());
        line("B  blind: intransitive    ", demoTransitivityBroken());
        line("C  getClass: unsubstitutable", demoGetClassBreaksSubstitution());
        line("D  composition: works     ", demoComposition());
        System.out.println();
        System.out.println("Takeaway: you cannot add a value component to an instantiable class and keep the");
        System.out.println("contract. instanceof loses symmetry, blind comparison loses transitivity, and");
        System.out.println("getClass keeps both while losing substitutability. Three attempts, three broken");
        System.out.println("clauses - the choice is which one to break. So do not choose: hold the object");
        System.out.println("instead of extending it, or make the class final and let the question vanish.");
    }

    private static void line(String label, String verdict) {
        System.out.println("  [" + label + "] " + verdict);
    }

    public static void main(String[] args) {
        new Equality03Inheritance().run();
    }
}
