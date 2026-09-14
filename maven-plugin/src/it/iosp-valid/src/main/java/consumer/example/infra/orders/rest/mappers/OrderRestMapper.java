package consumer.example.infra.orders.rest.mappers;

import consumer.example.domain.orders.services.read.result.OrderResult;
import consumer.example.infra.orders.rest.dto.OrderDto;
import org.mapstruct.Mapper;

@Mapper
public interface OrderRestMapper {
  OrderDto dto(OrderResult result);
}
