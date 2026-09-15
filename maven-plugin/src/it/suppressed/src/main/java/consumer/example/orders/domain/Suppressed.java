package consumer.example.orders.domain;

public final class Suppressed {
  @SuppressWarnings("PMD.DomainMethodsMustNotReturnNull")
  public Object value() {
    return null;
  }

  private final String label;

  public Suppressed(String label) {
    this.label = label.strip();
  }

  public String describe() {
    return this.label.concat("!");
  }
}
