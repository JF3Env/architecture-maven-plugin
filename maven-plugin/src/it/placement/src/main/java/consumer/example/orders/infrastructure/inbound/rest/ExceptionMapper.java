package consumer.example.orders.infrastructure.inbound.rest;

import org.jspecify.annotations.NullMarked;

/** The provider contract, named as JAX-RS names it; the detector matches the simple name. */
@NullMarked
public interface ExceptionMapper<E extends Exception> {
  String map(E failure);
}
