package consumer.example.orders.domain;

import org.jspecify.annotations.NullMarked;

/** No value folder exists here, so this type proves the detector stays silent without evidence. */
@NullMarked
public final class PriceValue {
  private final long amount;

  public PriceValue(long amount) {
    this.amount = amount;
  }

  public long amount() {
    return this.amount;
  }
}
