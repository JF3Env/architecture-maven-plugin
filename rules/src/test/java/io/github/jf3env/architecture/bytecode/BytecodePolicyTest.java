package io.github.jf3env.architecture.bytecode;

import static io.github.jf3env.architecture.bytecode.CompiledArchitectureFixture.accepts;
import static io.github.jf3env.architecture.bytecode.CompiledArchitectureFixture.compile;
import static io.github.jf3env.architecture.bytecode.CompiledArchitectureFixture.rejects;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BytecodePolicyTest {
  private static final String BASE = "org.acme";
  @TempDir Path directory;

  @Test
  void consumerConfigurationCannotLeakBetweenCatalogs() throws IOException {
    var first = new BytecodeRuleCatalog(TestPolicies.orders("com.first"), List.of()).rules();
    var second = new BytecodeRuleCatalog(TestPolicies.orders("org.second"), List.of()).rules();
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
  void theDerivedAuthorityFollowsAConsumerWithoutAnyConfiguration() throws IOException {
    var classes =
        compile(
            directory,
            Map.of(
                aggregate(),
                "public class Order {}",
                repository(),
                "public interface OrderRepository {}",
                service(),
                "public class OrderService { "
                    + aggregate()
                    + " order; "
                    + repository()
                    + " repository; }"));
    var policy = TestPolicies.orders(BASE);
    assertEquals(
        List.of(
            new DomainAuthority(
                "orders", List.of(aggregate()), List.of(repository()), List.of(repository()))),
        PersistenceAuthority.derive(policy, classes));
    var rules =
        new BytecodeRuleCatalog(policy, PersistenceAuthority.derive(policy, classes)).rules();
    accepts(rules.get("DOMAINS_HAVE_A_SINGLE_PERSISTENCE_AUTHORITY"), classes);
    accepts(rules.get("SERVICES_USE_THEIR_DOMAIN_REPOSITORY"), classes);
    accepts(rules.get("SERVICES_USE_THEIR_DOMAIN_AGGREGATE"), classes);
  }

  @Test
  void aServiceBypassingItsAggregateRootIsRejected() throws IOException {
    var classes =
        compile(
            directory,
            Map.of(
                aggregate(), "public class Order {}",
                repository(), "public interface OrderRepository {}",
                service(), "public class OrderService { " + repository() + " repository; }"));
    var policy = TestPolicies.orders(BASE);
    var rule =
        new BytecodeRuleCatalog(policy, PersistenceAuthority.derive(policy, classes))
            .rules()
            .get("SERVICES_USE_THEIR_DOMAIN_AGGREGATE");
    rejects(rule, classes, service(), "without the domain aggregate root", aggregate());
  }

  @Test
  void renamedDomainKeepsProducerAndStructuralOwnershipContracts() throws IOException {
    var classes =
        compile(
            directory,
            Map.of(
                repository(),
                "public interface OrderRepository {}",
                BASE + ".infra.domains.producers.OrdersProducer",
                "public class OrdersProducer { @jakarta.enterprise.inject.Produces public String label() { return \"orders\"; } }"));
    assertTrue(new DomainProducerRules(BASE).violations(classes).isEmpty());
    assertTrue(!new DomainProducerRules("com.other").violations(classes).isEmpty());
    var value = BASE + ".domain.orders.value.OrderValue";
    var owner = service();
    rejects(
        new BytecodeRuleCatalog(TestPolicies.orders(BASE), List.of())
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
  void policiesRejectInvalidBasePackages() {
    assertThrows(IllegalArgumentException.class, () -> new BytecodePolicy(null));
    assertThrows(IllegalArgumentException.class, () -> new BytecodePolicy(""));
    assertThrows(IllegalArgumentException.class, () -> new BytecodePolicy("org..acme"));
    assertThrows(IllegalArgumentException.class, () -> new BytecodePolicy("1org.acme"));
  }

  private static String aggregate() {
    return BASE + ".domain.orders.aggregate.Order";
  }

  private static String repository() {
    return BASE + ".domain.orders.OrderRepository";
  }

  private static String service() {
    return BASE + ".domain.orders.services.read.OrderService";
  }
}
