package consumer.example.infra.orders.rest;

import consumer.example.domain.orders.services.read.OrderService;
import consumer.example.infra.orders.rest.dto.OrderDto;
import consumer.example.infra.orders.rest.mappers.OrderRestMapper;

public final class OrderResource {
  private final OrderService service;
  private final OrderRestMapper mapper;

  public OrderResource(OrderService service, OrderRestMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  public OrderDto read() {
    return mapper.dto(service.read());
  }
}
