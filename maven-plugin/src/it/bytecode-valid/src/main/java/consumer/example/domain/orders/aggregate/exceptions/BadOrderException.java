package consumer.example.domain.orders.aggregate.exceptions;

public final class BadOrderException extends RuntimeException {
  private BadOrderException() {}

  public static BadOrderException of() {
    return new BadOrderException();
  }
}
