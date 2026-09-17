package consumer.example.orders.domain;

import org.jspecify.annotations.NullMarked;

@NullMarked
public final class Forwarding {
  private final String prefix;

  public Forwarding(String prefix) {
    this.prefix = prefix.strip();
  }

  public String label(String suffix) {
    return this.render(suffix) + "!";
  }

  private String render(String suffix) {
    return this.combine(suffix);
  }

  public String combine(String suffix) {
    return this.prefix + suffix;
  }
}
