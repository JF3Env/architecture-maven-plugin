package consumer.example.orders.api.events;

import consumer.example.orders.api.OrderRef;
import consumer.example.platform.domain.IntegrationEvent;

public record OrderPlaced(OrderRef order) implements IntegrationEvent {}
