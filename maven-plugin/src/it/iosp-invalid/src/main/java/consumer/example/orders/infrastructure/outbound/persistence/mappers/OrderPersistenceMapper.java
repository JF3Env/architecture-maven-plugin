package consumer.example.orders.infrastructure.outbound.persistence.mappers;

import consumer.example.orders.domain.Order;
import consumer.example.orders.infrastructure.outbound.persistence.entities.OrderEntity;
import org.mapstruct.Mapper;

@Mapper
public interface OrderPersistenceMapper {
  OrderEntity entity(Order order);
}
