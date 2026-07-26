// ABOUTME: Launcher/index for the Java Memory Model concept demos in org.example.jmm.
// ABOUTME: Run with no arg for the index, a number 1-7 for one concept, or "all" for every one.
package org.example;

import org.example.jmm.Concept01Reordering;
import org.example.jmm.Concept02HappensBefore;
import org.example.jmm.Concept03VolatileVsAtomic;
import org.example.jmm.Concept04DoubleCheckedLocking;
import org.example.jmm.Concept05ConcurrentPrimitives;
import org.example.jmm.Concept06ParallelStreams;
import org.example.jmm.Concept07ProducerConsumer;

public class Main {

    private static final String[] NO_ARGS = new String[0];

    private static final String[][] CONCEPTS = {
            {"Reordering", "why cross-thread reordering is legal; the (0,0) that sequential consistency forbids"},
            {"HappensBefore", "the 5 happens-before edges + transitivity; with no edge there is no ordering"},
            {"VolatileVsAtomic", "volatile = visibility + ordering, NOT atomicity; counter++ still races"},
            {"DoubleCheckedLocking", "naive lazy init builds N instances; DCL needs volatile; holder & enum"},
            {"ConcurrentPrimitives", "why j.u.c exists: HashMap/synchronizedMap break; CAS & ConcurrentHashMap"},
            {"ParallelStreams", "parallel() shares the commonPool; shared mutable state races; reduce/collect"},
            {"ProducerConsumer", "one handoff three ways: where the plain chain breaks, volatile and queue"},
    };

    public static void main(String[] args) throws InterruptedException {
        if (args.length == 0) {
            printIndex();
            return;
        }
        String arg = args[0].toLowerCase();
        if (arg.equals("all")) {
            for (int n = 1; n <= CONCEPTS.length; n++) {
                runConcept(n);
                if (n < CONCEPTS.length) System.out.println("\n" + "=".repeat(80) + "\n");
            }
            return;
        }
        int n = parsePositiveInt(arg);
        if (n < 1 || n > CONCEPTS.length) {
            System.out.println("Unknown concept: " + args[0] + "\n");
            printIndex();
            return;
        }
        runConcept(n);
    }

    private static void printIndex() {
        System.out.println("Java Memory Model - concept demos (org.example.jmm)");
        System.out.println("===================================================");
        for (int i = 0; i < CONCEPTS.length; i++) {
            System.out.printf("  %d  %-22s %s%n", i + 1, CONCEPTS[i][0], CONCEPTS[i][1]);
        }
        System.out.println();
        System.out.println("Usage: java org.example.Main <1-" + CONCEPTS.length + "|all>");
    }

    private static void runConcept(int n) throws InterruptedException {
        System.out.println(">>> Concept #" + n + " - " + CONCEPTS[n - 1][0] + "\n");
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

    private static int parsePositiveInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
