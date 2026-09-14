package io.github.jf3env.architecture.bytecode;

import static io.github.jf3env.architecture.bytecode.CompiledArchitectureFixture.accepts;
import static io.github.jf3env.architecture.bytecode.CompiledArchitectureFixture.compile;
import static io.github.jf3env.architecture.bytecode.CompiledArchitectureFixture.rejects;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BytecodePolicyTest {
  @TempDir Path directory;

  @Test
  void consumerConfigurationCannotLeakBetweenCatalogs() throws IOException {
    var first = new BytecodeRuleCatalog(TestPolicies.orders("com.first")).rules();
    var second = new BytecodeRuleCatalog(TestPolicies.orders("org.second")).rules();
    var classes =
        compile(
            directory,
            Map.of("com.first.domain.orders.value.OrderValue", "public class OrderValue {}"));
    accepts(first.get("CLASSES_ARE_GROUPED_BY_LAYER_AND_DOMAIN"), classes);
    rejects(
        second.get("CLASSES_ARE_GROUPED_BY_LAYER_AND_DOMAIN"),
        classes,
        "com.first.domain.orders.value.OrderValue");
    accepts(first.get("CLASSES_ARE_GROUPED_BY_LAYER_AND_DOMAIN"), classes);
  }

  @Test
  void renamedAuthorityStillRequiresTheAggregateAndRootRepository() throws IOException {
    var base = "org.acme";
    var policy = TestPolicies.orders(base);
    var service = base + ".domain.orders.services.read.OrderService";
    var aggregate = policy.domain().aggregate();
    var repository = policy.domain().repository();
    var definitions =
        Map.of(
            aggregate,
            "public class Order {}",
            repository,
            "public interface OrderRepository {}",
            service,
            "public class OrderService { "
                + aggregate
                + " order; "
                + repository
                + " repository; }");
    var rule =
        new BytecodeRuleCatalog(policy)
            .rules()
            .get("ASSET_CHANGING_SERVICES_USE_WORKSPACE_AUTHORITY");
    accepts(rule, compile(directory, definitions));
    var invalid =
        compile(
            directory,
            Map.of(
                aggregate,
                definitions.get(aggregate),
                repository,
                definitions.get(repository),
                service,
                "public class OrderService { " + aggregate + " order; }"));
    var result = rule.evaluate(invalid);
    assertTrue(result.hasViolation());
    assertTrue(result.getFailureReport().toString().contains(service));
    assertTrue(result.getFailureReport().toString().contains(repository));
  }

  @Test
  void renamedDomainKeepsProducerAndStructuralOwnershipContracts() throws IOException {
    var base = "org.acme";
    var classes =
        compile(
            directory,
            Map.of(
                base + ".domain.orders.OrderRepository",
                "public interface OrderRepository {}",
                base + ".infra.domains.producers.OrdersProducer",
                "public class OrdersProducer { @jakarta.enterprise.inject.Produces public String label() { return \"orders\"; } }"));
    assertTrue(new DomainProducerRules(base).violations(classes).isEmpty());
    assertTrue(!new DomainProducerRules("com.other").violations(classes).isEmpty());
    var value = base + ".domain.orders.value.OrderValue";
    var owner = base + ".domain.orders.services.read.OrderService";
    rejects(
        new BytecodeRuleCatalog(TestPolicies.orders(base))
            .rules()
            .get("DOMAIN_COMPONENTS_FOLLOW_STRUCTURAL_OWNERS"),
        compile(
            directory,
            Map.of(
                value,
                "public class OrderValue {}",
                owner,
                "public class OrderService { " + value + " value; }")),
        value,
        owner + ".value",
        ".services.read.value");
  }

  @Test
  void policiesCannotSilentlyDropAuthorityOrMisidentifyItsDomain() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DomainPolicy(
                "orders",
                "assets",
                "org.acme.domain.orders.aggregate.Order",
                "org.acme.domain.orders.OrderRepository",
                Set.of(),
                "AssetRepository"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BytecodePolicy("org.acme", TestPolicies.reference().domain()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BytecodePolicy("org..acme", TestPolicies.orders("org.acme").domain()));
    assertThrows(IllegalArgumentException.class, () -> new BytecodePolicy("org.acme", null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DomainPolicy(
                "orders",
                "orders",
                "org.acme.Order",
                "org.acme.OrderRepository",
                Set.of("OrderService"),
                "AssetRepository"));
  }
}
