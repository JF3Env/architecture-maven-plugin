package io.github.jf3env.architecture.bytecode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LaboratoryArchitectureRulesTest {
  @TempDir Path directory;

  /** A public fixture type must live in a file carrying its own name. */
  private static String fileName(String source, int index) {
    var declaration =
        java.util.regex.Pattern.compile("public\\s+(?:final\\s+)?(?:class|interface)\\s+(\\w+)")
            .matcher(source);
    return declaration.find() ? declaration.group(1) + ".java" : "Fixture" + index + ".java";
  }

  @Test
  void acceptsOneConsumerFactoryCreatingSeveralProducts() throws IOException {
    assertTrue(
        this.check(
                """
        package fixtures.factory;
        class Product {} class Other {}
        public class ConsumerFactory {
          public Object product() { return new Product(); }
          public Object other() { return new Other(); }
        }
        """,
                """
        package fixtures;
        import fixtures.factory.ConsumerFactory;
        class Consumer {
          final ConsumerFactory factory;
          Consumer(ConsumerFactory factory) { this.factory = factory; }
          Object run() { return factory.product(); }
        }
        """)
            .isEmpty());
  }

  @Test
  void rejectsOverloadedConstructors() throws IOException {
    assertTrue(
        this.check("class Product { Product() {} Product(int value) {} }").stream()
            .anyMatch(message -> message.contains("exactly one constructor")));
  }

  @Test
  void rejectsSeveralConstructionOwners() throws IOException {
    assertTrue(
        this.check(
                """
        class Product {}
        class First { Product create() { return new Product(); } }
        class Second { Product create() { return new Product(); } }
        """)
            .stream()
            .anyMatch(message -> message.contains("several construction owners")));
  }

  @Test
  void rejectsSharedFactoryEvenThroughItsContract() throws IOException {
    var contract = "package fixtures.factory; public interface FirstFactory {}";
    var strategy =
        """
        package fixtures.factory;
        public class StandardFirstFactory implements FirstFactory {}
        """;
    var first =
        """
        package fixtures;
        import fixtures.factory.FirstFactory;
        public class First { FirstFactory factory; }
        """;
    assertTrue(this.check(contract, strategy, first).isEmpty());
    var messages =
        this.check(
            contract,
            strategy,
            first,
            """
        package fixtures;
        import fixtures.factory.FirstFactory;
        public class Second { FirstFactory factory; }
        """);
    assertEquals(
        List.of(
            "fixtures.factory.FirstFactory: exactly one consumer is required,"
                + " found [fixtures.First, fixtures.Second]"),
        messages);
  }

  @Test
  void anUnrecognizedContractDoesNotProveSharedFactoryConsumption() throws IOException {
    var messages =
        this.check(
            """
        interface Creator { Product create(); }
        class Product {}
        class FirstFactory implements Creator { public Product create() { return new Product(); } }
        class First { final Creator creator; First(Creator creator) { this.creator = creator; } }
        class Second { final Creator creator; Second(Creator creator) { this.creator = creator; } }
        """);
    assertEquals(
        List.of(
            "fixtures.FirstFactory: factories reside in the factory package of their consumer",
            "fixtures.FirstFactory: exactly one consumer is required, found []"),
        messages);
  }

  @Test
  void constructorReferencesAlsoHaveAConstructionOwner() throws IOException {
    assertTrue(
        this.check(
                """
        class Product {}
        class First { java.util.function.Supplier<Product> create() { return Product::new; } }
        class Second { java.util.function.Supplier<Product> create() { return Product::new; } }
        """)
            .stream()
            .anyMatch(message -> message.contains("several construction owners")));
  }

  @Test
  void superInitializationDoesNotCountAsAnotherInstantiation() throws IOException {
    assertTrue(
        this.check(
                """
        class Parent {}
        class Child extends Parent { Child() { super(); } }
        class Consumer { Parent create() { return new Parent(); } }
        """)
            .isEmpty());
  }

  @Test
  void genericAndArrayFieldsAlsoEstablishFactoryConsumers() throws IOException {
    var violations =
        this.check(
            """
        class FirstFactory {}
        class First { FirstFactory factory; }
        class Second { java.util.List<FirstFactory> factories; }
        class Third { FirstFactory[] factories; }
        """);
    assertTrue(
        violations.stream()
            .anyMatch(
                message ->
                    message.contains("exactly one consumer")
                        && message.contains("fixtures.Second")
                        && message.contains("fixtures.Third")));
  }

  @Test
  void rejectsFactoryNestedInItsProduct() throws IOException {
    assertTrue(
        this.check(
                """
        class Product { static class ConsumerFactory { Product create() { return new Product(); } } }
        class Consumer {
          final Product.ConsumerFactory factory;
          Consumer(Product.ConsumerFactory factory) { this.factory = factory; }
        }
        """)
            .stream()
            .anyMatch(message -> message.contains("factories must be top-level")));
  }

  @Test
  void rejectsUnownedOrMisnamedFactories() throws IOException {
    var messages =
        this.check(
            """
        package fixtures.factory;
        class Product {}
        class OrphanFactory { Product create() { return new Product(); } }
        class WrongFactory {}
        class Consumer { final WrongFactory factory; Consumer(WrongFactory factory) { this.factory = factory; } }
        """);
    assertTrue(messages.stream().anyMatch(message -> message.contains("exactly one consumer")));
    assertTrue(
        messages.stream().anyMatch(message -> message.contains("must be named ConsumerFactory")));
  }

  @Test
  void namesTheContractForTheConsumerWithoutStackingRoleSuffixes() throws IOException {
    assertTrue(
        this.check(
                """
        package fixtures.factory;
        public interface CatalogFactory { Object create(); }
        """,
                """
        package fixtures.factory;
        class Product {}
        class StandardCatalogFactory implements CatalogFactory {
          public Product create() { return new Product(); }
        }
        """,
                """
        package fixtures;
        import fixtures.factory.CatalogFactory;
        class CatalogService {
          final CatalogFactory factory;
          CatalogService(CatalogFactory factory) { this.factory = factory; }
          Object run() { return factory.create(); }
        }
        """)
            .isEmpty());
  }

  @Test
  void rejectsTheConsumerRoleSuffixStackedOntoTheFactoryName() throws IOException {
    assertTrue(
        this.check(
                """
        package fixtures.factory;
        class Product {}
        class CatalogServiceFactory { Product create() { return new Product(); } }
        class CatalogService {
          final CatalogServiceFactory factory;
          CatalogService(CatalogServiceFactory factory) { this.factory = factory; }
        }
        """)
            .stream()
            .anyMatch(message -> message.contains("must be named CatalogFactory")));
  }

  @Test
  void rejectsFactoriesOutsideTheConsumerFactoryPackage() throws IOException {
    assertTrue(
        this.check(
                """
        class Product {}
        class ConsumerFactory { Product create() { return new Product(); } }
        class Consumer {
          final ConsumerFactory factory;
          Consumer(ConsumerFactory factory) { this.factory = factory; }
        }
        """)
            .stream()
            .anyMatch(message -> message.contains("factory package of their consumer")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "fixtures.services.other.factory",
        "fixtures.services.factory",
        "fixtures.services.configuration.extra.factory",
        "fixtures.domain.other.services.configuration.factory"
      })
  void rejectsMisplacedContractsEvenWithoutAnImplementation(String actualPackage)
      throws IOException {
    var messages =
        this.check(
            "package " + actualPackage + "; public interface WorkspaceConfigurationFactory {}",
            """
        package fixtures.services.configuration;
        import %s.WorkspaceConfigurationFactory;
        class WorkspaceConfigurationService { WorkspaceConfigurationFactory factory; }
        """
                .formatted(actualPackage));
    assertEquals(1, messages.size(), messages.toString());
    assertTrue(messages.getFirst().contains("FACTORY_CONSUMER_PACKAGE"), messages.toString());
    assertTrue(
        messages
            .getFirst()
            .contains("fixtures.services.configuration.WorkspaceConfigurationService"));
    assertTrue(messages.getFirst().contains(actualPackage));
    assertTrue(messages.getFirst().contains("fixtures.services.configuration.factory"));
  }

  @Test
  void rejectsMovingOnlyTheContractWhileItsStrategyRemainsInPlace() throws IOException {
    var messages =
        this.check(
            """
        package fixtures.services.other.factory;
        public interface WorkspaceConfigurationFactory { String create(); }
        """,
            """
        package fixtures.services.configuration.factory;
        import fixtures.services.other.factory.WorkspaceConfigurationFactory;
        class StandardWorkspaceConfigurationFactory implements WorkspaceConfigurationFactory {
          public String create() { return "data"; }
        }
        """,
            """
        package fixtures.services.configuration;
        import fixtures.services.other.factory.WorkspaceConfigurationFactory;
        class WorkspaceConfigurationService { WorkspaceConfigurationFactory factory; }
        """);
    assertEquals(1, messages.size(), messages.toString());
    assertTrue(messages.getFirst().contains("FACTORY_CONSUMER_PACKAGE"), messages.toString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "WorkspaceConfigurationFactory factory;",
        "java.util.List<WorkspaceConfigurationFactory> factories;",
        "WorkspaceConfigurationFactory[][] factories;",
        "java.util.function.Supplier<String> work(WorkspaceConfigurationFactory factory) { return factory::create; }"
      })
  void acceptsExactConsumerPackagesAndStructuralUses(String use) throws IOException {
    var messages =
        this.check(
            """
        package fixtures.services.configuration.factory;
        public interface WorkspaceConfigurationFactory { String create(); }
        """,
            """
        package fixtures.services.configuration.factory;
        class StandardWorkspaceConfigurationFactory implements WorkspaceConfigurationFactory, Runnable {
          public String create() { return "data"; }
          public void run() {}
        }
        """,
            """
        package fixtures.services.configuration;
        import fixtures.services.configuration.factory.WorkspaceConfigurationFactory;
        class WorkspaceConfigurationService { %s }
        class Scheduler { Runnable task; }
        """
                .formatted(use));
    assertTrue(messages.isEmpty(), messages.toString());
  }

  @Test
  void rejectsOrphanAndSharedInterfaceContractsWithoutStrategies() throws IOException {
    var messages =
        this.check(
            """
        package fixtures.factory;
        interface OrphanFactory {}
        interface FirstFactory {}
        class First { FirstFactory factory; }
        class Second { FirstFactory factory; }
        """);
    assertEquals(
        2, messages.stream().filter(message -> message.contains("exactly one consumer")).count());
  }

  @Test
  void auxiliaryInterfacesCannotSupplyAnAccidentallyMatchingContractName() throws IOException {
    var messages =
        this.check(
            """
        package fixtures.factory;
        interface CatalogFactory { void run(); }
        interface WrongFactory { String create(); }
        class StandardCatalogFactory implements WrongFactory, CatalogFactory {
          public String create() { return "data"; }
          public void run() {}
        }
        class CatalogService { WrongFactory factory; }
        """);
    assertTrue(
        messages.stream().anyMatch(message -> message.contains("factory contract")),
        messages.toString());
  }

  @Test
  void implementingTheContractDoesNotHideAnAdditionalConsumer() throws IOException {
    var messages =
        this.check(
            """
        package fixtures.factory;
        public interface CatalogFactory {}
        """,
            """
        package fixtures.factory;
        class StandardCatalogFactory implements CatalogFactory {}
        class DelegatingCatalogFactory implements CatalogFactory { CatalogFactory delegate; }
        """,
            """
        package fixtures;
        import fixtures.factory.CatalogFactory;
        class CatalogService { CatalogFactory factory; }
        """);
    assertTrue(
        messages.stream()
            .anyMatch(
                message ->
                    message.contains("exactly one consumer")
                        && message.contains("DelegatingCatalogFactory")
                        && message.contains("CatalogService")),
        messages.toString());
  }

  @Test
  void rejectsDomainProductsCreatedOutsideTheDomain() throws IOException {
    assertTrue(
        this.check(
                "package fixtures.domain.value; public class Amount { public Amount() {} }",
                """
        package fixtures.infra.factory;
        import fixtures.domain.value.Amount;
        class ConsumerFactory { Amount create() { return new Amount(); } }
        class Consumer { final ConsumerFactory factory; Consumer(ConsumerFactory factory) { this.factory = factory; } }
        """)
            .stream()
            .anyMatch(message -> message.contains("domain products are created in the domain")));
  }

  @Test
  void rejectsGeneratedMappersConstructingDomainProducts() throws IOException {
    var violations =
        this.check(
            "package fixtures.domain.value; public class Amount {}",
            """
        package fixtures.infra;
        @org.mapstruct.Mapper interface AmountMapper { fixtures.domain.value.Amount map(); }
        class AmountMapperImpl implements AmountMapper {
          public fixtures.domain.value.Amount map() { return new fixtures.domain.value.Amount(); }
        }
        """);
    assertTrue(
        violations.stream()
            .anyMatch(
                message ->
                    message.contains("domain products") && message.contains("AmountMapperImpl")),
        violations.toString());
  }

  @Test
  void rejectsExternalConstructorReferencesToDomainProducts() throws IOException {
    assertTrue(
        this.check(
                "package fixtures.domain.value; public class Amount {}",
                """
        package fixtures.infra;
        class External {
          java.util.function.Supplier<fixtures.domain.value.Amount> create() {
            return fixtures.domain.value.Amount::new;
          }
        }
        """)
            .stream()
            .anyMatch(message -> message.contains("domain products")));
  }

  @Test
  void implementingADomainInterfaceDoesNotMakeAValueAWiringStrategy() throws IOException {
    assertTrue(
        this.check(
                "package fixtures.domain.value; public interface Value {}",
                """
        package fixtures.domain.value;
        public class Amount implements Value {}
        """,
                """
        package fixtures.infra;
        class External { Object create() { return new fixtures.domain.value.Amount(); } }
        """)
            .stream()
            .anyMatch(message -> message.contains("domain products")));
  }

  @Test
  void acceptsGeneratedMappersConstructingTransportTypes() throws IOException {
    assertTrue(
        this.check(
                """
        package fixtures.infra;
        record ResponseDto(int value) {}
        @org.mapstruct.Mapper interface ResponseMapper { ResponseDto map(); }
        class ResponseMapperImpl implements ResponseMapper {
          public ResponseDto map() { return new ResponseDto(1); }
        }
        """)
            .isEmpty());
  }

  @Test
  void acceptsWiringOfADomainServiceAndTheStrategyBehindItsContract() throws IOException {
    assertTrue(
        this.check(
                "package fixtures.domain.factory; public interface CatalogFactory {}",
                """
        package fixtures.domain.factory;
        public final class StandardCatalogFactory implements CatalogFactory {
          public StandardCatalogFactory() {}
        }
        """,
                """
        package fixtures.domain;
        public final class CatalogService {
          public CatalogService(fixtures.domain.factory.CatalogFactory factory) {}
        }
        """,
                """
        package fixtures.infra;
        import fixtures.domain.CatalogService;
        import fixtures.domain.factory.StandardCatalogFactory;
        class CatalogWiring {
          CatalogService service() { return new CatalogService(new StandardCatalogFactory()); }
        }
        """)
            .stream()
            .noneMatch(message -> message.contains("domain products")));
  }

  @Test
  void requiresAProducerForEveryDomain() throws IOException {
    assertTrue(
        this.check("package com.ai.label.domain.workspace; class Workspace {}").stream()
            .anyMatch(message -> message.contains("exactly one domain producer")));
  }

  @Test
  void acceptsOneProducerWithSeveralBeansForTheDomain() throws IOException {
    assertTrue(
        this.check(
                "package com.ai.label.domain.workspace; class Workspace {}",
                """
        package com.ai.label.infra.domains.producers;
        class WorkspaceProducer {
          @jakarta.enterprise.inject.Produces String label() { return "workspace"; }
          @jakarta.enterprise.inject.Produces Integer count() { return 1; }
        }
        """)
            .isEmpty());
  }

  @Test
  void acceptsDifferentDomainProducersInTheSharedPackage() throws IOException {
    assertTrue(
        this.check(
                "package com.ai.label.domain.workspace; class Workspace {}",
                "package com.ai.label.domain.catalog; class Catalog {}",
                """
                              package com.ai.label.infra.domains.producers;
                              class WorkspaceProducer {
                                @jakarta.enterprise.inject.Produces String workspace() { return "workspace"; }
                              }
                              class CatalogProducer {
                                @jakarta.enterprise.inject.Produces Integer catalog() { return 1; }
                              }
                              """)
            .isEmpty());
  }

  @Test
  void rejectsTheFormerPerDomainProducerPackage() throws IOException {
    assertTrue(
        this.check(
                "package com.ai.label.domain.workspace; class Workspace {}",
                """
        package com.ai.label.infra.workspace.producers;
        class WorkspaceProducer {
          @jakarta.enterprise.inject.Produces String label() { return "workspace"; }
        }
        """)
            .stream()
            .anyMatch(message -> message.contains("producers belong in")));
  }

  @Test
  void rejectsNestedDomainProducers() throws IOException {
    assertTrue(
        this.check(
                "package com.ai.label.domain.workspace; class Workspace {}",
                """
        package com.ai.label.infra.domains.producers;
        class Outer {
          static class WorkspaceProducer {
            @jakarta.enterprise.inject.Produces String label() { return "workspace"; }
          }
        }
        """)
            .stream()
            .anyMatch(message -> message.contains("top-level class named <Domain>Producer")));
  }

  @Test
  void rejectsDuplicateDomainProducers() throws IOException {
    assertTrue(
        this.check(
                "package com.ai.label.domain.workspace; class Workspace {}",
                """
        package com.ai.label.infra.domains.producers;
        class WorkspaceProducer {
          @jakarta.enterprise.inject.Produces String label() { return "workspace"; }
        }
        class Outer {
          static class WorkspaceProducer {
            @jakarta.enterprise.inject.Produces Integer count() { return 1; }
          }
        }
        """)
            .stream()
            .anyMatch(message -> message.contains("found 2")));
  }

  @Test
  void rejectsProducerNamedAfterAService() throws IOException {
    assertTrue(
        this.check(
                "package com.ai.label.domain.workspace; class Workspace {}",
                """
        package com.ai.label.infra.domains.producers;
        class WorkspaceConfigurationServiceProducer {
          @jakarta.enterprise.inject.Produces String label() { return "workspace"; }
        }
        """)
            .stream()
            .anyMatch(message -> message.contains("top-level class named <Domain>Producer")));
  }

  @Test
  void rejectsProducerOutsideItsDomainPackage() throws IOException {
    assertTrue(
        this.check(
                "package com.ai.label.domain.workspace; class Workspace {}",
                """
        package com.ai.label.infra.workspace.factory;
        class WorkspaceProducer {
          @jakarta.enterprise.inject.Produces String label() { return "workspace"; }
        }
        """)
            .stream()
            .anyMatch(
                message ->
                    message.contains("producers belong in com.ai.label.infra.domains.producers")));
  }

  @Test
  void rejectsProducerWithoutCdiMembers() throws IOException {
    assertTrue(
        this.check(
                "package com.ai.label.domain.workspace; class Workspace {}",
                """
        package com.ai.label.infra.domains.producers;
        class WorkspaceProducer {}
        """)
            .stream()
            .anyMatch(message -> message.contains("must declare a CDI @Produces member")));
  }

  @Test
  void rejectsCdiMembersHiddenInAnArbitrarilyNamedClass() throws IOException {
    assertTrue(
        this.check(
                "package com.ai.label.domain.workspace; class Workspace {}",
                """
        package com.ai.label.infra.domains.producers;
        class Wiring {
          @jakarta.enterprise.inject.Produces String label() { return "workspace"; }
        }
        """)
            .stream()
            .anyMatch(message -> message.contains("top-level class named <Domain>Producer")));
  }

  @Test
  void rejectsProducingBeansFromAnotherDomain() throws IOException {
    assertTrue(
        this.check(
                "package com.ai.label.domain.workspace; class Workspace {}",
                """
        package com.ai.label.domain.catalog;
        public class CatalogService {}
        """,
                """
        package com.ai.label.infra.domains.producers;
        class WorkspaceProducer {
          @jakarta.enterprise.inject.Produces
          com.ai.label.domain.catalog.CatalogService catalog() {
            return new com.ai.label.domain.catalog.CatalogService();
          }
        }
        """)
            .stream()
            .anyMatch(message -> message.contains("cannot produce a bean from another domain")));
  }

  @Test
  void validatesCdiProducerFieldsAsWellAsMethods() throws IOException {
    assertTrue(
        this.check(
                "package com.ai.label.domain.workspace; class Workspace {}",
                """
        package com.ai.label.infra.domains.producers;
        class Wiring {
          @jakarta.enterprise.inject.Produces String label = "workspace";
        }
        """)
            .stream()
            .anyMatch(message -> message.contains("top-level class named <Domain>Producer")));
  }

  private List<String> check(String... sources) throws IOException {
    var classes = Files.createTempDirectory(this.directory, "classes-");
    var arguments =
        new java.util.ArrayList<>(
            List.of("--release", "24", "-proc:none", "-d", classes.toString()));
    for (var index = 0; index < sources.length; index++) {
      var source = sources[index];
      var input = this.directory.resolve(fileName(source, index));
      Files.writeString(
          input, source.startsWith("package ") ? source : "package fixtures; " + source);
      arguments.add(input.toString());
    }
    var compiler = ToolProvider.getSystemJavaCompiler();
    var exit = compiler.run(null, null, null, arguments.toArray(String[]::new));
    assertTrue(exit == 0, "fixture must compile");
    var imported = new ClassFileImporter().importPath(classes);
    assertFalse(imported.isEmpty());
    return new ConstructionRules("com.ai.label").violations(imported);
  }
}
