package io.github.jf3env.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Minimal, dependency-free fixtures in the context-first shape {@code <base>.<context>.<layer>}.
 */
class SourceRulesTest {
  private static final String MARKER = ".platform.domain.AggregateRoot";

  @TempDir Path directory;
  private Path sources;
  private Path classes;

  @BeforeEach
  void prepare() throws IOException {
    sources = Files.createDirectory(directory.resolve("sources"));
    classes = Files.createDirectory(directory.resolve("classes"));
  }

  @Test
  void acceptsACompiledConsumerAndReportsActualRuleIdentities() throws Exception {
    compile("com.acme.orders.domain", "int value() { return 1; }");
    var result = analyze("com.acme");
    assertEquals(1, result.sourceFiles());
    assertEquals(Set.of(), findings(result));
    assertEquals(
        Set.of(
            "AvoidOptionalGet",
            "DomainMethodsMustNotReturnNull",
            "RequireTypeImports",
            "AggregateInvariantSetter",
            "PassthroughFactsCollector",
            "TypePlacementFactsCollector"),
        Set.copyOf(result.rules()));
    assertEquals(List.of(), result.advisories());
  }

  @Test
  void passthroughAdvisoriesAreReportedWithoutAffectingTheOutcome() throws Exception {
    compile(
        "com.acme.orders.domain",
        "int entry(int value) { return helper(value) + 1; }"
            + " private int helper(int value) { return target(value); }"
            + " int target(int value) { return value; }");
    var report = analyze("com.acme");
    assertEquals(Set.of(), findings(report));
    assertTrue(report.passed(), report.toString());
    assertEquals(1, report.advisories().size(), report.advisories().toString());
    assertTrue(
        report.advisories().get(0).startsWith("PASSTHROUGH_S1/MEDIUM | "),
        report.advisories().toString());
    assertTrue(
        report.advisories().get(0).contains("Fixture.helper: single-use forwarder"),
        report.advisories().toString());
  }

  @Test
  void placementAdvisoriesAreReportedWithoutAffectingTheOutcome() throws Exception {
    compile("com.acme.orders.domain", "int value() { return 1; }");
    declare("com.acme.orders.domain", "OrderReconstruction");
    declare("com.acme.orders.domain.factory", "OrderFactory");
    var report = analyze("com.acme");
    assertEquals(Set.of(), findings(report));
    assertTrue(report.passed(), report.toString());
    assertEquals(1, report.advisories().size(), report.advisories().toString());
    assertTrue(
        report.advisories().get(0).startsWith("PLACEMENT_FACTORY/MEDIUM | "),
        report.advisories().toString());
    assertTrue(
        report
            .advisories()
            .get(0)
            .contains("OrderReconstruction: misplaced type: OrderReconstruction is declared in"),
        report.advisories().toString());
    assertTrue(
        report.advisories().get(0).contains("git mv to com/acme/orders/domain/factory"),
        report.advisories().toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"com.acme", "com.ai.label", "org.example.longer.prefix"})
  void domainSelectorsFollowTheConsumerPackage(String base) throws Exception {
    compile(
        base + ".orders.domain.asset",
        "Object value(Optional<Object> input) { input.get(); return null; }");
    assertEquals(
        Set.of("AvoidOptionalGet", "DomainMethodsMustNotReturnNull"), findings(analyze(base)));
  }

