package consumer.example.orders.infrastructure.wiring;

import consumer.example.orders.application.PlaceOrderHandler;
import consumer.example.orders.application.mappers.OrderRefMapper;
import consumer.example.orders.domain.OrderFactory;
import consumer.example.orders.domain.OrderRepository;
import consumer.example.platform.application.UnitOfWork;
import jakarta.enterprise.inject.Produces;
import lombok.NoArgsConstructor;

@NoArgsConstructor
public final class OrdersProducer {
  @Produces
  public PlaceOrderHandler handler(
      UnitOfWork unitOfWork, OrderRepository repository, OrderRefMapper refs) {
    return new PlaceOrderHandler(unitOfWork, repository, new OrderFactory(), refs);
  }
}
