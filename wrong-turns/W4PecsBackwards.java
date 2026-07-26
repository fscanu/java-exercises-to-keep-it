import java.util.List;

/** WRONG TURN 4: PECS reversed. dest is written to, src is read from - the wildcards say
 *  the opposite. This is the exact mistake the mnemonic is supposed to prevent. */
public class W4PecsBackwards {
    static <T> void copy(List<? extends T> dest, List<? super T> src) {
        for (int i = 0; i < src.size(); i++) {
            dest.set(i, src.get(i));
        }
    }
}
