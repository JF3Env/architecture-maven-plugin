package consumer.example.platform.application;

public interface UnitOfWork {
  void begin();

  void commit();
}
