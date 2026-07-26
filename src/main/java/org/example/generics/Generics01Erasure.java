// ABOUTME: Demonstrates type erasure concretely: List<String> and List<Integer> are one class at
// ABOUTME: runtime, the back door that proves it, and why erasure forces the compiler to be strict.
package org.example.generics;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;

/*
 * =====================================================================================
 * Concept #1 - "Type erasure, concretely"
 * =====================================================================================
 *
 * Generics are a COMPILE-TIME device. The compiler checks your types, then throws the
 * information away and emits bytecode that mentions only the erasure: List<String> and
 * List<Integer> both become plain List, and every `String s = list.get(i)` becomes a
 * get() plus a synthetic cast to String that the compiler inserts for you.
 *
 * That single fact explains the entire rest of this track. The runtime CANNOT check
 * generic types, because at runtime there are none. So every guarantee has to be
 * established by the compiler, before the information is destroyed - and a checker that
 * gets no second chance at runtime must be conservative. Every rule that looks arbitrary
 * ("why can't I add to a ? extends list?", "why is List<T> not covariant?") is the
 * compiler refusing to accept something it would have no way to catch later.
 *
 * WHAT ERASURE DOES AND DOES NOT ERASE. This trips people up, so be precise:
 *   - ERASED: the type ARGUMENTS of an object at runtime. An ArrayList<String> instance
 *     carries no memory of String. Demo A proves it with getClass().
 *   - KEPT: the generic DECLARATIONS in the class file's Signature attribute - field
 *     types, method signatures, supertypes. Demo C reads `List<String>` back out of a
 *     field via reflection. This is how javac can compile against a library it did not
 *     build, and how `javap` can print the signatures Concept #4 relies on.
 *
 * So it is not that generic information is absent from the class file. It is that no
 * INSTANCE carries its type arguments, which is exactly what a runtime check would need.
 *
 * THE CONSEQUENCES YOU CANNOT PROGRAM AROUND (see wrong-turns/W6GenericArray.java):
 *   - `new T[10]` is rejected: the array would need a runtime component type it cannot have
 *   - `o instanceof List<String>` is rejected: there is nothing at runtime to test
 *   - two overloads differing only in type arguments collide: both erase to the same thing
 * =====================================================================================
 */
public final class Generics01Erasure {

    // Demo C reflects on this field. Its generic type survives in the class file.
    private List<String> declaredAsListOfString = new ArrayList<>();

    // =================================================================================
    // Demo A - the same class object: the type argument is gone at runtime
    // =================================================================================
    private String demoSameClassAtRuntime() {
        List<String> strings = new ArrayList<>();
        List<Integer> integers = new ArrayList<>();

        Class<?> a = strings.getClass();
        Class<?> b = integers.getClass();
        boolean identical = (a == b); // reference equality, not just equals()

        return identical
                ? "ArrayList<String>.getClass() == ArrayList<Integer>.getClass() -> " + a.getName()
                  + " : one class, the <T> is gone"
                : "UNEXPECTED: " + a + " != " + b;
    }

    // =================================================================================
    // Demo B - the back door: a raw type lets an Integer into a List<String>
    // =================================================================================
    @SuppressWarnings({"unchecked", "rawtypes"})
    private String demoHeapPollution() {
        List<String> strings = new ArrayList<>();
        strings.add("legitimately a string");

        // The raw type switches generic checking OFF for this reference. javac warns
        // ("unchecked call") and compiles it. At runtime there is no List<String>, only a
        // List, so nothing objects: the Integer goes in and sits there quietly.
        List raw = strings;
        raw.add(42);

        // The list is now "polluted": its static type promises String, its contents do not.
        // Note WHERE it breaks. Not at the add above - at the read below, in code that did
        // nothing wrong, because THAT is where javac inserted the synthetic cast to String.
        int sizeAfter = strings.size();
        String failure;
        try {
            String s = strings.get(1); // compiles to: (String) strings.get(1)
            failure = "UNEXPECTED: read back " + s;
        } catch (ClassCastException e) {
            failure = "ClassCastException at the READ: " + e.getMessage();
        }

        return "size=" + sizeAfter + " after smuggling an Integer in -> " + failure;
    }

    // =================================================================================
    // Demo C - what erasure KEEPS: the declaration's signature, in the class file
    // =================================================================================
    private String demoSignatureSurvives() throws NoSuchFieldException {
        Field f = Generics01Erasure.class.getDeclaredField("declaredAsListOfString");

        Class<?> erased = f.getType();                       // what the VM works with
        ParameterizedType generic = (ParameterizedType) f.getGenericType(); // what javac recorded
        java.lang.reflect.Type arg = generic.getActualTypeArguments()[0];

        // The INSTANCE has no type argument (Demo A); the DECLARATION still does.
        boolean instanceHasIt = false;
        for (java.lang.reflect.TypeVariable<?> tv : declaredAsListOfString.getClass().getTypeParameters()) {
            if (!"E".equals(tv.getName())) instanceHasIt = true; // only ever the parameter NAME
        }

        return "field type erases to " + erased.getSimpleName()
                + ", but its recorded signature is still " + generic.getTypeName()
                + " (argument = " + ((Class<?>) arg).getSimpleName() + ")"
                + "; the instance itself carries " + (instanceHasIt ? "UNEXPECTED" : "nothing");
    }

    // =================================================================================
    // Demo D - the one thing erasure cannot fake: a runtime type test
    // =================================================================================
    private String demoNoRuntimeTypeTest() {
        List<String> strings = new ArrayList<>();
        strings.add("a");
        List<Integer> integers = new ArrayList<>();
        integers.add(1);

        // You can test the raw type. You cannot test the type argument: there is nothing
        // to test. `o instanceof List<String>` does not compile (wrong-turns/W6).
        boolean bothAreLists = (strings instanceof List<?>) && (integers instanceof List<?>);

        // The best you can do is inspect an ELEMENT, which tells you about that element
        // and nothing about the list. An empty list is indistinguishable either way.
        Object firstOfStrings = strings.get(0);
        Object firstOfIntegers = integers.get(0);

        return "both instanceof List<?> = " + bothAreLists
                + "; only elements are inspectable (" + firstOfStrings.getClass().getSimpleName()
                + ", " + firstOfIntegers.getClass().getSimpleName()
                + ") - and an EMPTY list of either type is indistinguishable";
    }

    private void run() throws NoSuchFieldException {
        System.out.println("Track 2, Concept #1 - type erasure, concretely");
        System.out.println("==============================================");
        line("A  same class    ", demoSameClassAtRuntime());
        line("B  heap pollution", demoHeapPollution());
        line("C  signature kept", demoSignatureSurvives());
        line("D  no type test  ", demoNoRuntimeTypeTest());
        System.out.println();
        System.out.println("Takeaway: no INSTANCE carries its type arguments, so no check can happen at");
        System.out.println("runtime. Everything must be proven before erasure, by a compiler that gets no");
        System.out.println("second chance - which is why the variance rules that follow are strict rather");
        System.out.println("than clever. Demo B is what strictness is protecting you from.");
    }

    private static void line(String label, String verdict) {
        System.out.println("  [" + label + "] " + verdict);
    }

    public static void main(String[] args) throws NoSuchFieldException {
        new Generics01Erasure().run();
    }
}
