package consumer.example.orders.application.mappers;

import consumer.example.orders.api.OrderRef;
import consumer.example.orders.domain.Order;
import org.mapstruct.Mapper;

@Mapper
public interface OrderRefMapper {
  OrderRef ref(Order order);
}
