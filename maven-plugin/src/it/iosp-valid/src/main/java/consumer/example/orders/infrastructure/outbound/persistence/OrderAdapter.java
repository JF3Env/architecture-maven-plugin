package consumer.example.orders.infrastructure.outbound.persistence;

import consumer.example.orders.domain.Order;
import consumer.example.orders.domain.OrderRepository;
import consumer.example.orders.infrastructure.outbound.persistence.mappers.OrderPersistenceMapper;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class OrderAdapter implements OrderRepository {
  private final OrderPersistenceMapper mapper;

  @Override
  public void save(Order order) {
    mapper.entity(order);
  }
}
