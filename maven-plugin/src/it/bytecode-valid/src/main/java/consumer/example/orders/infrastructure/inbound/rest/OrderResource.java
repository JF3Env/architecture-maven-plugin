package consumer.example.orders.infrastructure.inbound.rest;

import consumer.example.orders.application.PlaceOrderHandler;
import consumer.example.orders.infrastructure.inbound.rest.dto.OrderDto;
import consumer.example.orders.infrastructure.inbound.rest.mappers.OrderRestMapper;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class OrderResource {
  private final PlaceOrderHandler handler;
  private final OrderRestMapper mapper;

  public OrderDto place(long count) {
    return mapper.dto(handler.place(count));
  }
}
