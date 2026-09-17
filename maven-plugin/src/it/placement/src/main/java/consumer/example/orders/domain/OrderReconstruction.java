package consumer.example.orders.domain;

import org.jspecify.annotations.NullMarked;

/** A reconstruction port: its name announces the factory role while it sits in the root folder. */
@NullMarked
public interface OrderReconstruction {
  String label();
}
