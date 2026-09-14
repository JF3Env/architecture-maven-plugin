package consumer.example.infra.orders.rest;

import consumer.example.domain.orders.services.read.OrderService;
import consumer.example.infra.orders.rest.dto.OrderDto;
import consumer.example.infra.orders.rest.mappers.OrderRestMapper;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class OrderResource {
  private final OrderService service;
  private final OrderRestMapper mapper;

  public OrderDto read() {
    return mapper.dto(service.read());
  }
}
