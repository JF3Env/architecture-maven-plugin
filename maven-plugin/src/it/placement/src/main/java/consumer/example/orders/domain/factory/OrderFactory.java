package consumer.example.orders.domain.factory;

import org.jspecify.annotations.NullMarked;

/** The factory role folder this consumer already adopted, which is what calibrates the advisory. */
@NullMarked
public final class OrderFactory {
  public OrderFactory() {}

  public String create(String label) {
    return label.strip();
  }
}
