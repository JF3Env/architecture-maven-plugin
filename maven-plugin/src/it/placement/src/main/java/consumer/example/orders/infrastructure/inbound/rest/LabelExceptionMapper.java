package consumer.example.orders.infrastructure.inbound.rest;

import org.jspecify.annotations.NullMarked;

/** A provider, not a mapper: the adopted mappers folder next to it must not attract it. */
@NullMarked
public final class LabelExceptionMapper implements ExceptionMapper<IllegalStateException> {
  public LabelExceptionMapper() {}

  @Override
  public String map(IllegalStateException failure) {
    return failure.getMessage() == null ? "" : failure.getMessage();
  }
}
