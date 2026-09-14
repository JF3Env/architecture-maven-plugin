package consumer.example.domain.orders.services.read.factory;

import consumer.example.domain.orders.services.read.result.OrderResult;
import lombok.NoArgsConstructor;

@NoArgsConstructor
public final class StandardOrderFactory implements OrderFactory {
  @Override
  public OrderResult result() {
    return new OrderResult(1);
  }
}
