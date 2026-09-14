package io.github.jf3env.architecture.bytecode;

import static io.github.jf3env.architecture.bytecode.CompiledArchitectureFixture.accepts;
import static io.github.jf3env.architecture.bytecode.CompiledArchitectureFixture.compile;
import static io.github.jf3env.architecture.bytecode.CompiledArchitectureFixture.rejects;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every derived authority rule keeps its positive control and intended negative diagnostic. */
class PersistenceAuthorityRulesTest {
  private static final String BASE = "consumer.example";
  private static final String ORDERS = BASE + ".domain.orders";
  private static final String ASSETS = BASE + ".domain.assets";
  private static final String ORDER = ORDERS + ".aggregate.Order";
  private static final String ORDER_REPOSITORY = ORDERS + ".OrderRepository";
  private static final String ORDER_SERVICE = ORDERS + ".services.read.OrderService";
  private static final String ASSET = ASSETS + ".aggregate.Asset";
  private static final String ASSET_REPOSITORY = ASSETS + ".AssetRepository";
  private static final String ASSET_SERVICE = ASSETS + ".services.read.AssetService";
  @TempDir Path directory;

  private Map<String, ArchRule> rulesFor(JavaClasses classes) {
    var policy = TestPolicies.orders(BASE);
    return new BytecodeRuleCatalog(policy, PersistenceAuthority.derive(policy, classes)).rules();
  }

  private Map<String, String> completeDomain(String aggregate, String repository, String service) {
    return Map.of(
        aggregate,
        "public class " + simple(aggregate) + " {}",
        repository,
        "public interface " + simple(repository) + " {}",
        service,
        "public class "
            + simple(service)
            + " { "
            + repository
            + " repository; "
            + aggregate
            + " state; }");
  }

  @Test
  void everyDomainMayDeclareItsOwnCompleteAuthority() throws IOException {
    var classes =
        compile(
            directory,
            merge(
                completeDomain(ORDER, ORDER_REPOSITORY, ORDER_SERVICE),
                completeDomain(ASSET, ASSET_REPOSITORY, ASSET_SERVICE)));
    var rules = rulesFor(classes);
    accepts(rules.get("DOMAINS_HAVE_A_SINGLE_PERSISTENCE_AUTHORITY"), classes);
    accepts(rules.get("SERVICES_USE_THEIR_DOMAIN_REPOSITORY"), classes);
    accepts(rules.get("SERVICES_USE_THEIR_DOMAIN_AGGREGATE"), classes);
  }

  @Test
  void aComputationalDomainWithoutAnAuthorityIsAccepted() throws IOException {
    var classes =
        compile(
            directory,
            Map.of(
                ORDERS + ".value.OrderValue",
                "public class OrderValue {}",
                ORDER_SERVICE,
                "public class OrderService { " + ORDERS + ".value.OrderValue value; }"));
    var rules = rulesFor(classes);
    accepts(rules.get("DOMAINS_HAVE_A_SINGLE_PERSISTENCE_AUTHORITY"), classes);
    accepts(rules.get("SERVICES_USE_THEIR_DOMAIN_REPOSITORY"), classes);
    accepts(rules.get("SERVICES_USE_THEIR_DOMAIN_AGGREGATE"), classes);
  }

  @Test
  void severalAggregateRootsAreRejected() throws IOException {
    var other = ORDERS + ".aggregate.OtherOrder";
    var classes =
        compile(
            directory,
            merge(
                completeDomain(ORDER, ORDER_REPOSITORY, ORDER_SERVICE),
                Map.of(other, "public class OtherOrder {}")));
    rejects(
        rulesFor(classes).get("DOMAINS_HAVE_A_SINGLE_PERSISTENCE_AUTHORITY"),
        classes,
        ORDER,
        other,
        "exactly one aggregate root is required");
  }

  @Test
  void severalRepositoryInterfacesAreRejected() throws IOException {
    var second = ORDERS + ".services.read.ReadRepository";
    var classes =
        compile(
            directory,
            merge(
                completeDomain(ORDER, ORDER_REPOSITORY, ORDER_SERVICE),
                Map.of(second, "public interface ReadRepository {}")));
    rejects(
        rulesFor(classes).get("DOMAINS_HAVE_A_SINGLE_PERSISTENCE_AUTHORITY"),
        classes,
        ORDER_REPOSITORY,
        second,
        "exactly one root repository is required");
  }

  @Test
  void aMisplacedRepositoryIsRejected() throws IOException {
    var misplaced = ORDERS + ".services.read.ReadRepository";
    var classes =
        compile(
            directory,
            Map.of(
                ORDER,
                "public class Order {}",
                misplaced,
                "public interface ReadRepository {}",
                ORDER_SERVICE,
                "public class OrderService { "
                    + misplaced
                    + " repository; "
                    + ORDER
                    + " state; }"));
    rejects(
        rulesFor(classes).get("DOMAINS_HAVE_A_SINGLE_PERSISTENCE_AUTHORITY"),
        classes,
        misplaced,
        "root repository must reside in " + ORDERS);
  }

  @Test
  void aRootRepositoryWithoutAnAggregateRootIsRejected() throws IOException {
    var classes =
        compile(
            directory,
            Map.of(
                ORDER_REPOSITORY,
                "public interface OrderRepository {}",
                ORDER_SERVICE,
                "public class OrderService { " + ORDER_REPOSITORY + " repository; }"));
    rejects(
        rulesFor(classes).get("DOMAINS_HAVE_A_SINGLE_PERSISTENCE_AUTHORITY"),
        classes,
        ORDER_REPOSITORY,
        "root repository requires the domain aggregate root in " + ORDERS + ".aggregate");
  }

  @Test
  void aServiceUsingAForeignDomainRepositoryIsRejected() throws IOException {
    var bypass =
        "public class OrderService { "
            + ORDER_REPOSITORY
            + " repository; "
            + ASSET_REPOSITORY
            + " foreign; "
            + ORDER
            + " state; }";
    var classes =
        compile(
            directory,
            merge(
                completeDomain(ORDER, ORDER_REPOSITORY, ORDER_SERVICE),
                completeDomain(ASSET, ASSET_REPOSITORY, ASSET_SERVICE),
                Map.of(ORDER_SERVICE, bypass)));
    rejects(
        rulesFor(classes).get("SERVICES_USE_THEIR_DOMAIN_REPOSITORY"),
        classes,
        ORDER_SERVICE,
        ASSET_REPOSITORY,
        "must use its own domain's root repository " + ORDER_REPOSITORY);
  }

  @Test
  void aServiceUsingItsRootRepositoryWithoutTheAggregateRootIsRejected() throws IOException {
    var classes =
        compile(
            directory,
            Map.of(
                ORDER,
                "public class Order {}",
                ORDER_REPOSITORY,
                "public interface OrderRepository {}",
                ORDER_SERVICE,
                "public class OrderService { " + ORDER_REPOSITORY + " repository; }"));
    rejects(
        rulesFor(classes).get("SERVICES_USE_THEIR_DOMAIN_AGGREGATE"),
        classes,
        ORDER_SERVICE,
        "without the domain aggregate root " + ORDER);
  }

  private static String simple(String fullyQualifiedName) {
    return fullyQualifiedName.substring(fullyQualifiedName.lastIndexOf('.') + 1);
  }

  @SafeVarargs
  private static Map<String, String> merge(Map<String, String>... maps) {
    var merged = new LinkedHashMap<String, String>();
    for (var map : maps) {
      merged.putAll(map);
    }
    return merged;
  }
}
