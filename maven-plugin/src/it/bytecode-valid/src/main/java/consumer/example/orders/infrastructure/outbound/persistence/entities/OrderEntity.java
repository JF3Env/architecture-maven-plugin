package consumer.example.orders.infrastructure.outbound.persistence.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@NoArgsConstructor
@Getter
@Setter
public class OrderEntity {
  @Id private long count;
}
