package consumer.example.orders.domain;

import java.util.Optional;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class Valid {
  private final String fallback;

  public Valid(String fallback) {
    this.fallback = fallback.strip();
  }

  public String value(Optional<String> input) {
    return input.orElse(this.fallback);
  }
}
