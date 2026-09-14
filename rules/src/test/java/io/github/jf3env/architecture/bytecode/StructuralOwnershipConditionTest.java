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
import org.junit.jupiter.params.provider.ValueSource;

class StructuralOwnershipConditionTest {
  private static final ArchRule OWNERSHIP =
      new BytecodeRuleCatalog(TestPolicies.reference(), List.of())
          .rules()
          .get("DOMAIN_COMPONENTS_FOLLOW_STRUCTURAL_OWNERS");
  private static final String DOMAIN = "com.ai.label.domain.probe";
  private static final String CAPABILITY = DOMAIN + ".services.read";
  private static final String OWNER = CAPABILITY + ".ReadService";

  @TempDir Path temporary;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ReadValue",
        "java.util.List<ReadValue>",
        "ReadValue[][]",
        "java.util.List<ReadValue[]>"
      })
  void ownGenericAndArrayFieldsRequireTheConsumerRelativeRole(String fieldType) throws IOException {
    var right = CAPABILITY + ".value.ReadValue";
    var wrong = DOMAIN + ".value.ReadValue";
    var declaration = "public class ReadService { " + fieldType + " state; }";
    accepts(
        OWNERSHIP,
        compile(
            this.temporary,
            Map.of(
                right,
                "public class ReadValue {}",
                OWNER,
                "import " + right + "; " + declaration)));
    rejects(
        OWNERSHIP,
        compile(
            this.temporary,
            Map.of(
                wrong, "public class ReadValue {}", OWNER, "import " + wrong + "; " + declaration)),
        wrong,
        CAPABILITY + ".value",
        OWNER + ".state");
  }

  @Test
  void sharedComponentsUseTheNearestCommonOwnerScope() throws IOException {
    var secondOwner = DOMAIN + ".services.write.WriteService";
    var right = DOMAIN + ".services.value.SharedValue";
    var wrong = CAPABILITY + ".value.SharedValue";
    for (var component : new String[] {right, wrong}) {
      var classes =
          compile(
              this.temporary,
              Map.of(
                  component,
                  "public class SharedValue {}",
                  OWNER,
                  "public class ReadService { " + component + " state; }",
                  secondOwner,
                  "public class WriteService { " + component + " state; }"));
      if (component.equals(right)) {
        accepts(OWNERSHIP, classes);
      } else {
        rejects(
            OWNERSHIP,
            classes,
            component,
            DOMAIN + ".services.value",
            OWNER + ".state",
            secondOwner + ".state");
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"com.ai.label.domain.other.services.read", "com.ai.label.infra.probe"})
  void ownersAcrossDomainOrLayerBoundariesAreRejected(String ownerPackage) throws IOException {
    var value = CAPABILITY + ".value.ReadValue";
    var owner = ownerPackage + ".Owner";
    rejects(
        OWNERSHIP,
        compile(
            this.temporary,
            Map.of(
                value,
                "public class ReadValue {}",
                owner,
                "public class Owner { " + value + " state; }")),
        value,
        "across domain or layer boundaries",
        owner + ".state");
  }

  @Test
  void anExistingRolePackageIsNotDuplicated() throws IOException {
    var scope = CAPABILITY + ".value";
    accepts(
        OWNERSHIP,
        compile(
            this.temporary,
            Map.of(
                scope + ".OwnerValue",
                "public class OwnerValue { ChildValue child; }",
                scope + ".ChildValue",
                "public class ChildValue {}")));
  }

  @Test
  void staticSelfAndMethodOnlyDependenciesDoNotEstablishOwners() throws IOException {
    var value = DOMAIN + ".value.ReadValue";
    accepts(
        OWNERSHIP,
        compile(
            this.temporary,
            Map.of(
                value,
                "public class ReadValue { ReadValue self; }",
                OWNER,
                "public class ReadService { static "
                    + value
                    + " shared; "
                    + value
                    + " read("
                    + value
                    + " input) { return input; } }")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"public interface ReadValue {}", "public @interface ReadValue {}"})
  void contractsAndAnnotationsAreNotConcreteOwnedComponents(String declaration) throws IOException {
    var value = DOMAIN + ".ReadValue";
    accepts(
        OWNERSHIP,
        compile(
            this.temporary,
            Map.of(
                value,
                declaration,
                "com.ai.label.infra.probe.Owner",
                "public class Owner { " + value + " state; }")));
  }

  @Test
  void nestedTypesAndServicesKeepTheirSeparateLocationContracts() throws IOException {
    var service = CAPABILITY + ".ReadService";
    var container = DOMAIN + ".value.Container";
    accepts(
        OWNERSHIP,
        compile(
            this.temporary,
            Map.of(
                service,
                "public class ReadService {}",
                container,
                "public class Container { public static class NestedValue {} }",
                "com.ai.label.infra.probe.Owner",
                "public class Owner { "
                    + service
                    + " service; "
                    + container
                    + ".NestedValue value; }")));
  }

  @Test
  void aPreviousInventoryCannotOwnComponentsInTheNextEvaluation() throws IOException {
    var value = DOMAIN + ".value.ReadValue";
    rejects(
        OWNERSHIP,
        compile(
            this.temporary,
            Map.of(
                value,
                "public class ReadValue {}",
                OWNER,
                "public class ReadService { " + value + " state; }")),
        value,
        OWNER + ".state");
    accepts(OWNERSHIP, compile(this.temporary, Map.of(value, "public class ReadValue {}")));
  }
}
