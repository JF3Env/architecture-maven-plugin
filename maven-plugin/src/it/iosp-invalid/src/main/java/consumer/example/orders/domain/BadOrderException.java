package consumer.example.orders.domain;

public final class BadOrderException extends RuntimeException {
  private BadOrderException() {
    super("An order count must not be negative");
  }

  public static BadOrderException of() {
    return new BadOrderException();
  }
}
