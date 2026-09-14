package io.github.jf3env.architecture.source;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.document.FileId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExceptionConstructionRuleTest {
  @Test
  void acceptsCustomSelfFactories() {
    try (var analysis = PmdAnalysis.create(TypedSourceRuleFixture.configuration())) {
      TypedSourceRuleFixture.addRules(analysis);
      analysis
          .files()
          .addSourceFile(
              FileId.fromPathLikeString("Sample.java"),
              """
          package com.ai.label.domain.probe.exceptions;
          class InvalidValueException extends RuntimeException {
            private InvalidValueException(String message) { super(message); }
            static InvalidValueException of(String message) { return new InvalidValueException(message); }
          }
          @lombok.NoArgsConstructor class Sample {
            void reject() { throw InvalidValueException.of("invalid"); }
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
        "throw new IllegalArgumentException(\"negative_counter\");",
        "throw new ArithmeticException(\"counter_overflow\");",
        "throw new RuntimeException(\"failure\");",
        "throw new java.io.IOException(\"failure\");",
        "java.util.function.Supplier<IllegalArgumentException> e = IllegalArgumentException::new; throw e.get();"
      })
  void rejectsGenericExceptionConstructionEvenInsideFactories(String statement) {
    try (var analysis = PmdAnalysis.create(TypedSourceRuleFixture.configuration())) {
      TypedSourceRuleFixture.addRules(analysis);
      analysis
          .files()
          .addSourceFile(
              FileId.fromPathLikeString("Sample.java"),
              """
          package com.ai.label.domain.probe.aggregate;
          @lombok.NoArgsConstructor class SampleFactory {
            void reject() throws Exception { %s }
          }
          """
                  .formatted(statement));
      var report = analysis.performAnalysisAndCollectReport();
      assertTrue(report.getProcessingErrors().isEmpty(), report.getProcessingErrors().toString());
      assertTrue(
          report.getViolations().stream()
              .anyMatch(violation -> violation.getDescription().contains("EXCEPTION_SELF_FACTORY")),
          report.getViolations().toString());
    }
  }
}
