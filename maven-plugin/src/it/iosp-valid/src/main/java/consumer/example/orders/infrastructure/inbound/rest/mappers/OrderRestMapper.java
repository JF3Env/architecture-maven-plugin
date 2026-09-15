package consumer.example.orders.infrastructure.inbound.rest.mappers;

import consumer.example.orders.api.OrderRef;
import consumer.example.orders.infrastructure.inbound.rest.dto.OrderDto;
import org.mapstruct.Mapper;

@Mapper
public interface OrderRestMapper {
  OrderDto dto(OrderRef ref);
}
