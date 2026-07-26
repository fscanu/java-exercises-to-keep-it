import java.util.List;

/** WRONG TURN 6: erasure consequences - you cannot create a T[] or test a parameterized type. */
public class W6GenericArray {
    static <T> T[] make() {
        return new T[10];
    }
    static boolean isListOfString(Object o) {
        return o instanceof List<String>;
    }
}
