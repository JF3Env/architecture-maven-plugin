package io.github.jf3env.architecture.bytecode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DomainPackageConventionTest {
  private static final DomainPackageConvention CONVENTION =
      new DomainPackageConvention("com.ai.label");

  @ParameterizedTest
  @ValueSource(
      strings = {
        "com.ai.label.domain.workspace.services.configuration",
        "com.ai.label.domain.catalog.services.search"
      })
  void acceptsServicesInTheirOwnPackages(String packageName) {
    assertTrue(CONVENTION.isServiceLocation(packageName));
    assertTrue(CONVENTION.serviceTypeViolation(packageName, "ExampleService", false).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "com.ai.label.domain.workspace",
        "com.ai.label.domain.workspace.services",
        "com.ai.label.domain.workspace.service.configuration",
        "com.ai.label.domain.workspace.services.configuration.nested",
        "com.ai.label.domain.workspace.services.factory",
        "com.ai.label.domain.workspace.services.configuration.command",
        "com.ai.label.infra.workspace.services.configuration"
      })
  void rejectsServicesOutsideTheirOwnPackages(String packageName) {
    assertFalse(CONVENTION.isServiceLocation(packageName));
    assertTrue(CONVENTION.serviceTypeViolation(packageName, "ExampleService", false).isPresent());
  }

  @ParameterizedTest
  @CsvSource({
    "services.search.command, SearchCommand, false",
    "services.search.query, SearchQuery, false",
    "services.search.result, SearchResult, false",
    "services.search.value, SearchValue, false",
    "services.search.projection, SearchProjection, false",
    "services.search.exceptions, SearchException, true",
    "services.search.factory, SearchFactory, false",
    "services.value, SharedValue, false",
    "services.result, SharedResult, false",
    "services.exceptions, SharedException, true",
    "services.search.result.value, NestedValue, false"
  })
  void acceptsCapabilityAndSharedRolePackages(String scope, String name, boolean exception) {
    assertTrue(
        CONVENTION
            .serviceTypeViolation("com.ai.label.domain.catalog." + scope, name, exception)
            .isEmpty());
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "services.search | SearchValue | false | only Service classes",
        "services | SharedValue | false | expected a service capability",
        "services.search.misc | SearchValue | false | expected a service capability",
        "services.search.command.misc | SearchCommand | false | unknown role package",
        "services.search.command | SearchResult | false | requires suffix 'Command'",
        "services.search.result | SearchCommand | false | requires suffix 'Result'",
        "services.search.factory | SearchCreator | false | requires suffix 'Factory'",
        "services.search.exceptions | SearchException | false | only Exception subtypes",
        "services.search.result | SearchResult | true | only Exception subtypes",
        "services.search.command.service.value | SearchValue | false | expected a service capability",
        "services.search.query.services | SearchQuery | false | expected a service capability"
      })
  void rejectsMissingMismatchedAndMisleadingRoles(
      String scope, String name, boolean exception, String diagnostic) {
    var violation =
        CONVENTION.serviceTypeViolation("com.ai.label.domain.catalog." + scope, name, exception);
    assertTrue(violation.isPresent(), scope + ": " + name);
    assertTrue(violation.orElseThrow().contains(diagnostic), violation.orElseThrow());
  }

  @ParameterizedTest
  @CsvSource({
    "services.search, SearchService, com.ai.label.domain.catalog, true, true",
    "services.search, SearchService, com.ai.label.domain.other, true, false",
    "services.search, SearchService, com.ai.label.domain.catalog, false, false",
    "services.search, SearchHelper, com.ai.label.domain.catalog, true, false",
    "services.search.nested, SearchService, com.ai.label.domain.catalog, true, false",
    "services, SearchService, com.ai.label.domain.catalog, true, false"
  })
  void rootContractAccessRequiresTheOwnDomainInterfaceAndAService(
      String scope, String name, String target, boolean contract, boolean allowed) {
    assertEquals(
        allowed,
        CONVENTION.isOwnRootContractAccess(
            "com.ai.label.domain.catalog." + scope, name, target, contract));
  }

  @ParameterizedTest
  @CsvSource({
    "value, SearchCommand, command",
    "value, Plain, value",
    "misc, SearchFactory, factory"
  })
  void componentRoleUsesTheDeclaredSuffixOrKnownTerminalPackage(
      String scope, String name, String expected) {
    assertEquals(
        expected,
        CONVENTION.componentRole("com.ai.label.domain.catalog." + scope, name).orElseThrow());
  }

  @ParameterizedTest
  @ValueSource(strings = {"com.ai.label.domain.catalog.misc", "com.ai.label.domain.catalog"})
  void unknownComponentRolesAreNotInvented(String scope) {
    assertTrue(CONVENTION.componentRole(scope, "Plain").isEmpty());
  }
}
