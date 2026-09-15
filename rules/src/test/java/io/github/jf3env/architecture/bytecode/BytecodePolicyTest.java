package io.github.jf3env.architecture.bytecode;

import static io.github.jf3env.architecture.bytecode.CompiledArchitectureFixture.accepts;
import static io.github.jf3env.architecture.bytecode.CompiledArchitectureFixture.compile;
import static io.github.jf3env.architecture.bytecode.CompiledArchitectureFixture.rejects;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BytecodePolicyTest {
  @TempDir Path directory;

  @Test
  void consumerConfigurationCannotLeakBetweenCatalogs() throws IOException {
    var first =
        new BytecodeRuleCatalog(TestPolicies.orders("com.first"), List.of("orders")).rules();
    var second =
        new BytecodeRuleCatalog(TestPolicies.orders("org.second"), List.of("orders")).rules();
    var classes =
        compile(
            directory, Map.of("com.first.orders.domain.OrderValue", "public class OrderValue {}"));
    accepts(first.get("CLASSES_RESIDE_IN_CONTEXT_SHAPE"), classes);
    rejects(
        second.get("CLASSES_RESIDE_IN_CONTEXT_SHAPE"),
        classes,
        "com.first.orders.domain.OrderValue");
    accepts(first.get("CLASSES_RESIDE_IN_CONTEXT_SHAPE"), classes);
  }

  @Test
  void markerTypesDefaultToThePlatformAndFollowAConfiguredPlatformPackage() {
    var defaults = BytecodePolicy.of("org.acme");
    assertEquals("platform", defaults.platformPackage());
    assertEquals("org.acme.platform.application.UnitOfWork", defaults.unitOfWorkType());
    assertEquals("org.acme.platform.domain.IntegrationEvent", defaults.integrationEventType());
    assertEquals("org.acme.platform.domain.AggregateRoot", defaults.aggregateRootAnnotation());
    assertEquals(BytecodePolicy.DEFAULT_FRAMEWORK_PACKAGES, defaults.frameworkPackages());
    var shared = new BytecodePolicy("org.acme", "shared", null, "", null, List.of());
    assertEquals("org.acme.shared.application.UnitOfWork", shared.unitOfWorkType());
    assertEquals("org.acme.shared.domain.IntegrationEvent", shared.integrationEventType());
    assertEquals("org.acme.shared.domain.AggregateRoot", shared.aggregateRootAnnotation());
    var explicit =
        new BytecodePolicy(
            "org.acme",
            null,
            "org.acme.kernel.Tx",
            "org.acme.kernel.Event",
            "org.acme.kernel.Root",
            List.of("org.forbidden.."));
    assertEquals("org.acme.kernel.Tx", explicit.unitOfWorkType());
    assertEquals("org.acme.kernel.Event", explicit.integrationEventType());
    assertEquals("org.acme.kernel.Root", explicit.aggregateRootAnnotation());
    assertEquals(List.of("org.forbidden.."), explicit.frameworkPackages());
  }

  @Test
  void policiesRejectInvalidConfiguration() {
    assertThrows(IllegalArgumentException.class, () -> BytecodePolicy.of(null));
    assertThrows(IllegalArgumentException.class, () -> BytecodePolicy.of(""));
    assertThrows(IllegalArgumentException.class, () -> BytecodePolicy.of("org..acme"));
    assertThrows(IllegalArgumentException.class, () -> BytecodePolicy.of("1org.acme"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BytecodePolicy("org.acme", "shared.kernel", null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BytecodePolicy("org.acme", null, "not a type", null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BytecodePolicy("org.acme", null, null, null, null, List.of(" ")));
  }
}
