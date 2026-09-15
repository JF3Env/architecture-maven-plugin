package consumer.example.billing.application;

import consumer.example.billing.domain.InvoicePolicy;
import consumer.example.orders.api.Orders;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class InvoiceOrderHandler {
  private final Orders orders;
  private final InvoicePolicy policy;

  public long invoice(long count) {
    var order = orders.place(count);
    return policy.total(order.count());
  }
}
