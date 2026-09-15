package consumer.example.billing.domain;

import lombok.NoArgsConstructor;

@NoArgsConstructor
public final class InvoicePolicy {
  private final long factor = 2;

  public long total(long count) {
    return count * factor;
  }
}
