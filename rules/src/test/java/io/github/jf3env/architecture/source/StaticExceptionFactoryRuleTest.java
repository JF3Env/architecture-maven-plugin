package io.github.jf3env.architecture.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.rule.RuleSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StaticExceptionFactoryRuleTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "static MissingException of(String message) { return new MissingException(message); }",
        "static MissingException missing() { return new MissingException(\"missing\"); }"
      })
  void acceptsDirectSelfFactories(String method) {
    assertEquals(
        0,
        violations(
            "class MissingException extends RuntimeException {"
                + " MissingException(String message) { super(message); } "
                + method
                + " }"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "static int helper() { return 1; }",
        "static RuntimeException other() { return new RuntimeException(); }",
        "static MissingException of(String message) { return new MissingException(message.trim()); }",
        "static MissingException of(String message) { System.out.println(message);"
            + " return new MissingException(message); }",
        "static MissingException cached() { return null; }",
        "static synchronized MissingException of(String message) { return new MissingException(message); }",
        "static RuntimeException widened(String message) { return new MissingException(message); }"
      })
  void rejectsStaticLogicEvenInsideExceptions(String method) {
    assertEquals(
        1,
        violations(
            "class MissingException extends RuntimeException {"
                + " MissingException(String message) { super(message); } "
                + method
                + " }"));
  }

  @Test
  void aSuffixDoesNotMakeAClassAnException() {
    assertEquals(
        1,
        violations(
            "class FakeException { static FakeException of() { return new FakeException(); } }"));
  }

  @Test
  void reportsTheIntendedNegativeDiagnostic() {
    try (var analysis = PmdAnalysis.create(TypedSourceRuleFixture.configuration())) {
      analysis.addRuleSet(RuleSet.forSingleRule(new StaticExceptionFactoryRule()));
      analysis
          .files()
          .addSourceFile(
              FileId.fromPathLikeString("Fixture.java"),
              "class Fixture { static int helper() { return 1; } }");
      var report = analysis.performAnalysisAndCollectReport();
      assertEquals(1, report.getViolations().size());
      var description = report.getViolations().getFirst().getDescription();
      assertTrue(description.startsWith("STATIC_EXCEPTION_FACTORY: helper"), description);
      assertTrue(
          description.contains("must only return a new instance of its own Exception subtype"),
          description);
    }
  }

  private static int violations(String source) {
    try (var analysis = PmdAnalysis.create(TypedSourceRuleFixture.configuration())) {
      analysis.addRuleSet(RuleSet.forSingleRule(new StaticExceptionFactoryRule()));
      analysis.files().addSourceFile(FileId.fromPathLikeString("Fixture.java"), source);
      var report = analysis.performAnalysisAndCollectReport();
      assertTrue(report.getProcessingErrors().isEmpty(), report.getProcessingErrors().toString());
      assertTrue(
          report.getConfigurationErrors().isEmpty(), report.getConfigurationErrors().toString());
      return report.getViolations().size();
    }
  }
}
