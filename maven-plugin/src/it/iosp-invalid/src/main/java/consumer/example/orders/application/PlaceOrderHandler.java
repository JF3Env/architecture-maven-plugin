package consumer.example.orders.application;

import consumer.example.orders.api.OrderRef;
import consumer.example.orders.api.Orders;
import consumer.example.orders.application.mappers.OrderRefMapper;
import consumer.example.orders.domain.OrderFactory;
import consumer.example.orders.domain.OrderRepository;
import consumer.example.platform.application.UnitOfWork;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class PlaceOrderHandler implements Orders {
  private final UnitOfWork unitOfWork;
  private final OrderRepository repository;
  private final OrderFactory factory;
  private final OrderRefMapper refs;

  @Override
  public OrderRef place(long count) {
    unitOfWork.begin();
    var order = factory.create(count);
    repository.save(order);
    unitOfWork.commit();
    return refs.ref(order);
  }
}
