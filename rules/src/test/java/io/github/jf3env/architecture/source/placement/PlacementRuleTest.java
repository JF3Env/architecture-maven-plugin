package io.github.jf3env.architecture.source.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jf3env.architecture.SourceReport;
import io.github.jf3env.architecture.source.placement.PlacementFixture.Source;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Positive and negative controls for the advisory type-placement detector. */
class PlacementRuleTest {
  private static final String AGGREGATE = "com.acme.orders";

  @TempDir Path directory;

  @ParameterizedTest
  @CsvSource({
    "OrderReconstruction, factory",
    "OrderFactory, factory",
    "OrderNotFoundException, exceptions",
    "PriceValue, value",
    "PlacementResult, result",
    "OrderProjection, projection",
    "PlaceOrderCommand, command",
    "FindOrderQuery, query",
    "OrderMapper, mappers"
  })
  void reportsATypeDeclaredOutsideTheRoleFolderItsNameAnnounces(String typeName, String role)
      throws IOException {
    var result =
        PlacementFixture.analyze(
            this.directory,
            List.of(
                Source.of(AGGREGATE, typeName, "class " + typeName + " {}"),
                Source.of(AGGREGATE + "." + role, "Neighbor", "class Neighbor {}")));
    assertEquals(1, result.findings().size(), result.findings().toString());
    var finding = result.findings().get(0);
    assertEquals(role, finding.role());
    assertEquals("MEDIUM", finding.confidence());
    assertEquals(typeName, finding.typeName());
    assertEquals(AGGREGATE, finding.currentPackage());
    assertEquals(AGGREGATE + "." + role, finding.expectedPackage());
    assertTrue(finding.detail().contains("misplaced type: " + typeName), finding.detail());
    assertTrue(finding.detail().contains("already exists next to it"), finding.detail());
    assertTrue(
        finding.suggestion().contains("git mv to com/acme/orders/" + role), finding.suggestion());
    assertTrue(finding.suggestion().contains("update the imports"), finding.suggestion());
  }

  @Test
  void aTypeInsideTheWrongRoleFolderIsPlacedAgainstItsSibling() throws IOException {
    var result =
        PlacementFixture.analyze(
            this.directory,
            List.of(
                Source.of(AGGREGATE + ".value", "OrderFactory", "class OrderFactory {}"),
                Source.of(AGGREGATE + ".factory", "Neighbor", "class Neighbor {}")));
    assertEquals(1, result.findings().size(), result.findings().toString());
    var finding = result.findings().get(0);
    assertEquals(AGGREGATE + ".value", finding.currentPackage());
    assertEquals(AGGREGATE + ".factory", finding.expectedPackage());
  }

  @Test
  void aTypeAlreadyInItsRoleFolderIsNotAFinding() throws IOException {
    var result =
        PlacementFixture.analyze(
            this.directory,
            List.of(
                Source.of(AGGREGATE + ".factory", "OrderFactory", "class OrderFactory {}"),
                Source.of(AGGREGATE + ".exceptions", "OrderException", "class OrderException {}"),
                Source.of(AGGREGATE + ".value", "PriceValue", "class PriceValue {}")));
    assertEquals(List.of(), result.findings());
  }

  @Test
  void anAbsentRoleFolderMeansTheConventionIsNotInUseHere() throws IOException {
    var result =
        PlacementFixture.analyze(
            this.directory,
            List.of(
                Source.of(AGGREGATE, "OrderFactory", "class OrderFactory {}"),
                Source.of(AGGREGATE, "PriceValue", "class PriceValue {}"),
                Source.of(AGGREGATE + ".value", "Neighbor", "class Neighbor {}")));
    assertEquals(
        List.of("PriceValue"),
        result.findings().stream().map(PlacementFinding::typeName).toList(),
        "only the role modelled next to the type may be reported: " + result.findings());
  }

  @Test
  void anExistingRoleFolderElsewhereInTheTreeCalibratesNothing() throws IOException {
    var result =
        PlacementFixture.analyze(
            this.directory,
            List.of(
                Source.of(AGGREGATE, "OrderFactory", "class OrderFactory {}"),
                Source.of("com.acme.billing.factory", "Neighbor", "class Neighbor {}")));
    assertTrue(
        result.packages().contains("com.acme.billing.factory"), result.packages().toString());
    assertEquals(List.of(), result.findings());
  }

  @Test
  void packageMetadataIsNeverATypeOutOfPlace() throws IOException {
    var result =
        PlacementFixture.analyze(
            this.directory,
            List.of(
                new Source(PlacementFixture.MAIN, AGGREGATE, "package-info", ""),
                Source.of(AGGREGATE + ".factory", "Neighbor", "class Neighbor {}")));
    assertEquals(List.of("Neighbor"), result.typeNames());
    assertEquals(List.of(), result.findings());
  }

  @Test
  void aNestedTypeHasNoFolderOfItsOwn() throws IOException {
    var result =
        PlacementFixture.analyze(
            this.directory,
            List.of(
                Source.of(AGGREGATE, "Orders", "class Orders { static class OrderFactory {} }"),
                Source.of(AGGREGATE + ".factory", "Neighbor", "class Neighbor {}")));
    assertEquals(List.of("Orders", "Neighbor"), result.typeNames());
    assertEquals(List.of(), result.findings());
  }

