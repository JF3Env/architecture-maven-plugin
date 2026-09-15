package consumer.example.orders.domain;

import consumer.example.platform.domain.AggregateRoot;

@AggregateRoot
public final class Order {
  private long count;

  public Order(long count) {
    setCount(count);
  }

  public long getCount() {
    return count;
  }

  private void setCount(long value) {
    if (value < 0) throw BadOrderException.of();
    count = value;
  }
}
