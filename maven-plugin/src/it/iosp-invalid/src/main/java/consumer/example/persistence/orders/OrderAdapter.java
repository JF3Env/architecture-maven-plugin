package consumer.example.persistence.orders;

import consumer.example.domain.orders.OrderRepository;
import lombok.NoArgsConstructor;

@NoArgsConstructor
public final class OrderAdapter implements OrderRepository {
  @Override
  public void save() {}
}
