package io.github.jf3env.architecture.maven;

import io.github.jf3env.architecture.bytecode.BytecodeContracts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Real Maven project mutations; the child process resolves only the installed plugin artifacts. */
public final class BytecodeFixtureProject {
  public BytecodeFixtureProject() {}

  public void prepare(Path target, String version, String scenario) throws IOException {
    var original = target.resolve("../../../src/it/bytecode-valid").normalize();
    try (var files = Files.walk(original.resolve("src"))) {
      for (var file : files.toList()) {
        var destination = target.resolve(original.relativize(file));
        if (Files.isDirectory(file)) Files.createDirectories(destination);
        else Files.copy(file, destination);
      }
    }
    Files.writeString(
        target.resolve("pom.xml"),
        Files.readString(original.resolve("pom.xml")).replace("@project.version@", version));
    var sources = target.resolve("src/main/java/consumer/example");
    switch (scenario) {
      case "owners" -> {
        var producer = sources.resolve("orders/infrastructure/wiring/OrdersProducer.java");
        replace(
            producer,
            "import consumer.example.orders.application.PlaceOrderHandler;",
            "import consumer.example.orders.application.PlaceOrderHandler;\n"
                + "import consumer.example.orders.domain.Order;");
        replace(
            producer,
            "  @Produces\n",
            "  public Order sample() {\n    return new Order(1);\n  }\n\n  @Produces\n");
      }
      case "generated" -> {
        var mapper =
            sources.resolve("orders/infrastructure/inbound/rest/mappers/OrderRestMapper.java");
        replace(
            mapper,
            "import consumer.example.orders.api.OrderRef;",
            "import consumer.example.orders.api.OrderRef;\n"
                + "import consumer.example.orders.domain.Order;");
        replace(
            mapper,
            "  OrderDto dto(OrderRef ref);",
            "  OrderDto dto(OrderRef ref);\n\n  Order order(OrderDto dto);");
      }
      case "boundary" -> {
        var handler = sources.resolve("billing/application/InvoiceOrderHandler.java");
        replace(
            handler,
            "import consumer.example.orders.api.Orders;",
            "import consumer.example.orders.api.Orders;\n"
                + "import consumer.example.orders.domain.Order;");
        replace(
            handler,
            "  public long invoice(long count) {",
            "  public long count(Order order) {\n    return order.getCount();\n  }\n\n"
                + "  public long invoice(long count) {");
      }
      case "producer" ->
          Files.writeString(
              sources.resolve("orders/infrastructure/wiring/ExtraProducer.java"),
              """
              package consumer.example.orders.infrastructure.wiring;

              import jakarta.enterprise.inject.Produces;
              import lombok.NoArgsConstructor;

              @NoArgsConstructor
              public final class ExtraProducer {
                @Produces
                public String label() {
                  return "orders";
                }
              }
              """);
      case "corrupt" -> {
        replace(
            target.resolve("pom.xml"),
            "<execution><id>source</id><goals><goal>check</goal></goals></execution>",
            "");
        replace(
            target.resolve("pom.xml"),
            "<build><plugins>",
            """
            <build><plugins>
            <plugin><artifactId>maven-antrun-plugin</artifactId><version>3.2.0</version>
              <executions><execution><phase>process-classes</phase><goals><goal>run</goal></goals>
                <configuration><target><echo file="${project.build.outputDirectory}/Broken.class">broken</echo></target></configuration>
              </execution></executions>
            </plugin>
            """);
      }
      default -> throw new IllegalArgumentException("Unknown fixture scenario: " + scenario);
    }
  }

  private void replace(Path file, String before, String after) throws IOException {
    var source = Files.readString(file);
    if (!source.contains(before) || before.equals(after))
      throw new IllegalStateException("Fixture did not change " + file);
    Files.writeString(file, source.replace(before, after));
  }

  public void verify(Path project, String status, List<String> witnesses) throws IOException {
    var report = Files.readString(project.resolve("target/architecture/bytecode-report.txt"));
    if (!report.startsWith(status + "\n")) throw new IllegalStateException(report);
    for (var witness : witnesses) {
      if (!report.contains(witness))
        throw new IllegalStateException("Missing witness " + witness + ":\n" + report);
    }
    if (!status.equals("ANALYSIS_ERROR")) {
      var executed =
          report.lines().filter(line -> line.startsWith("rules=")).findFirst().orElseThrow();
      for (var identity : new BytecodeContracts().requiredRules()) {
        if (!executed.contains(identity))
          throw new IllegalStateException("Required rule was not executed: " + identity);
      }
      if (report.contains("failed to check any classes")
          || report.contains("Unresolved class evidence")) {
        throw new IllegalStateException("Unrelated diagnostic: " + report);
      }
    }
    var log = Files.readString(project.resolve("build.log"));
    if (log.contains("COMPILATION ERROR") || log.contains("--- surefire:")) {
      throw new IllegalStateException("The fixture must reach the gate before Surefire: " + log);
    }
  }
}
