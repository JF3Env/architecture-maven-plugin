package consumer.example.orders.infrastructure.inbound.rest.mappers;

import consumer.example.orders.domain.OrderValue;
import consumer.example.orders.infrastructure.inbound.rest.dto.OrderDto;
import org.mapstruct.Mapper;

@Mapper
public interface OrderMapper {
  OrderDto toDto(OrderValue value);
}
