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
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every fixture's {@code Fixture} class relies on an implicit constructor, so {@code
 * LombokSimpleConstructor} is a standing finding across this file's minimal, dependency-free
 * fixtures; each assertion accounts for it explicitly rather than suppressing it, keeping fixtures
 * independent of Lombok on the analysis classpath.
 */
class SourceRulesTest {
  private static final String BOUNDARY = "com.acme.persistence.workspace.WorkspaceTransactions";

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
    compile("com.acme.domain.orders", "int value() { return 1; }");
    var result = analyze("com.acme");
    assertEquals(1, result.sourceFiles());
    assertEquals(Set.of("LombokSimpleConstructor"), findings(result));
    assertEquals(
        Set.of(
            "AvoidOptionalGet",
            "DomainMethodsMustNotReturnNull",
            "NoStaticMethods",
            "RequireTypeImports",
            "LombokSimpleConstructor",
            "LombokSimpleAccessor",
            "StaticExceptionFactory",
            "AggregateInvariantSetter",
            "ExceptionSelfFactory",
            "PersistenceBoundary"),
        Set.copyOf(result.rules()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"com.acme", "com.ai.label", "org.example.longer.prefix"})
  void domainSelectorsFollowTheConsumerPackage(String base) throws Exception {
    compile(
        base + ".domain.orders",
        "Object value(Optional<Object> input) { input.get(); return null; }");
    assertEquals(
        Set.of("AvoidOptionalGet", "DomainMethodsMustNotReturnNull", "LombokSimpleConstructor"),
        findings(analyze(base)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"infra", "persistence"})
  void absenceRulesDoNotExpandIntoAdapters(String layer) throws Exception {
    compile(
        "com.acme." + layer + ".orders",
        "Object value(Optional<Object> input) { input.get(); return null; }");
    assertEquals(Set.of("LombokSimpleConstructor"), findings(analyze("com.acme")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "private static int helper() { return 1; }",
        "static Fixture of() { return new Fixture(); }",
        "static class Nested { static int value() { return 1; } }",
        "interface Contract { static int value() { return 1; } }"
      })
  void preservesStaticMethodCounterexamples(String member) throws Exception {
    compile("com.acme.domain.orders", member);
    assertEquals(
        Set.of("NoStaticMethods", "StaticExceptionFactory", "LombokSimpleConstructor"),
        findings(analyze("com.acme")));
  }

  @Test
  void exceptionSelfFactoriesKeepTheOriginalPermittedForm() throws Exception {
    compile(
        "com.acme.domain.orders",
        """
        static class Missing extends RuntimeException {
          Missing(String message) { super(message); }
          static Missing of(String message) { return new Missing(message); }
        }
        """);
    assertEquals(Set.of("LombokSimpleConstructor"), findings(analyze("com.acme")));
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
    compile("com.acme.domain.orders", member);
    assertEquals(
        Set.of("RequireTypeImports", "LombokSimpleConstructor"), findings(analyze("com.acme")));
  }

  @Test
  void importedTypesWithAVisibleConflictingNameCanBeQualified() throws Exception {
    compile("com.acme.domain.orders", "static class Date {} java.util.Date value;");
    assertEquals(Set.of("LombokSimpleConstructor"), findings(analyze("com.acme")));
  }

  @Test
  void invisibleNestedNamesDoNotJustifyQualification() throws Exception {
    compile(
        "com.acme.domain.orders",
        "static class Other { static class Date {} } java.util.Date value;");
    assertEquals(
        Set.of("RequireTypeImports", "LombokSimpleConstructor"), findings(analyze("com.acme")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "(Object) null", "flag ? null : new Object()"})
  void preservesLiteralConditionalAndCastNullRejections(String expression) throws Exception {
    compile("com.acme.domain.orders", "Object value(boolean flag) { return " + expression + "; }");
    assertEquals(
        Set.of("DomainMethodsMustNotReturnNull", "LombokSimpleConstructor"),
        findings(analyze("com.acme")));
  }

  @Test
  void aGetMethodNameDoesNotImplyOptionalIdentity() throws Exception {
    compile(
        "com.acme.domain.orders", "Object get() { return this; } Object value() { return get(); }");
    assertEquals(Set.of("LombokSimpleConstructor"), findings(analyze("com.acme")));
  }

  @Test
  void optionalIdentityIsResolvedThroughCompiledConsumerMethods() throws Exception {
    compile(
        "com.acme.domain.orders",
        "Optional<Object> value() { return Optional.empty(); } Object read() { return value().get(); }");
    assertEquals(
        Set.of("AvoidOptionalGet", "LombokSimpleConstructor"), findings(analyze("com.acme")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "@SuppressWarnings(\"PMD.DomainMethodsMustNotReturnNull\") Object value() { return null; }",
        "Object value() { return null; } // NOPMD",
        "@javax.annotation.processing.Generated(\"handwritten\") static int value() { return 1; }"
      })
  void suppressionAndGeneratedMarkersCannotProduceApproval(String member) throws Exception {
    compile("com.acme.domain.orders", member);
    var report = analyze("com.acme");
    assertFalse(report.passed(), report.toString());
    assertFalse(report.violations().isEmpty(), report.toString());
  }

  @Test
  void wrongBasePackageCannotHideEverySource() throws Exception {
    compile("com.acme.domain.orders", "int value() { return 1; }");
    assertEquals(
        Set.of("SOURCE_INVENTORY", "LombokSimpleConstructor"), findings(analyze("org.wrong")));
    assertEquals(
        Set.of("LombokSimpleConstructor"),
        findings(analyze("com.acme")),
        "another request must not inherit the previous consumer configuration");
  }

  @Test
  void rejectsPackageAndDirectoryDisagreement() throws Exception {
    var source = compile("com.acme.domain.orders", "int value() { return 1; }");
    Files.move(source, sources.resolve("Fixture.java"));
    assertEquals(
        Set.of("SOURCE_INVENTORY", "LombokSimpleConstructor"), findings(analyze("com.acme")));
  }

  @Test
  void missingEmptyAndCorruptEvidenceIsRejected() throws Exception {
    assertThrows(IOException.class, () -> analyze("com.acme"));
    compile("com.acme.domain.orders", "int value() { return 1; }");
    var bytecode = classes.resolve("com/acme/domain/orders/Fixture.class");
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
    var source = compile("com.acme.domain.orders", "int value() { return 1; }");
    Files.writeString(source, "package com.acme.domain.orders; class Fixture { invalid Java");
    var result = analyze("com.acme");
    assertFalse(result.passed());
    assertFalse(result.errors().isEmpty());
  }

  @Test
  void unresolvedExternalTypesCannotSilentlyWeakenTypedSelectors() throws Exception {
    var source = compile("com.acme.domain.orders", "int value() { return 1; }");
    Files.writeString(
        source,
        "package com.acme.domain.orders; import missing.External; class Fixture { External value; }");
    assertEquals(
        Set.of("SOURCE_INVENTORY", "LombokSimpleConstructor"), findings(analyze("com.acme")));
  }

  @Test
  void sourceSymlinksAreRejected() throws Exception {
    var source = compile("com.acme.domain.orders", "int value() { return 1; }");
    Files.createSymbolicLink(sources.resolve("Alias.java"), source);
    assertTrue(
        assertThrows(IOException.class, () -> analyze("com.acme"))
            .getMessage()
            .contains("Symbolic link"));
  }

  @Test
  void inspectionDoesNotInitializeConsumerClasses() throws Exception {
    compile(
        "com.acme.domain.orders",
        "static { if (System.nanoTime() != 0) throw new AssertionError(\"INITIALIZED\"); }");
    assertEquals(
        Set.of("LombokSimpleConstructor", "ExceptionSelfFactory"), findings(analyze("com.acme")));
  }

  @Test
  void additionalSourceRootsAreAnalyzed() throws Exception {
    compile("com.acme.domain.orders", "int value() { return 1; }");
    var extra = Files.createDirectory(directory.resolve("extra"));
    var file = extra.resolve("com/acme/infra/orders/Extra.java");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file, "package com.acme.infra.orders; class Extra { static int value() { return 1; } }");
    var report =
        new SourceRules()
            .analyze(
                new SourceRequest(
                    "com.acme", BOUNDARY, List.of(sources, extra), List.of(), classes, List.of()));
    assertEquals(2, report.sourceFiles());
    assertEquals(
        Set.of("NoStaticMethods", "StaticExceptionFactory", "LombokSimpleConstructor"),
        findings(report));
  }

  @Test
  void missingClasspathEntriesAreReported() throws Exception {
    compile("com.acme.domain.orders", "int value() { return 1; }");
    var request =
        new SourceRequest(
            "com.acme",
            BOUNDARY,
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
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SourceRequest(
                "com.acme", BOUNDARY, List.of(sources), List.of(sources), classes, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SourceRequest(
                "com..acme", BOUNDARY, List.of(sources), List.of(), classes, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SourceRequest("com.acme", BOUNDARY, List.of(), List.of(), classes, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SourceRequest(
                "com.acme", "com..acme", List.of(sources), List.of(), classes, List.of()));
  }

  private SourceReport analyze(String base) throws IOException {
    return new SourceRules()
        .analyze(
            new SourceRequest(base, BOUNDARY, List.of(sources), List.of(), classes, List.of()));
  }

  private Path compile(String name, String members) throws IOException {
    var file = sources.resolve(name.replace('.', '/') + "/Fixture.java");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        "package " + name + "; import java.util.Optional; class Fixture { " + members + "\n}");
    var exit =
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "-proc:none",
                "--release",
                "24",
                "-d",
                classes.toString(),
                file.toString());
    assertEquals(0, exit, "The counterexample must compile before analysis: " + file);
    return file;
  }

  private Set<String> findings(SourceReport report) {
    assertTrue(report.errors().isEmpty(), report.toString());
    return report.violations().stream()
        .map(message -> message.split(" \\| ")[0])
        .collect(Collectors.toSet());
  }
}
