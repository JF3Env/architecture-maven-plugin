package consumer.example.domain.orders;

import java.util.Optional;

public final class Invalid {
  public String value(Optional<String> input) {
    return input.get();
  }

  private final String fallback;

  public Invalid(String fallback) {
    this.fallback = fallback.strip();
  }

  public String fallbackOrValue(Optional<String> input) {
    return input.orElse(this.fallback);
  }
}
