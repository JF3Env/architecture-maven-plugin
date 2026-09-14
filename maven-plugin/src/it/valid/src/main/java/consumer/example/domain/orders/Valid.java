package consumer.example.domain.orders;

import java.util.Optional;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class Valid {
  public String value(Optional<String> input) {
    return input.orElse("empty");
  }
}
