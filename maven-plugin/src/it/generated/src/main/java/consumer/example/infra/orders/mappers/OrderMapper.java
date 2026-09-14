package consumer.example.infra.orders.mappers;

import consumer.example.domain.orders.value.OrderValue;
import consumer.example.infra.orders.dto.OrderDto;
import org.mapstruct.Mapper;

@Mapper
public interface OrderMapper {
  OrderDto toDto(OrderValue value);
}
