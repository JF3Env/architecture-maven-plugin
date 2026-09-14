package consumer.example.persistence.orders.mappers;

import consumer.example.domain.orders.services.read.result.OrderResult;
import consumer.example.persistence.orders.entities.OrderEntity;
import org.mapstruct.Mapper;

@Mapper
public interface OrderPersistenceMapper {
  OrderEntity entity(OrderResult result);
}
