// ABOUTME: Demonstrates why List<T> is invariant while arrays are covariant, by showing the runtime
// ABOUTME: check that makes array covariance survivable and that erasure denies to generics.
package org.example.generics;

import java.util.ArrayList;
import java.util.List;

/*
 * =====================================================================================
 * Concept #2 - "Covariance, contravariance, invariance"
 * =====================================================================================
 *
 * The three words, on the question "Dog is a subtype of Animal, so what is the relation
 * between Box<Dog> and Box<Animal>?"
 *
 *   COVARIANT      Box<Dog> IS a subtype of Box<Animal>       (subtyping flows along)
 *   CONTRAVARIANT  Box<Animal> IS a subtype of Box<Dog>       (subtyping flows backwards)
 *   INVARIANT      neither; they are simply unrelated types
 *
 * In Java: arrays are COVARIANT (Dog[] is an Animal[]). Generics are INVARIANT
 * (List<Dog> is NOT a List<Animal>, nor the reverse). Same language, opposite choices,
 * and the difference is not taste. It is Concept #1.
 *
 * -------------------------------------------------------------------------------------
 * WHY ARRAYS GOT AWAY WITH IT
 * -------------------------------------------------------------------------------------
 * Array covariance is unsound on its face: if Dog[] is an Animal[], then through the
 * Animal[] reference you may store a Cat. Java allows the assignment anyway and pays for
 * it with a RUNTIME CHECK: every single array store checks the value against the array's
 * actual component type, and throws ArrayStoreException on mismatch (Demo A).
 *
 * It can do that only because an array KNOWS its component type at runtime. `new String[2]`
 * really is a String[] object, and getClass().getComponentType() will tell you so (Demo A
 * prints it). The type information survives into the running program.
 *
 * -------------------------------------------------------------------------------------
 * WHY GENERICS COULD NOT
 * -------------------------------------------------------------------------------------
 * A List<Dog> does not know it is a List<Dog> (Concept #1, Demo A). So the equivalent
 * runtime check is IMPOSSIBLE - there is nothing to check against. If generics were
 * covariant, this would compile:
 *
 *      List<Object> objects = new ArrayList<String>();   // pretend this were legal
 *      objects.add(42);                                  // no runtime check exists
 *      String s = ((List<String>) objects).get(0);       // ClassCastException, far away
 *
 * and the failure would surface somewhere else entirely, in code that did nothing wrong -
 * exactly the shape of Concept #1 Demo B. There is no ListStoreException to save you,
 * because there is no runtime type to compare against.
 *
 * So generics are invariant: the compiler refuses the assignment up front, because it is
 * the last checkpoint before the evidence is destroyed. Arrays chose "allow it, check at
 * runtime". Generics could not choose that, so they chose "reject it at compile time".
 *
 * -------------------------------------------------------------------------------------
 * AND THEN WILDCARDS GIVE THE FLEXIBILITY BACK, SAFELY
 * -------------------------------------------------------------------------------------
 * Invariance alone would be crippling: a method taking List<Number> could not accept a
 * List<Integer> (wrong-turns/W5NoWildcardTooStrict.java shows the rejection). Wildcards
 * restore the flexibility, but only in the direction that stays safe, by REMOVING the
 * operation that would break. That is Concept #3.
 *
 *   List<? extends Number>   covariant-ish, and you may not add    (Demos C)
 *   List<? super Integer>    contravariant-ish, and you may not typed-read (Demo D)
 * =====================================================================================
 */
public final class Generics02Variance {

    static class Animal { final String name; Animal(String n) { name = n; } public String toString() { return name; } }
    static class Dog extends Animal { Dog(String n) { super(n); } }
    static class Cat extends Animal { Cat(String n) { super(n); } }

