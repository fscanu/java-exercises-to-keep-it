import java.util.List;

/** WRONG TURN 2: expecting a typed read out of a consumer. */
public class W2ReadTypedFromConsumer {
    static Number first(List<? super Number> consumer) {
        return consumer.get(0);
    }
}
