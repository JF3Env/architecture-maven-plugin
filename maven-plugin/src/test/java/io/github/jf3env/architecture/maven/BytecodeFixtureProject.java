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
      case "owners" ->
          replace(
              sources.resolve("domain/orders/services/read/OrderService.java"),
              "  public OrderResult read() {",
              "  public OrderResult extra() { return new OrderResult(2); }\n  public OrderResult read() {");
      case "generated" ->
          replace(
              sources.resolve("infra/orders/rest/mappers/OrderRestMapper.java"),
              "  OrderDto dto(OrderResult result);",
              "  OrderDto dto(OrderResult result);\n  OrderResult illegal(OrderDto dto);");
      case "factory" -> relocateFactory(sources);
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

  private void relocateFactory(Path sources) throws IOException {
    var oldPackage = "consumer.example.domain.orders.services.read.factory";
    var newPackage = "consumer.example.domain.orders.services.other.factory";
    var oldDirectory = sources.resolve("domain/orders/services/read/factory");
    var destination = sources.resolve("domain/orders/services/other/factory");
    Files.createDirectories(destination);
    var contract = oldDirectory.resolve("OrderFactory.java");
    Files.writeString(
        destination.resolve("OrderFactory.java"),
        Files.readString(contract).replace(oldPackage, newPackage));
    Files.delete(contract);
    Files.writeString(
        destination.resolve("package-info.java"),
        Files.readString(oldDirectory.resolve("package-info.java"))
            .replace(oldPackage, newPackage));
    replace(
        sources.resolve("domain/orders/services/read/OrderService.java"),
        oldPackage + ".OrderFactory",
        newPackage + ".OrderFactory");
    replace(
        oldDirectory.resolve("StandardOrderFactory.java"),
        "import consumer.example.domain.orders.services.read.result.OrderResult;",
        "import consumer.example.domain.orders.services.read.result.OrderResult;\nimport "
            + newPackage
            + ".OrderFactory;");
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
