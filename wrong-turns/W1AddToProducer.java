import java.util.List;

/** WRONG TURN 1: adding to a producer. `? extends` means "some unknown subtype of Number". */
public class W1AddToProducer {
    static void addOne(List<? extends Number> producer) {
        producer.add(1);
    }
}
