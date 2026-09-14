package consumer.example.domain.orders.aggregate;

import consumer.example.domain.orders.aggregate.exceptions.BadOrderException;

public final class Order {
  private long count;

  public Order(long count) {
    setCount(count);
  }

  private void setCount(long value) {
    if (value < 0) throw BadOrderException.of();
    count = value;
  }
}
