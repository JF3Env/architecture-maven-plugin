package io.github.jf3env.architecture.iosp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.sourceforge.pmd.reporting.Report;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IospBackendScopeTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "domain.probe.aggregate",
        "domain.probe.value",
        "domain.probe.repository.value",
        "persistence.probe",
        "persistence.probe.mappers",
        "infra.probe.rest",
        "infra.probe.rest.dto"
      })
  void ordinaryBackendTypesCannotHideBehindTheirRoleOrName(String scope) {
    var report =
        this.analyze(
            "package com.ai.label."
                + scope
                + "; "
                + """
        interface Repository { int read(); }
        final class Ordinary {
          Repository repository;
          int run() { return repository.read() * 2; }
        }
        """);
    assertEquals(1, report.getViolations().size());
    assertTrue(report.getViolations().getFirst().getDescription().contains("IOSP_MIXED"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"domain.probe.value", "domain.probe.aggregate", "infra.probe.rest.dto"})
  void initializationKeepsOwnInvariantsWithoutManufacturingFactoryViolations(String scope) {
    var report =
        this.analyze(
            "package com.ai.label."
                + scope
                + "; "
                + """
        final class NonPositiveIncrementException extends RuntimeException {
          private NonPositiveIncrementException(String message) { super(message); }
          static NonPositiveIncrementException forAmount(int amount) {
            return new NonPositiveIncrementException("non_positive_increment");
          }
        }
        final class Positive {
          private final int value;
          Positive(int value) { validate(value); this.value = value; }
          static void validate(int value) {
            if (value <= 0) throw NonPositiveIncrementException.forAmount(value);
          }
        }
        final class Finite {
          private final double value;
          Finite(double value) {
            if (!Double.isFinite(value)) throw NonPositiveIncrementException.forAmount(0);
            this.value = value;
          }
        }
        """);
    assertTrue(report.getViolations().isEmpty(), report.getViolations().toString());
  }

  @Test
  void invariantValidationStillCannotMixDelegationAndInlineComputation() {
    var report =
        this.analyze(
            """
        package com.ai.label.domain.probe.value;
        final class Scaled {
          private final int value;
          Scaled(int value) { validate(value); this.value = value * 2; }
          static void validate(int value) {
            if (value <= 0) throw new IllegalArgumentException("positive_value_required");
          }
        }
        """);
    assertTrue(
        report.getViolations().stream().anyMatch(v -> v.getDescription().contains("IOSP_MIXED")));
  }

  @Test
  void aFileReadAndAMapperAreBothCoordinationWhileReadPlusArithmeticIsMixed() {
    var report =
        this.analyze(
            """
        package com.ai.label.persistence.probe;
        import java.io.IOException;
        import java.nio.file.Files;
        import java.nio.file.Path;
        final class Storage {
          int load(Path file) throws IOException { return decode(Files.readAllBytes(file)); }
          int decode(byte[] bytes) { return bytes.length; }
          long mixed(Path file) throws IOException { return Files.size(file) + 1; }
        }
        """);
    assertEquals(1, report.getViolations().size(), report.getViolations().toString());
    assertTrue(report.getViolations().getFirst().getDescription().contains("#mixed"));
    assertTrue(
        report.getViolations().getFirst().getDescription().contains("java.nio.file.Files#size"));
  }

  @Test
  void primitiveDataAlgorithmsRemainOperations() {
    var report =
        this.analyze(
            """
        package com.ai.label.persistence.probe;
        final class Decoding {
          int length(String value) { return Math.max(1, value.strip().length()); }
        }
        """);
    assertTrue(report.getViolations().isEmpty(), report.getViolations().toString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "int value = Math.abs(-input);",
        "{ input = Math.abs(input); }",
        "ProbeService() { action = () -> { input = 1; }; }",
        "enum Mode { ONLY(read() + 1) {}; Mode(int ignored) {} }"
      })
  void serviceInitializationAndEnumArgumentsCannotConcealPrimitiveWork(String member) {
    var report =
        this.analyze(
            """
        package com.ai.label.domain.probe;
        class ProbeService {
          int input; Runnable action;
          static int read() { return 1; }
        """
                + member
                + "}");
    assertFalse(report.getViolations().isEmpty(), member);
  }

  @ParameterizedTest
  @ValueSource(strings = {"() -> new Object()", "() -> { return new Object(); }", "Object::new"})
  void aStandaloneFieldFactoryHasTheSameRoleInEveryCallbackSyntax(String callback) {
    var report =
        this.analyze(
            """
        package com.ai.label.domain.probe;
        class CallbackService {
          java.util.function.Supplier<Object> factory =
        """
                + callback
                + "; }");
    assertTrue(report.getViolations().isEmpty(), report.getViolations().toString());
  }

  @Test
  void aFinalBuilderAllocationStillNeedsProofBeforeItCanBeAbsorbed() {
    var report =
        this.analyze(
            """
        package com.ai.label.domain.probe;
        final class Product { final int n; Product(int n) { this.n = n; } }
        final class Composer {
          int n;
          Composer() { System.nanoTime(); }
          Composer value(int n) { this.n = n; return this; }
          Product finish() { return new Product(n); }
        }
        class BuildService { Product make(int n) { return new Composer().value(n).finish(); } }
        """);
    assertTrue(
        report.getViolations().stream()
            .anyMatch(
                v ->
                    v.getDescription().contains("BuildService#make")
                        && v.getDescription().contains("IOSP_CONSTRUCTION_USE")),
        report.getViolations().toString());
  }

  @Test
  void compactRecordConstructorsAreExecutableScopesToo() {
    var report =
        this.analyze(
            """
        package com.ai.label.infra.probe;
        final class NonPositiveIncrementException extends RuntimeException {
          private NonPositiveIncrementException(String message) { super(message); }
          static NonPositiveIncrementException forAmount(int amount) {
            return new NonPositiveIncrementException("non_positive_increment");
          }
        }
        record Input(int count) {
          Input { validate(count); count = count * 2; }
          static void validate(int count) {
            if (count <= 0) throw NonPositiveIncrementException.forAmount(count);
          }
        }
        """);
    assertEquals(1, report.getViolations().size(), report.getViolations().toString());
    assertTrue(report.getViolations().getFirst().getDescription().contains("IOSP_MIXED"));
  }

  @Test
  void compactRecordInitializationRetainsItsRepresentationValidation() {
    var report =
        this.analyze(
            """
        package com.ai.label.infra.probe;
        final class NonPositiveIncrementException extends RuntimeException {
          private NonPositiveIncrementException(String message) { super(message); }
          static NonPositiveIncrementException forAmount(int amount) {
            return new NonPositiveIncrementException("non_positive_increment");
          }
        }
        record Input(int count) {
          Input { if (count <= 0) throw NonPositiveIncrementException.forAmount(count); }
        }
        """);
    assertTrue(report.getViolations().isEmpty(), report.getViolations().toString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "private final Object component = new Object();",
        "private final Object component; { component = new Object(); }",
        "private final Object component; ContainerValue() { component = new Object(); }"
      })
  void relocatingAnAllocationWithinInstanceInitializationCannotHideComposition(String member) {
    var report =
        this.analyze(
            "package com.ai.label.domain.probe.value; final class ContainerValue { "
                + member
                + " }");
    assertEquals(1, report.getViolations().size(), report.getViolations().toString());
    assertTrue(
        report.getViolations().getFirst().getDescription().contains("IOSP_COMPOSED_CONSTRUCTION"));
  }

  @Test
  void staticValueAndDeferredFactoriesDoNotConstructAnotherContainerInstance() {
    var report =
        this.analyze(
            """
        package com.ai.label.domain.probe.value;
        final class ContainerValue {
          private static final Object SHARED = new Object();
          private static final Object OTHER;
          static { OTHER = new Object(); }
          private final java.util.function.Supplier<Object> factory = () -> new Object();
        }
        """);
    assertTrue(report.getViolations().isEmpty(), report.getViolations().toString());
  }

  private Report analyze(String source) {
    var report =
        IospAnalysis.analyzeSource(source, this.getClass().getClassLoader(), "com.ai.label");
    assertTrue(report.getProcessingErrors().isEmpty(), report.getProcessingErrors().toString());
    assertTrue(
        report.getConfigurationErrors().isEmpty(), report.getConfigurationErrors().toString());
    return report;
  }
}
