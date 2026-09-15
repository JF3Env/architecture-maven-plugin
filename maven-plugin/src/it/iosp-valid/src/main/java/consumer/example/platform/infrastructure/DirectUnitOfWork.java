package consumer.example.platform.infrastructure;

import consumer.example.platform.application.UnitOfWork;
import lombok.NoArgsConstructor;

@NoArgsConstructor
public final class DirectUnitOfWork implements UnitOfWork {
  @Override
  public void begin() {}

  @Override
  public void commit() {}
}
