package consumer.example.domain.orders.services.read;

import consumer.example.domain.orders.OrderRepository;
import consumer.example.domain.orders.aggregate.Order;
import consumer.example.domain.orders.services.read.factory.OrderFactory;
import consumer.example.domain.orders.services.read.result.OrderResult;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class OrderService {
  private final OrderRepository repository;
  private final Order order;
  private final OrderFactory factory;

  public OrderResult read() {
    return factory.result();
  }
}
