package consumer.example.infra.domains.producers;

import consumer.example.domain.orders.OrderRepository;
import consumer.example.domain.orders.aggregate.Order;
import consumer.example.domain.orders.services.read.OrderService;
import consumer.example.domain.orders.services.read.factory.StandardOrderFactory;
import jakarta.enterprise.inject.Produces;
import lombok.NoArgsConstructor;

@NoArgsConstructor
public final class OrdersProducer {
  @Produces
  public OrderService service(OrderRepository repository, Order order) {
    return new OrderService(repository, order, new StandardOrderFactory());
  }
}
