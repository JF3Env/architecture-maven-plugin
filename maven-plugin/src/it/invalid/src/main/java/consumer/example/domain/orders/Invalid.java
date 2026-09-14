package consumer.example.domain.orders;

import java.util.Optional;

public final class Invalid {
  public String value(Optional<String> input) {
    return input.get();
  }
}
