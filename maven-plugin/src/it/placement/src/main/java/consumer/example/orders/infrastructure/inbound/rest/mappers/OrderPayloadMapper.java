package consumer.example.orders.infrastructure.inbound.rest.mappers;

import org.jspecify.annotations.NullMarked;

/** The mappers role folder this consumer already adopted, which is what would calibrate a finding. */
@NullMarked
public final class OrderPayloadMapper {
  public OrderPayloadMapper() {}

  public String payload(String label) {
    return label.strip();
  }
}
