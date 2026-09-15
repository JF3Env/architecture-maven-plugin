package consumer.example.billing.infrastructure.wiring;

import consumer.example.billing.application.InvoiceOrderHandler;
import consumer.example.billing.domain.InvoicePolicy;
import consumer.example.orders.api.Orders;
import jakarta.enterprise.inject.Produces;
import lombok.NoArgsConstructor;

@NoArgsConstructor
public final class BillingProducer {
  @Produces
  public InvoiceOrderHandler handler(Orders orders) {
    return new InvoiceOrderHandler(orders, new InvoicePolicy());
  }
}
