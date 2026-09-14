package consumer.example.persistence.orders.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class OrderEntity {
  @Id private int count;

  public int getCount() { return count; }
  public void setCount(int count) { this.count = count; }
}
