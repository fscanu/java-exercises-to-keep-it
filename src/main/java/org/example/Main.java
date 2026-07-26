// ABOUTME: Launcher/index for the exercise tracks: JVM memory model (org.example.jmm) and
// ABOUTME: generics variance (org.example.generics). Run with no arg for the index.
package org.example;

import org.example.equality.Equality01Contract;
import org.example.equality.Equality02MutableKey;
import org.example.equality.Equality03Inheritance;
import org.example.equality.Equality04ComparableVsEquals;
import org.example.equality.Equality05JdkAndRecords;
import org.example.generics.Generics01Erasure;
import org.example.generics.Generics02Variance;
import org.example.generics.Generics03Pecs;
import org.example.generics.Generics04JdkSignatures;
import org.example.generics.Generics05BoundedVsWildcard;
import org.example.jmm.Concept01Reordering;
import org.example.jmm.Concept02HappensBefore;
import org.example.jmm.Concept03VolatileVsAtomic;
import org.example.jmm.Concept04DoubleCheckedLocking;
import org.example.jmm.Concept05ConcurrentPrimitives;
import org.example.jmm.Concept06ParallelStreams;
import org.example.jmm.Concept07ProducerConsumer;

public class Main {

    private static final String[] NO_ARGS = new String[0];

    /** A track: a title and its concepts, each a name + one-line summary. */
    private record Track(String title, String[][] concepts) { }

    private static final Track[] TRACKS = {
            new Track("JVM Memory Model & Concurrency", new String[][]{
                    {"Reordering", "why cross-thread reordering is legal; the (0,0) that sequential consistency forbids"},
                    {"HappensBefore", "the 5 happens-before edges + transitivity; with no edge there is no ordering"},
                    {"VolatileVsAtomic", "volatile = visibility + ordering, NOT atomicity; counter++ still races"},
                    {"DoubleCheckedLocking", "naive lazy init builds N instances; DCL needs volatile; holder & enum"},
                    {"ConcurrentPrimitives", "why j.u.c exists: HashMap/synchronizedMap break; CAS & ConcurrentHashMap"},
                    {"ParallelStreams", "parallel() shares the commonPool; shared mutable state races; reduce/collect"},
                    {"ProducerConsumer", "one handoff three ways: where the plain chain breaks, volatile and queue"},
            }),
            new Track("Generics Variance (PECS)", new String[][]{
                    {"Erasure", "List<String> and List<Integer> are one class at runtime; the raw-type back door"},
                    {"Variance", "arrays are covariant and checked at runtime; generics were erased, so invariant"},
                    {"Pecs", "producer extends / consumer super, DERIVED from capture rather than memorised"},
                    {"JdkSignatures", "Collections.copy, List.sort, Stream.map, read from the class files"},
                    {"BoundedVsWildcard", "when <T extends ...> is required and when a wildcard is the better tool"},
            }),
            new Track("equals/hashCode & Collection Behaviour", new String[][]{
                    {"Contract", "hashCode picks the bucket, equals decides inside it; break either and lookups vanish"},
                    {"MutableKey", "mutate a key and the object is present, unfindable and unremovable at once"},
                    {"Inheritance", "adding a value component breaks symmetry, transitivity or substitutability"},
                    {"ComparableVsEquals", "TreeSet/TreeMap decide identity with compareTo and never call equals"},
                    {"JdkAndRecords", "what the JDK defines for you, and the array component records cannot fix"},
            }),
    };

    public static void main(String[] args) throws InterruptedException {
        if (args.length == 0) {
            printIndex();
            return;
        }
        String arg = args[0].toLowerCase();

        if (arg.equals("all")) {
            for (int t = 1; t <= TRACKS.length; t++) runTrack(t);
            return;
        }

        int dot = arg.indexOf('.');
        if (dot < 0) {                                   // a whole track: "1" or "2"
            int t = parsePositiveInt(arg);
            if (t < 1 || t > TRACKS.length) { unknown(args[0]); return; }
            runTrack(t);
            return;
        }

        int t = parsePositiveInt(arg.substring(0, dot)); // one concept: "1.3"
        int n = parsePositiveInt(arg.substring(dot + 1));
        if (t < 1 || t > TRACKS.length || n < 1 || n > TRACKS[t - 1].concepts().length) {
            unknown(args[0]);
            return;
        }
        runConcept(t, n);
    }

    private static void unknown(String arg) {
        System.out.println("Unknown selector: " + arg + "\n");
        printIndex();
    }

    private static void printIndex() {
        System.out.println("Java exercises - concept demos");
        System.out.println("==============================");
        for (int t = 0; t < TRACKS.length; t++) {
            Track track = TRACKS[t];
            System.out.printf("%nTrack %d: %s%n", t + 1, track.title());
            for (int i = 0; i < track.concepts().length; i++) {
                System.out.printf("  %d.%-3d %-22s %s%n",
                        t + 1, i + 1, track.concepts()[i][0], track.concepts()[i][1]);
            }
        }
        System.out.println();
        System.out.println("Usage: java org.example.Main <track.concept | track | all>");
        System.out.println("  e.g. 1.3  one concept        2    a whole track        all   everything");
    }

    private static void runTrack(int t) throws InterruptedException {
        String[][] concepts = TRACKS[t - 1].concepts();
        for (int n = 1; n <= concepts.length; n++) {
            runConcept(t, n);
            if (n < concepts.length) System.out.println("\n" + "=".repeat(80) + "\n");
        }
    }

    private static void runConcept(int t, int n) throws InterruptedException {
        System.out.println(">>> Track " + t + ", Concept #" + n + " - " + TRACKS[t - 1].concepts()[n - 1][0] + "\n");
        switch (t) {
            case 1 -> runJmm(n);
            case 2 -> runGenerics(n);
            case 3 -> runEquality(n);
            default -> throw new IllegalArgumentException("no track " + t);
        }
    }

    private static void runJmm(int n) throws InterruptedException {
        switch (n) {
            case 1 -> Concept01Reordering.main(NO_ARGS);
            case 2 -> Concept02HappensBefore.main(NO_ARGS);
            case 3 -> Concept03VolatileVsAtomic.main(NO_ARGS);
            case 4 -> Concept04DoubleCheckedLocking.main(NO_ARGS);
            case 5 -> Concept05ConcurrentPrimitives.main(NO_ARGS);
            case 6 -> Concept06ParallelStreams.main(NO_ARGS);
            case 7 -> Concept07ProducerConsumer.main(NO_ARGS);
            default -> throw new IllegalArgumentException("no concept " + n);
        }
    }

    private static void runGenerics(int n) {
        try {
            switch (n) {
                case 1 -> Generics01Erasure.main(NO_ARGS);
                case 2 -> Generics02Variance.main(NO_ARGS);
                case 3 -> Generics03Pecs.main(NO_ARGS);
                case 4 -> Generics04JdkSignatures.main(NO_ARGS);
                case 5 -> Generics05BoundedVsWildcard.main(NO_ARGS);
                default -> throw new IllegalArgumentException("no concept " + n);
            }
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("reflection demo failed", e);
        }
    }

    private static void runEquality(int n) {
        switch (n) {
            case 1 -> Equality01Contract.main(NO_ARGS);
            case 2 -> Equality02MutableKey.main(NO_ARGS);
            case 3 -> Equality03Inheritance.main(NO_ARGS);
            case 4 -> Equality04ComparableVsEquals.main(NO_ARGS);
            case 5 -> Equality05JdkAndRecords.main(NO_ARGS);
            default -> throw new IllegalArgumentException("no concept " + n);
        }
    }

    private static int parsePositiveInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