  @Test
  void testSourcesAreOutOfScope() throws IOException {
    var result =
        PlacementFixture.analyze(
            this.directory,
            List.of(
                new Source(
                    PlacementFixture.TEST,
                    AGGREGATE,
                    "OrderReconstruction",
                    "class OrderReconstruction {}"),
                Source.of(AGGREGATE + ".factory", "Neighbor", "class Neighbor {}")));
    assertEquals(List.of(), result.findings());
  }

  @Test
  void aNameThatIsOnlyTheRoleItselfAnnouncesNothing() throws IOException {
    var result =
        PlacementFixture.analyze(
            this.directory,
            List.of(
                Source.of(AGGREGATE, "Factory", "class Factory {}"),
                Source.of(AGGREGATE + ".factory", "Neighbor", "class Neighbor {}")));
    assertEquals(List.of(), result.findings());
  }

  /**
   * The provider contract, declared in the fixture tree so the counterexamples compile. Named
   * exactly like {@code jakarta.ws.rs.ext.ExceptionMapper}, because the analysis has no type
   * resolution and matches the simple name.
   */
  private static final String PROVIDER_CONTRACT =
      "interface ExceptionMapper<E> { String map(E e); }";

  @Test
  void aJaxrsExceptionProviderIsNotAMapper() throws IOException {
    var result =
        PlacementFixture.analyze(
            this.directory,
            List.of(
                Source.of(AGGREGATE, "ExceptionMapper", PROVIDER_CONTRACT),
                Source.of(
                    AGGREGATE,
                    "FooExceptionMapper",
                    "class FooExceptionMapper implements ExceptionMapper<IllegalStateException> {"
                        + " public String map(IllegalStateException e) { return \"\"; } }"),
                Source.of(AGGREGATE + ".mappers", "Neighbor", "class Neighbor {}")));
    assertEquals(
        List.of(),
        result.findings(),
        "a JAX-RS provider must never be asked to move into mappers: " + result.findings());
  }

  /**
   * Signal 1 in isolation: the name ends in {@code Mapper} so the role resolves to {@code mappers},
   * and it does not end in {@code ExceptionMapper}, so only the {@code implements} clause can
   * exclude it.
   */
  @Test
  void theImplementsClauseAloneExcludesAProvider() throws IOException {
    var result =
        PlacementFixture.analyze(
            this.directory,
            List.of(
                Source.of(AGGREGATE, "ExceptionMapper", PROVIDER_CONTRACT),
                Source.of(
                    AGGREGATE,
                    "DomainFailureMapper",
                    "class DomainFailureMapper implements ExceptionMapper<IllegalStateException> {"
                        + " public String map(IllegalStateException e) { return \"\"; } }"),
                Source.of(AGGREGATE + ".mappers", "Neighbor", "class Neighbor {}")));
    assertEquals(List.of(), result.findings(), result.findings().toString());
  }

  /**
   * Signal 2 in isolation: no {@code implements} clause at all, so only the name can exclude it.
   * This is the type whose provider contract arrives through a hierarchy the source does not show.
   */
  @Test
  void theNameAloneExcludesAProvider() throws IOException {
    var result =
        PlacementFixture.analyze(
            this.directory,
            List.of(
                Source.of(AGGREGATE, "LabelExceptionMapper", "class LabelExceptionMapper {}"),
                Source.of(AGGREGATE + ".mappers", "Neighbor", "class Neighbor {}")));
    assertEquals(List.of(), result.findings(), result.findings().toString());
  }

  /** The exclusion is per type, not a switch that turns the {@code mappers} role off. */
  @Test
  void anOrdinaryMapperIsStillReportedNextToAProvider() throws IOException {
    var result =
        PlacementFixture.analyze(
            this.directory,
            List.of(
                Source.of(AGGREGATE, "ExceptionMapper", PROVIDER_CONTRACT),
                Source.of(
                    AGGREGATE,
                    "FooExceptionMapper",
                    "class FooExceptionMapper implements ExceptionMapper<IllegalStateException> {"
                        + " public String map(IllegalStateException e) { return \"\"; } }"),
                Source.of(AGGREGATE, "OrderMapper", "class OrderMapper {}"),
                Source.of(AGGREGATE + ".mappers", "Neighbor", "class Neighbor {}")));
    assertEquals(
        List.of("OrderMapper"),
        result.findings().stream().map(PlacementFinding::typeName).toList(),
        result.findings().toString());
    assertEquals("mappers", result.findings().get(0).role());
  }

  @Test
  void anAdvisoryNeverFailsTheContract() throws IOException {
    var result =
        PlacementFixture.analyze(
            this.directory,
            List.of(
                Source.of(AGGREGATE, "OrderReconstruction", "class OrderReconstruction {}"),
                Source.of(AGGREGATE + ".factory", "Neighbor", "class Neighbor {}")));
    assertEquals(1, result.findings().size(), result.findings().toString());
    var report =
        new SourceReport(
            2,
            List.of(PlacementRule.NAME),
            List.of(),
            List.of(),
            result.findings().stream().map(PlacementFinding::detail).toList());
    assertTrue(report.passed(), report.toString());
    assertEquals(1, report.advisories().size());
    assertTrue(new SourceReport(2, List.of(PlacementRule.NAME), List.of(), List.of()).passed());
  }
}
