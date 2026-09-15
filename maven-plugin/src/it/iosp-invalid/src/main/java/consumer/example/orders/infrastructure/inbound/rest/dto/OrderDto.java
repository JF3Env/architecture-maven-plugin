package consumer.example.orders.infrastructure.inbound.rest.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@Getter
@Setter
public final class OrderDto {
  private long count;
}