    // =================================================================================
    // Demo A - arrays are covariant, and pay for it with a check on EVERY store
    // =================================================================================
    private String demoArrayCovariance() {
        Dog[] dogs = { new Dog("rex") };
        Animal[] animals = dogs;      // LEGAL: arrays are covariant. No cast, no warning.

        // The array still knows what it really is. That is the whole difference.
        String actualComponent = animals.getClass().getComponentType().getSimpleName();

        String outcome;
        try {
            animals[0] = new Cat("tom"); // compiles fine; the VM checks and refuses
            outcome = "UNEXPECTED: the Cat was stored in a Dog[]";
        } catch (ArrayStoreException e) {
            outcome = "ArrayStoreException(" + e.getMessage() + ") at the store";
        }

        return "Dog[] assigned to Animal[] (legal), component type at runtime = "
                + actualComponent + " -> " + outcome;
    }

    // =================================================================================
    // Demo B - generics are invariant: the same shape is rejected before it can run
    // =================================================================================
    private String demoGenericInvariance() {
        List<Dog> dogs = new ArrayList<>();
        dogs.add(new Dog("rex"));

        // List<Animal> animals = dogs;   // <-- DOES NOT COMPILE:
        //   error: incompatible types: List<Dog> cannot be converted to List<Animal>
        // (wrong-turns/W3Invariance.java is the same rejection with Integer/Number.)
        //
        // There is no ListStoreException to fall back on, so the compiler cannot let this
        // through the way the VM lets Demo A through. Note what erasure leaves us:
        Class<?> dogListClass = dogs.getClass();
        boolean sameAsAnimalList = dogListClass == new ArrayList<Animal>().getClass();

        return "List<Dog> and List<Animal> are unrelated types to javac, yet the SAME class ("
                + dogListClass.getSimpleName() + ") at runtime: sameClass=" + sameAsAnimalList
                + " -> nothing left to check against, so it must be rejected early";
    }

    // =================================================================================
    // Demo C - `? extends` buys covariance back, minus the ability to add
    // =================================================================================
    private String demoCovariantWildcard() {
        List<Dog> dogs = new ArrayList<>();
        dogs.add(new Dog("rex"));
        dogs.add(new Dog("fido"));

        List<? extends Animal> animals = dogs;   // LEGAL: covariance via wildcard

        // Reading is fine: whatever the unknown subtype is, it IS an Animal.
        Animal first = animals.get(0);

        // animals.add(new Dog("spot")); // <-- DOES NOT COMPILE, and that is the price.
        //   See wrong-turns/W1AddToProducer.java for the exact message. The compiler does
        //   not know whether this is a List<Dog>, a List<Cat> or a List<Animal>.

        return "List<Dog> assigned to List<? extends Animal> (legal), read back " + first
                + "; add() is rejected - that removal is what makes it safe";
    }

    // =================================================================================
    // Demo D - `? super` buys contravariance, minus the ability to read a specific type
    // =================================================================================
    private String demoContravariantWildcard() {
        List<Animal> animals = new ArrayList<>();
        List<? super Dog> dogSink = animals;      // LEGAL: contravariance via wildcard

        dogSink.add(new Dog("rex"));              // writing a Dog is always safe here
        // Dog d = dogSink.get(0);                // <-- DOES NOT COMPILE: only Object comes out.
        //   See wrong-turns/W2ReadTypedFromConsumer.java.
        Object onlyObject = dogSink.get(0);

        return "List<Animal> assigned to List<? super Dog> (legal), add(Dog) accepted, "
                + "get() typed only as Object (" + onlyObject + ") - the read is what was removed";
    }

    private void run() {
        System.out.println("Track 2, Concept #2 - covariance, contravariance, invariance");
        System.out.println("===========================================================");
        line("A  arrays covariant  ", demoArrayCovariance());
        line("B  generics invariant", demoGenericInvariance());
        line("C  ? extends         ", demoCovariantWildcard());
        line("D  ? super           ", demoContravariantWildcard());
        System.out.println();
        System.out.println("Takeaway: arrays are covariant because they kept their component type and can");
        System.out.println("check every store at runtime. Generics were erased, so no such check exists, so");
        System.out.println("invariance is the only sound default. Wildcards then hand back one direction of");
        System.out.println("flexibility each - and pay for it by DELETING the operation that would break.");
        System.out.println("Which operation gets deleted is not arbitrary. That is Concept #3.");
    }

    private static void line(String label, String verdict) {
        System.out.println("  [" + label + "] " + verdict);
    }

    public static void main(String[] args) {
        new Generics02Variance().run();
    }
}