  @Test
  void thePlatformDomainIsDomainCodeToo() throws Exception {
    compile("com.acme.platform.domain", "Object value() { return null; }");
    assertEquals(Set.of("DomainMethodsMustNotReturnNull"), findings(analyze("com.acme")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"api", "application", "infrastructure"})
  void absenceRulesDoNotExpandIntoOtherLayers(String layer) throws Exception {
    compile(
        "com.acme.orders." + layer,
        "Object value(Optional<Object> input) { input.get(); return null; }");
    assertEquals(Set.of(), findings(analyze("com.acme")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "private static int helper() { return 1; }",
        "static Fixture of() { return new Fixture(); }",
        "static class Nested { static int value() { return 1; } }",
        "interface Contract { static int value() { return 1; } }",
        "record Command(long count) {}",
        "Object create() { return new Object(); }"
      })
  void staticFactoriesRecordsAndPlainConstructionAreIdiomatic(String member) throws Exception {
    compile("com.acme.orders.domain", member);
    assertEquals(Set.of(), findings(analyze("com.acme")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "java.util.UUID id;",
        "@java.lang.Deprecated void old() {}",
        "Object create() { return new java.util.ArrayList<>(); }",
        "Object value() { return java.util.UUID.randomUUID(); }",
        "java.util.function.Supplier<?> source() { return java.util.UUID::randomUUID; }"
      })
  void preservesQualifiedTypeCounterexamples(String member) throws Exception {
    compile("com.acme.orders.domain", member);
    assertEquals(Set.of("RequireTypeImports"), findings(analyze("com.acme")));
  }

  @Test
  void importedTypesWithAVisibleConflictingNameCanBeQualified() throws Exception {
    compile("com.acme.orders.domain", "static class Date {} java.util.Date value;");
    assertEquals(Set.of(), findings(analyze("com.acme")));
  }

  @Test
  void invisibleNestedNamesDoNotJustifyQualification() throws Exception {
    compile(
        "com.acme.orders.domain",
        "static class Other { static class Date {} } java.util.Date value;");
    assertEquals(Set.of("RequireTypeImports"), findings(analyze("com.acme")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "(Object) null", "flag ? null : new Object()"})
  void preservesLiteralConditionalAndCastNullRejections(String expression) throws Exception {
    compile("com.acme.orders.domain", "Object value(boolean flag) { return " + expression + "; }");
    assertEquals(Set.of("DomainMethodsMustNotReturnNull"), findings(analyze("com.acme")));
  }

  @Test
  void aGetMethodNameDoesNotImplyOptionalIdentity() throws Exception {
    compile(
        "com.acme.orders.domain", "Object get() { return this; } Object value() { return get(); }");
    assertEquals(Set.of(), findings(analyze("com.acme")));
  }

  @Test
  void optionalIdentityIsResolvedThroughCompiledConsumerMethods() throws Exception {
    compile(
        "com.acme.orders.domain",
        "Optional<Object> value() { return Optional.empty(); } Object read() { return value().get(); }");
    assertEquals(Set.of("AvoidOptionalGet"), findings(analyze("com.acme")));
  }

  @Test
  void aggregateRootsAreRecognizedByTheConsumersCompiledMarker() throws Exception {
    var marker = sources.resolve("com/acme/platform/domain/AggregateRoot.java");
    Files.createDirectories(marker.getParent());
    Files.writeString(
        marker, "package com.acme.platform.domain; public @interface AggregateRoot {}");
    compileFile(marker);
    compile(
        "com.acme.orders.domain",
        "@com.acme.platform.domain.AggregateRoot static class Order { private long count;"
            + " void reset() { this.count = 0; } }");
    assertEquals(
        Set.of("AggregateInvariantSetter", "RequireTypeImports"), findings(analyze("com.acme")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "@SuppressWarnings(\"PMD.DomainMethodsMustNotReturnNull\") Object value() { return null; }",
        "Object value() { return null; } // NOPMD",
        "@javax.annotation.processing.Generated(\"handwritten\") Object value() { return null; }"
      })
  void suppressionAndGeneratedMarkersCannotProduceApproval(String member) throws Exception {
    compile("com.acme.orders.domain", member);
    var report = analyze("com.acme");
    assertFalse(report.passed(), report.toString());
    assertFalse(report.violations().isEmpty(), report.toString());
  }

  @Test
  void wrongBasePackageCannotHideEverySource() throws Exception {
    compile("com.acme.orders.domain", "int value() { return 1; }");
    assertEquals(Set.of("SOURCE_INVENTORY"), findings(analyze("org.wrong")));
    assertEquals(
        Set.of(),
        findings(analyze("com.acme")),
        "another request must not inherit the previous consumer configuration");
  }

  @ParameterizedTest
  @CsvSource({
    "com.acme, Application, true",
    "com.acme, Helper, false",
    "com.acme.orders, package-info, true",
    "com.acme.orders, Stray, false",
    "com.acme.orders.services, OrderService, false",
    "com.acme.domain.orders, Order, false",
    "com.acme.orders.infrastructure.outbound.persistence, OrderAdapter, true"
  })
  void onlyTheContextShapeTheBootstrapAndContextMetadataAreOwned(
      String packageName, String type, boolean owned) throws Exception {
    compile("com.acme.orders.domain", "int value() { return 1; }");
    if (type.equals("package-info")) {
      var file = sources.resolve(packageName.replace('.', '/') + "/package-info.java");
      Files.writeString(file, "@Deprecated\npackage " + packageName + ";");
      compileFile(file);
    } else {
      var file = sources.resolve(packageName.replace('.', '/') + "/" + type + ".java");
      Files.createDirectories(file.getParent());
      Files.writeString(file, "package " + packageName + "; class " + type + " {}");
      compileFile(file);
    }
    var report = analyze("com.acme");
    assertEquals(2, report.sourceFiles());
    assertEquals(owned ? Set.of() : Set.of("SOURCE_INVENTORY"), findings(report));
    if (!owned) {
      assertTrue(
          report.violations().stream().anyMatch(line -> line.contains(packageName)),
          report.toString());
    }
  }

  @Test
  void rejectsPackageAndDirectoryDisagreement() throws Exception {
    var source = compile("com.acme.orders.domain", "int value() { return 1; }");
    Files.move(source, sources.resolve("Fixture.java"));
    assertEquals(Set.of("SOURCE_INVENTORY"), findings(analyze("com.acme")));
  }

  @Test
  void missingEmptyAndCorruptEvidenceIsRejected() throws Exception {
    assertThrows(IOException.class, () -> analyze("com.acme"));
    compile("com.acme.orders.domain", "int value() { return 1; }");
    var bytecode = classes.resolve("com/acme/orders/domain/Fixture.class");
    Files.write(bytecode, new byte[] {0, 1, 2});
    assertTrue(
        assertThrows(IOException.class, () -> analyze("com.acme"))
            .getMessage()
            .contains("Cannot inspect class file"));
    Files.delete(bytecode);
    assertTrue(
        assertThrows(IOException.class, () -> analyze("com.acme"))
            .getMessage()
            .contains("Empty compiled-class inventory"));
  }

  @Test
  void parseErrorsAreAnalysisFailuresRatherThanCleanReports() throws Exception {
    var source = compile("com.acme.orders.domain", "int value() { return 1; }");
    Files.writeString(source, "package com.acme.orders.domain; class Fixture { invalid Java");
    var result = analyze("com.acme");
    assertFalse(result.passed());
    assertFalse(result.errors().isEmpty());
  }

  @Test
  void unresolvedExternalTypesCannotSilentlyWeakenTypedSelectors() throws Exception {
    var source = compile("com.acme.orders.domain", "int value() { return 1; }");
    Files.writeString(
        source,
        "package com.acme.orders.domain; import missing.External; class Fixture { External value; }");
    assertEquals(Set.of("SOURCE_INVENTORY"), findings(analyze("com.acme")));
  }

  @Test
  void sourceSymlinksAreRejected() throws Exception {
    var source = compile("com.acme.orders.domain", "int value() { return 1; }");
    Files.createSymbolicLink(sources.resolve("Alias.java"), source);
    assertTrue(
        assertThrows(IOException.class, () -> analyze("com.acme"))
            .getMessage()
            .contains("Symbolic link"));
  }

  @Test
  void inspectionDoesNotInitializeConsumerClasses() throws Exception {
    compile(
        "com.acme.orders.domain",
        "static { if (System.nanoTime() != 0) throw new AssertionError(\"INITIALIZED\"); }");
    assertEquals(Set.of(), findings(analyze("com.acme")));
  }

  @Test
  void additionalSourceRootsAreAnalyzed() throws Exception {
    compile("com.acme.orders.domain", "int value() { return 1; }");
    var extra = Files.createDirectory(directory.resolve("extra"));
    var file = extra.resolve("com/acme/orders/infrastructure/Extra.java");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        "package com.acme.orders.infrastructure; class Extra { java.util.UUID id() { return null; } }");
    var report =
        new SourceRules()
            .analyze(
                new SourceRequest(
                    "com.acme",
                    "com.acme" + MARKER,
                    List.of(sources, extra),
                    List.of(),
                    classes,
                    List.of()));
    assertEquals(2, report.sourceFiles());
    assertEquals(Set.of("RequireTypeImports"), findings(report));
  }

  @Test
  void missingClasspathEntriesAreReported() throws Exception {
    compile("com.acme.orders.domain", "int value() { return 1; }");
    var request =
        new SourceRequest(
            "com.acme",
            "com.acme" + MARKER,
            List.of(sources),
            List.of(),
            classes,
            List.of(directory.resolve("missing.jar")));
    assertTrue(
        assertThrows(IOException.class, () -> new SourceRules().analyze(request))
            .getMessage()
            .contains("classpath"));
  }

  @Test
  void generatedConfigurationCannotExcludeTheHandwrittenRoot() {
    var marker = "com.acme" + MARKER;
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SourceRequest(
                "com.acme", marker, List.of(sources), List.of(sources), classes, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SourceRequest(
                "com..acme", marker, List.of(sources), List.of(), classes, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SourceRequest("com.acme", marker, List.of(), List.of(), classes, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SourceRequest(
                "com.acme", "com..acme", List.of(sources), List.of(), classes, List.of()));
  }

  private SourceReport analyze(String base) throws IOException {
    return new SourceRules()
        .analyze(
            new SourceRequest(
                base, base + MARKER, List.of(sources), List.of(), classes, List.of()));
  }

  private Path compile(String name, String members) throws IOException {
    var file = sources.resolve(name.replace('.', '/') + "/Fixture.java");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        "package " + name + "; import java.util.Optional; class Fixture { " + members + "\n}");
    compileFile(file);
    return file;
  }

  private void declare(String name, String type) throws IOException {
    var file = sources.resolve(name.replace('.', '/') + "/" + type + ".java");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "package " + name + "; class " + type + " {}");
    compileFile(file);
  }

  private void compileFile(Path file) {
    var exit =
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "-proc:none",
                "--release",
                "24",
                "-classpath",
                classes.toString(),
                "-d",
                classes.toString(),
                file.toString());
    assertEquals(0, exit, "The counterexample must compile before analysis: " + file);
  }

  private Set<String> findings(SourceReport report) {
    assertTrue(report.errors().isEmpty(), report.toString());
    return report.violations().stream()
        .map(message -> message.split(" \\| ")[0])
        .collect(Collectors.toSet());
  }
}
