package consumer.example.domain.orders.services.read.result;

public final class OrderResult {
  private final int count;

  public OrderResult(int count) {
    this.count = count;
  }

  public int getCount() {
    return count;
  }
}
