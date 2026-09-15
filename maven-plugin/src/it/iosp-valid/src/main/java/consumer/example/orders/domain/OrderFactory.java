package consumer.example.orders.domain;

import lombok.NoArgsConstructor;

@NoArgsConstructor
public final class OrderFactory {
  public Order create(long count) {
    return new Order(count);
  }
}
