package io.github.jf3env.architecture.iosp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IospCreatorLocationTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "new IllegalArgumentException(\"invalid\")",
        "new ArithmeticException(\"overflow\")"
      })
  void jdkExceptionsRequireCustomExceptionSelfFactories(String creation) {
    var report =
        this.analyze(
            "package com.ai.label.probe.domain; class Sample {"
                + " void reject() { throw "
                + creation
                + "; } }");
    assertEquals(1, locations(report));
  }

  private static final String PREFIX =
      """
      package com.ai.label.probe.domain.value;
      final class Product { Product(int value) {} }
      """;

  private static long locations(net.sourceforge.pmd.reporting.Report report) {
    return report.getViolations().stream()
        .filter(v -> v.getDescription().contains("IOSP_CREATOR_LOCATION"))
        .count();
  }

  @ParameterizedTest
  @ValueSource(strings = {"ProductFactory", "ProductMapper", "ProductBuilder"})
  void permittedCreatorClassesCanConstructApplicationProducts(String owner) {
    var report =
        this.analyze(
            PREFIX
                + "class "
                + owner
                + " { Product create(int value) { return new Product(value); } }");
    assertTrue(report.getViolations().isEmpty(), report.getViolations().toString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ProductService",
        "ProductHelper",
        "ProductValue",
        "FactoryHelper",
        "ProductFactoryImpl"
      })
  void aHelperOrFactoryLikePrefixIsNotAnAuthorizedCreator(String owner) {
    var report =
        this.analyze(
            PREFIX + "class " + owner + " { Product create() { return new Product(1); } }");
    assertEquals(1, locations(report));
  }

  @ParameterizedTest
  @ValueSource(strings = {"ProductFactory", "ProductMapper", "ProductBuilder"})
  void constructorReferencesFollowTheSameOwnerRule(String owner) {
    var permitted =
        this.analyze(
            PREFIX
                + "class "
                + owner
                + " { java.util.function.IntFunction<Product> constructor() { return Product::new; } }");
    assertEquals(0, locations(permitted));
    var forbidden =
        this.analyze(
            PREFIX
                + "class Ordinary {"
                + " java.util.function.IntFunction<Product> constructor() { return Product::new; } }");
    assertEquals(1, locations(forbidden));
  }

  @Test
  void domainProducerCanWireABeanAndItsConstructorDependenciesTogether() {
    var report =
        this.analyze(
            """
        package com.ai.label.probe.infrastructure.wiring;
        class Strategy {}
        class Service { Service(Strategy strategy) {} }
        class WorkspaceProducer {
          @jakarta.enterprise.inject.Produces
          Service service() { return new Service(new Strategy()); }
        }
        """);
    assertTrue(report.getViolations().isEmpty(), report.getViolations().toString());
  }

  @Test
  void producerSuffixAloneDoesNotAuthorizeConstruction() {
    var report =
        this.analyze(
            PREFIX + "class WorkspaceProducer { Product create() { return new Product(1); } }");
    assertEquals(1, locations(report));
  }

  @Test
  void producerWiringStillRejectsComputationAndProductUse() {
    var report =
        this.analyze(
            """
        package com.ai.label.probe.infrastructure.wiring;
        class Strategy { Strategy(int value) {} }
        class Service { Service(Strategy strategy) {} }
        class WorkspaceProducer {
          @jakarta.enterprise.inject.Produces
          Service service(int value) { return new Service(new Strategy(value * 2)); }
          void consume(java.util.List<Service> services) { services.add(new Service(new Strategy(1))); }
        }
        """);
    assertTrue(
        report.getViolations().stream().anyMatch(v -> v.getDescription().contains("IOSP_MIXED")));
    assertTrue(
        report.getViolations().stream()
            .anyMatch(v -> v.getDescription().contains("IOSP_CONSTRUCTION_USE")));
  }

  @Test
  void aPrivateConstructionHelperStillNeedsACreatorClass() {
    var report =
        this.analyze(
            PREFIX
                + """
        class ProductService {
          Product create() { return helper(); }
          private Product helper() { return new Product(1); }
        }
        """);
    assertEquals(1, locations(report));
    assertTrue(
        report.getViolations().stream().anyMatch(v -> v.getDescription().contains("#helper")));
  }

  @Test
  void enclosingACodeHelperInAFactoryDoesNotAuthorizeItsOwnConstruction() {
    var report =
        this.analyze(
            PREFIX
                + """
        class ProductFactory {
          static class Helper { Product create() { return new Product(1); } }
        }
        """);
    assertEquals(1, locations(report));
  }

  @Test
  void applicationExceptionsAreApplicationClassesToo() {
    var report =
        this.analyze(
            """
        package com.ai.label.probe.domain.exceptions;
        class Problem extends RuntimeException {}
        class Validation {
          void reject() { throw new Problem(); }
          java.util.function.Supplier<Problem> error() { return Problem::new; }
        }
        """);
    assertEquals(2, locations(report));
  }

  @Test
  void libraryClassesAndConstructorChainingAreNotApplicationInstantiationSites() {
    var report =
        this.analyze(
            """
        package com.ai.label.probe.domain.value;
        class Base { Base(String text) {} }
        class Child extends Base {
          Child() { this("default"); }
          Child(String text) { super(text); }
          Object object() { return new Object(); }
          java.util.List<String> buffer() { return new java.util.ArrayList<>(); }
        }
        """);
    assertEquals(0, locations(report));
  }

  @Test
  void aFactorySuffixDoesNotPermitMixingConstructionWithComputationOrUse() {
    var report =
        this.analyze(
            PREFIX
                + """
        class ProductFactory {
          Product calculated(int value) { return new Product(value * 2); }
          void consumed(java.util.List<Product> products) { products.add(new Product(1)); }
        }
        """);
    assertEquals(0, locations(report));
    assertTrue(
        report.getViolations().stream().anyMatch(v -> v.getDescription().contains("IOSP_MIXED")));
    assertTrue(
        report.getViolations().stream()
            .anyMatch(v -> v.getDescription().contains("IOSP_CONSTRUCTION_USE")));
  }

  @Test
  void verifiedInventoryNotPackageSpellingDeterminesApplicationOwnership() {
    var name = "com.ai.label.probe.domain.external.LibraryValue";
    var type = ClassDesc.of(name);
    var constructor = MethodTypeDesc.ofDescriptor("()V");
    var bytes =
        ClassFile.of()
            .build(
                type,
                builder -> {
                  builder.withFlags(ClassFile.ACC_PUBLIC);
                  builder.withMethodBody(
                      "<init>",
                      constructor,
                      ClassFile.ACC_PUBLIC,
                      code ->
                          code.aload(0)
                              .invokespecial(
                                  ClassDesc.of("java.lang.Object"), "<init>", constructor)
                              .return_());
                });
    var resource = name.replace('.', '/') + ".class";
    var loader =
        new ClassLoader(this.getClass().getClassLoader()) {
          @Override
          public InputStream getResourceAsStream(String requested) {
            return resource.equals(requested)
                ? new ByteArrayInputStream(bytes)
                : super.getResourceAsStream(requested);
          }
        };
    var source =
        """
        package com.ai.label.probe.domain;
        import com.ai.label.probe.domain.external.LibraryValue;
        class Consumer { LibraryValue create() { return new LibraryValue(); } }
        """;
    var consumer = "com.ai.label.probe.domain.Consumer";
    var external = IospAnalysis.analyzeSource(source, loader, "com.ai.label", Set.of(consumer));
    assertTrue(external.getProcessingErrors().isEmpty(), external.getProcessingErrors().toString());
    assertTrue(external.getViolations().isEmpty(), external.getViolations().toString());
    var owned = IospAnalysis.analyzeSource(source, loader, "com.ai.label", Set.of(consumer, name));
    assertTrue(owned.getProcessingErrors().isEmpty(), owned.getProcessingErrors().toString());
    assertEquals(1, locations(owned));
  }

  @Test
  void aComposedFactoryStrategyKeepsCreationOutOfTheService() {
    var report =
        this.analyze(
            PREFIX
                + """
        interface ProductFactory { Product create(int value); }
        final class DefaultProductFactory implements ProductFactory {
          public Product create(int value) { return new Product(value); }
        }
        final class AlternateProductFactory implements ProductFactory {
          public Product create(int value) { return new Product(42); }
        }
        final class ProductService {
          private final ProductFactory factory;
          ProductService(ProductFactory factory) { this.factory = factory; }
          Product run(int value) { return factory.create(value); }
        }
        """);
    assertTrue(report.getViolations().isEmpty(), report.getViolations().toString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Object create() { return new Object() { int field; }; }",
        "Object create() { class Local { } return new Local(); }"
      })
  void anonymousAndLocalApplicationClassesNeedAnAuthorizedCreator(String member) {
    var report =
        this.analyze("package com.ai.label.probe.domain.value; class Ordinary { " + member + " }");
    assertEquals(1, locations(report));
    var permitted =
        this.analyze(
            "package com.ai.label.probe.domain.value; class OrdinaryFactory { " + member + " }");
    assertEquals(0, locations(permitted));
  }

  private net.sourceforge.pmd.reporting.Report analyze(String source) {
    var report =
        IospAnalysis.analyzeSource(source, this.getClass().getClassLoader(), "com.ai.label");
    assertTrue(report.getProcessingErrors().isEmpty(), report.getProcessingErrors().toString());
    assertTrue(
        report.getConfigurationErrors().isEmpty(), report.getConfigurationErrors().toString());
    assertFalse(
        report.getViolations().stream()
            .anyMatch(v -> v.getDescription().contains("IOSP_UNRESOLVED")),
        report.getViolations().toString());
    return report;
  }
}
