import java.util.ArrayList;
import java.util.List;

/** WRONG TURN 3: assuming List<T> is covariant the way arrays are. */
public class W3Invariance {
    static void go() {
        List<Number> nums = new ArrayList<Integer>();
    }
}
