package consumer.example.domain.orders.services.read.factory;

import consumer.example.domain.orders.services.read.result.OrderResult;

public final class StandardOrderFactory implements OrderFactory {
  @Override
  public OrderResult result() {
    return new OrderResult(1);
  }
}
