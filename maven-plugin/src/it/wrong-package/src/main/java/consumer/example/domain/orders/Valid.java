package consumer.example.domain.orders;

public final class Valid {
  private final int offset;

  public Valid(int offset) {
    this.offset = Math.abs(offset);
  }

  public int value() {
    return this.offset + 1;
  }
}
