import java.util.ArrayList;
import java.util.List;

/** WRONG TURN 5: no wildcard at all. Correct, but uselessly strict at the call site. */
public class W5NoWildcardTooStrict {
    static double sum(List<Number> nums) {
        double t = 0;
        for (Number n : nums) t += n.doubleValue();
        return t;
    }
    static void call() {
        List<Integer> ints = new ArrayList<>();
        sum(ints);
    }
}
