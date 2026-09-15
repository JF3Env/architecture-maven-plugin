package io.github.jf3env.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ContextShapeTest {
  private static final ContextShape SHAPE = ContextShape.of("com.acme");

  @ParameterizedTest
  @ValueSource(
      strings = {
        "com.acme.orders.api",
        "com.acme.orders.api.events",
        "com.acme.orders.domain",
        "com.acme.orders.domain.workblock.policy",
        "com.acme.orders.application",
        "com.acme.orders.infrastructure.outbound.persistence.entities",
        "com.acme.platform.domain"
      })
  void layeredPackagesOwnEveryType(String packageName) {
    assertTrue(SHAPE.isLayerPackage(packageName));
    assertEquals(Optional.empty(), SHAPE.ownershipViolation(packageName, List.of("Anything")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "com.acme.orders.services",
        "com.acme.orders.domainlike",
        "com.acme.domain.orders",
        "com.acme.application.probe",
        "com.acmeorders.domain",
        "org.other.orders.domain"
      })
  void packagesOutsideTheGrammarAreNotOwned(String packageName) {
    assertFalse(SHAPE.isLayerPackage(packageName));
    if (!SHAPE.isContextRoot(packageName)) {
      assertTrue(SHAPE.ownershipViolation(packageName, List.of("Anything")).isPresent());
    }
  }

  @Test
  void theBaseOwnsOnlyTheBootstrapAndContextRootsOnlyPackageMetadata() {
    assertEquals(Optional.empty(), SHAPE.ownershipViolation("com.acme", List.of("Application")));
    assertEquals(Optional.empty(), SHAPE.ownershipViolation("com.acme", List.of("package-info")));
    assertTrue(SHAPE.ownershipViolation("com.acme", List.of("Application", "Helper")).isPresent());
    assertEquals(
        Optional.empty(), SHAPE.ownershipViolation("com.acme.orders", List.of("package-info")));
    assertTrue(SHAPE.ownershipViolation("com.acme.orders", List.of("Application")).isPresent());
    assertTrue(SHAPE.ownershipViolation("com.acme.orders", List.of("Order")).isPresent());
    assertTrue(SHAPE.ownershipViolation("com.acme.orders", List.of()).isEmpty());
  }

  @ParameterizedTest
  @CsvSource({
    "com.acme.orders.domain, orders, domain",
    "com.acme.orders.application.workblock, orders, application",
    "com.acme.platform.infrastructure, platform, infrastructure",
    "com.acme.orders, orders,",
    "com.acme, ,"
  })
  void segmentsAndLayersAreDerivedFromThePackage(String packageName, String segment, String layer) {
    assertEquals(Optional.ofNullable(segment), SHAPE.segmentOf(packageName));
    assertEquals(Optional.ofNullable(layer), SHAPE.layerOf(packageName));
  }

  @Test
  void domainWiringAndPlatformPredicatesFollowTheGrammar() {
    assertTrue(SHAPE.isDomainPackage("com.acme.orders.domain.asset"));
    assertFalse(SHAPE.isDomainPackage("com.acme.orders.application"));
    assertTrue(SHAPE.isApplicationPackage("com.acme.orders.application.workblock"));
    assertTrue(SHAPE.isWiringPackage("com.acme.orders.infrastructure.wiring"));
    assertTrue(SHAPE.isWiringPackage("com.acme.orders.infrastructure.wiring.cdi"));
    assertFalse(SHAPE.isWiringPackage("com.acme.orders.infrastructure.wiringx"));
    assertFalse(SHAPE.isWiringPackage("com.acme.orders.application.wiring"));
    assertTrue(SHAPE.isPlatformPackage("com.acme.platform"));
    assertTrue(SHAPE.isPlatformPackage("com.acme.platform.domain"));
    assertFalse(SHAPE.isPlatformPackage("com.acme.platformx.domain"));
    assertTrue("com.acme.orders.domain".matches(SHAPE.domainPackagePattern()));
    assertTrue("com.acme.orders.domain.asset".matches(SHAPE.domainPackagePattern()));
    assertFalse("com.acme.orders.domainx".matches(SHAPE.domainPackagePattern()));
    assertFalse("com.acme.orders.application".matches(SHAPE.domainPackagePattern()));
  }

  @Test
  void markerDefaultsAndValidationFollowThePlatformPackage() {
    assertEquals("com.acme.platform", SHAPE.platform());
    assertEquals("com.acme.platform.domain.AggregateRoot", SHAPE.defaultAggregateRootAnnotation());
    var shared = ContextShape.of("com.acme", "shared");
    assertEquals("com.acme.shared.application.UnitOfWork", shared.defaultUnitOfWorkType());
    assertEquals("com.acme.shared.domain.IntegrationEvent", shared.defaultIntegrationEventType());
    assertEquals("platform", ContextShape.of("com.acme", null).platformPackage());
    assertThrows(IllegalArgumentException.class, () -> ContextShape.of("com..acme"));
    assertThrows(IllegalArgumentException.class, () -> ContextShape.of(null));
    assertThrows(IllegalArgumentException.class, () -> ContextShape.of("com.acme", "a.b"));
    assertThrows(IllegalArgumentException.class, () -> ContextShape.of("com.acme", "class"));
    assertEquals("com.acme.<context>.{api,domain,application,infrastructure}", SHAPE.description());
  }
}
