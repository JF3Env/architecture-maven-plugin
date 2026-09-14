package io.github.jf3env.architecture.source;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.document.FileId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AggregateMutationRuleTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "this.counter = -1;",
        "this.counter = this.otherValue;",
        "this.counter = value + 1;",
        "this.counter = convert(value);",
        "this.counter = (int) value;",
        "value = -1; this.counter = value;",
        "value++; this.counter = value;",
        "--value; this.counter = value;",
        "value += 1; this.counter = value;",
        "this.counter = value--;",
        "other.counter = value;",
        "Runnable later = () -> this.counter = value; later.run();",
        "try { this.counter = value; } finally { convert(1); }",
        "if (value > 0) this.counter = value;"
      })
  void rejectsWritesOtherThanTheUnmodifiedGuardedParameter(String write) throws IOException {
    this.assertRejected("if (value < 0) throw NegativeCounterException.forValue(value); " + write);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "if (value-- < 0) throw NegativeCounterException.forValue(value); this.counter = value;",
        "if (++value < 0) throw NegativeCounterException.forValue(value); this.counter = value;",
        "if ((value = convert(value)) < 0) throw NegativeCounterException.forValue(value); this.counter = value;",
        "if (this.value < 0) throw NegativeCounterException.forValue(value); this.counter = value;",
        "if (convert(value) < 0) throw NegativeCounterException.forValue(value); this.counter = value;",
        "if (value > 10) { if (value < 0) throw NegativeCounterException.forValue(value); } this.counter = value;",
        "if (value < 0) return; this.counter = value;",
        "if (value < 0) throw NegativeCounterException.forValue(value); else this.counter = value;"
      })
  void rejectsMutationInGuardsAndUnprovenPaths(String body) throws IOException {
    this.assertRejected(body);
  }

  @Test
  void acceptsSeveralImmediateRejectingGuardsOnTheSameParameter() throws IOException {
    var report =
        this.analyze(
            """
        if (value < 0) throw NegativeCounterException.forValue(value);
        if (value > 100) { throw NegativeCounterException.forValue(value); }
        this.counter = value;
        """);
    assertTrue(report.getViolations().isEmpty(), report.getViolations().toString());
  }

  private void assertRejected(String body) throws IOException {
    var report = this.analyze(body);
    assertTrue(
        report.getViolations().stream()
            .anyMatch(
                violation -> violation.getDescription().contains("AGGREGATE_INVARIANT_SETTER")),
        body);
  }

  private net.sourceforge.pmd.reporting.Report analyze(String body) throws IOException {
    return TypedSourceRuleFixture.analyze(
        this.directory,
        """
        package com.ai.label.domain.workspace.aggregate;
        class NegativeCounterException extends RuntimeException {
          private NegativeCounterException(String message) { super(message); }
          static NegativeCounterException forValue(long value) {
            return new NegativeCounterException("negative_counter:" + value);
          }
        }
        class Fixture {
          private long counter;
          private long otherValue;
          private long value;
          private Fixture other;
          Fixture(long value) { setCounter(value); }
          long convert(long value) { return -value; }
          private void setCounter(long value) { %s }
        }
        """
            .formatted(body),
        new AggregateMutationRule());
  }

  @Test
  void acceptsAnInvariantGuardInsideTheSetterUsedByConstructionAndOperations() {
    try (var analysis = PmdAnalysis.create(TypedSourceRuleFixture.configuration())) {
      TypedSourceRuleFixture.addRules(analysis);
      analysis
          .files()
          .addSourceFile(
              FileId.fromPathLikeString("Sample.java"),
              """
          package com.ai.label.domain.workspace.aggregate;
          class NegativeCounterException extends RuntimeException {
            private NegativeCounterException(String message) { super(message); }
            static NegativeCounterException forValue(long value) {
              return new NegativeCounterException("negative_counter");
            }
          }
          class Sample {
            private long counter;
            Sample(long value) { setCounter(value); }
            void reset() { setCounter(0); }
            private void setCounter(long value) {
              if (value < 0) throw NegativeCounterException.forValue(value);
              this.counter = value;
            }
          }
          """);
      var report = analysis.performAnalysisAndCollectReport();
      assertTrue(report.getProcessingErrors().isEmpty(), report.getProcessingErrors().toString());
      assertTrue(report.getViolations().isEmpty(), report.getViolations().toString());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "@lombok.NoArgsConstructor class Sample { @lombok.Setter(lombok.AccessLevel.PRIVATE) long counter; }",
        "@lombok.Data class Sample { private long counter; }",
        "class Sample { private long counter; Sample(long value) {"
            + " if (value < 0) throw new IllegalArgumentException(); this.counter = value; } }",
        "@lombok.NoArgsConstructor class Sample { private long counter;"
            + " private void setCounter(long value) { this.counter = value; } }",
        """
          @lombok.NoArgsConstructor class Sample { private long counter;\
           private void setCounter(long value) { this.counter = value;\
           if (value < 0) throw new IllegalArgumentException(); } }""",
        "@lombok.NoArgsConstructor class Sample { private long counter;"
            + " void increment() { counter++; } }"
      })
  void rejectsMutationPathsThatBypassAValidatingPrivateSetter(String declaration) {
    try (var analysis = PmdAnalysis.create(TypedSourceRuleFixture.configuration())) {
      TypedSourceRuleFixture.addRules(analysis);
      analysis
          .files()
          .addSourceFile(
              FileId.fromPathLikeString("Sample.java"),
              "package com.ai.label.domain.probe.aggregate; " + declaration);
      var report = analysis.performAnalysisAndCollectReport();
      assertTrue(report.getProcessingErrors().isEmpty(), report.getProcessingErrors().toString());
      assertTrue(
          report.getViolations().stream()
              .anyMatch(
                  violation -> violation.getDescription().contains("AGGREGATE_INVARIANT_SETTER")),
          report.getViolations().toString());
    }
  }
}
