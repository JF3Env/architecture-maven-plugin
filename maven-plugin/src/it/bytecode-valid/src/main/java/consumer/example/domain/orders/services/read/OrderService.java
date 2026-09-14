package consumer.example.domain.orders.services.read;

import consumer.example.domain.orders.OrderRepository;
import consumer.example.domain.orders.aggregate.Order;
import consumer.example.domain.orders.services.read.factory.OrderFactory;
import consumer.example.domain.orders.services.read.result.OrderResult;

public final class OrderService {
  private final OrderRepository repository;
  private final Order order;
  private final OrderFactory factory;

  public OrderService(OrderRepository repository, Order order, OrderFactory factory) {
    this.repository = repository;
    this.order = order;
    this.factory = factory;
  }

  public OrderResult read() {
    return factory.result();
  }
}
