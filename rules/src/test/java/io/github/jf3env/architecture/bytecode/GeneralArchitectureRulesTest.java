package io.github.jf3env.architecture.bytecode;

import static io.github.jf3env.architecture.bytecode.CompiledArchitectureFixture.accepts;
import static io.github.jf3env.architecture.bytecode.CompiledArchitectureFixture.compile;
import static io.github.jf3env.architecture.bytecode.CompiledArchitectureFixture.rejects;

import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Each fixture evaluates its named contract, not the conjunction of every production rule. */
class GeneralArchitectureRulesTest {
  private static final Map<String, ArchRule> RULES =
      new BytecodeRuleCatalog(TestPolicies.reference(), List.of()).rules();
  @TempDir Path temporary;

  @ParameterizedTest
  @CsvSource({
    "infra, domain, true",
    "persistence, domain, true",
    "infra, persistence, false",
    "persistence, infra, false",
    "domain, infra, false",
    "domain, persistence, false"
  })
  void dependenciesPointTowardTheDomain(String originLayer, String targetLayer, boolean allowed)
      throws IOException {
    var origin = "com.ai.label." + originLayer + ".probe.Origin";
    var target = "com.ai.label." + targetLayer + ".probe.Target";
    var classes =
        compile(
            this.temporary,
            Map.of(
                origin,
                "public class Origin { " + target + " target; }",
                target,
                "public class Target {}",
                "com.ai.label.domain.probe.value.Marker",
                "public class Marker {}",
                "com.ai.label.infra.probe.Marker",
                "public class Marker {}",
                "com.ai.label.persistence.probe.Marker",
                "public class Marker {}"));
    if (allowed) {
      accepts(RULES.get("LAYER_COMMUNICATION"), classes);
    } else {
      rejects(RULES.get("LAYER_COMMUNICATION"), classes, origin, target);
    }
  }

  @Test
  void applicationLayerIsRejectedWhileOwnedLayersAreAccepted() throws IOException {
    var valid =
        compile(
            this.temporary,
            Map.of(
                "com.ai.label.domain.probe.value.ProbeValue",
                "public class ProbeValue {}",
                "com.ai.label.persistence.probe.Adapter",
                "public class Adapter {}",
                "com.ai.label.infra.probe.Resource",
                "public class Resource {}"));
    accepts(RULES.get("APPLICATION_LAYER_IS_ABSENT"), valid);
    accepts(RULES.get("CLASSES_ARE_GROUPED_BY_LAYER_AND_DOMAIN"), valid);
    var invalid =
        compile(
            this.temporary,
            Map.of("com.ai.label.application.probe.Misplaced", "public class Misplaced {}"));
    rejects(
        RULES.get("APPLICATION_LAYER_IS_ABSENT"),
        invalid,
        "com.ai.label.application.probe.Misplaced");
    rejects(
        RULES.get("CLASSES_ARE_GROUPED_BY_LAYER_AND_DOMAIN"),
        invalid,
        "com.ai.label.application.probe.Misplaced");
  }

  @Test
  void domainDependenciesMayBeAcyclicButNeverCyclic() throws IOException {
    var first = "com.ai.label.domain.first.value.FirstValue";
    var second = "com.ai.label.domain.second.value.SecondValue";
    var firstSource = "public class FirstValue { " + second + " other; }";
    accepts(
        RULES.get("DOMAINS_ARE_FREE_OF_CYCLES"),
        compile(this.temporary, Map.of(first, firstSource, second, "public class SecondValue {}")));
    rejects(
        RULES.get("DOMAINS_ARE_FREE_OF_CYCLES"),
        compile(
            this.temporary,
            Map.of(
                first, firstSource, second, "public class SecondValue { " + first + " other; }")),
        first,
        second);
  }

  @Test
  void domainUsesJavaButRejectsFrameworkCapabilities() throws IOException {
    var name = "com.ai.label.domain.probe.value.ProbeValue";
    accepts(
        RULES.get("DOMAIN_IS_FRAMEWORK_FREE"),
        compile(this.temporary, Map.of(name, "public class ProbeValue { java.util.UUID id; }")));
    rejects(
        RULES.get("DOMAIN_IS_FRAMEWORK_FREE"),
        compile(
            this.temporary,
            Map.of(name, "public class ProbeValue { jakarta.persistence.EntityManager manager; }")),
        name,
        "jakarta.persistence.EntityManager");
  }

  @ParameterizedTest
  @CsvSource({
    "services.read, ReadService, true",
    "services.read, Reader, false",
    "services.read.nested, ReadService, false",
    "aggregate, ProbeAggregate, false"
  })
  void onlyCorrectlyLocatedServicesMayAccessTheirOwnRootInterface(
      String scope, String consumer, boolean allowed) throws IOException {
    var contract = "com.ai.label.domain.probe.ProbeRepository";
    var origin = "com.ai.label.domain.probe." + scope + "." + consumer;
    var classes =
        compile(
            this.temporary,
            Map.of(
                contract,
                "public interface ProbeRepository {}",
                origin,
                "public class " + consumer + " { " + contract + " repository; }"));
    if (allowed) {
      accepts(RULES.get("SUBPACKAGES_DO_NOT_ACCESS_ANCESTOR_PACKAGES"), classes);
    } else {
      rejects(RULES.get("SUBPACKAGES_DO_NOT_ACCESS_ANCESTOR_PACKAGES"), classes, origin, contract);
    }
  }

  @Test
  void ownRootExemptionDoesNotPermitConcreteAncestorTypes() throws IOException {
    var root = "com.ai.label.domain.probe.RootValue";
    var consumer = "com.ai.label.domain.probe.services.read.ReadService";
    rejects(
        RULES.get("SUBPACKAGES_DO_NOT_ACCESS_ANCESTOR_PACKAGES"),
        compile(
            this.temporary,
            Map.of(
                root,
                "public class RootValue {}",
                consumer,
                "public class ReadService { " + root + " value; }")),
        consumer,
        root);
  }
}
