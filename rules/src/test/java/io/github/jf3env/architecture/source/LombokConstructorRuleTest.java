package io.github.jf3env.architecture.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.rule.RuleSet;
import net.sourceforge.pmd.reporting.Report;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LombokConstructorRuleTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "class Sample { public Sample() {} }",
        "class Sample { private Sample() { super(); ; } }",
        "class Sample { final String text; Sample(String text) { this.text = text; } }",
        "class Sample { String text; Sample(String value) { text = value; } }",
        "class Sample { int a; long b; Sample(int a, long b) { this.a = a; this.b = b; } }",
        "class Sample {}",
        "@lombok.Getter class Sample {}",
        "@lombok.NoArgsConstructor class Sample { Sample() {} }",
        "class Sample { @lombok.Generated Sample() {} }",
        "@SuppressWarnings(\"PMD\") class Sample { Sample() {} }"
      })
  void rejectsHandwrittenOrImplicitSimpleConstructors(String source) {
    var report = analyze(source);
    assertEquals(1, report.getViolations().size(), report.getViolations().toString());
    assertTrue(
        report.getViolations().getFirst().getDescription().contains("LOMBOK_SIMPLE_CONSTRUCTOR"));
    assertThrows(IllegalStateException.class, () -> TypedSourceRuleFixture.requireClean(report));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "@lombok.NoArgsConstructor class Sample {}",
        "@lombok.RequiredArgsConstructor class Sample { final String text; }",
        "@lombok.AllArgsConstructor class Sample { String text; int number; }",
        "@lombok.Value class Sample { String text; }",
        "@lombok.Data class Sample { final String text; }",
        "import lombok.RequiredArgsConstructor; @RequiredArgsConstructor class Sample { final int number; }",
        "class Sample { final int number; Sample(int number) {"
            + " if (number < 0) throw new IllegalArgumentException(); this.number = number; } }",
        "class Sample { final int number; Sample(int number) { this.number = Math.abs(number); } }",
        "class Sample extends RuntimeException { Sample(String message) { super(message); } }",
        "class Sample { final int number; Sample() { this(1); }"
            + " Sample(int number) { this.number = Math.abs(number); } }",
        "interface Sample {}",
        "@interface Sample {}",
        "record Sample(int number) {}"
      })
  void acceptsLombokAndConstructorsWithCustomLogic(String source) {
    var report = analyze(source);
    assertTrue(report.getViolations().isEmpty(), report.getViolations().toString());
    TypedSourceRuleFixture.requireClean(report);
  }

  @Test
  void alsoChecksNestedClasses() {
    var report =
        analyze("@lombok.NoArgsConstructor class Sample { static class Nested { Nested() {} } }");
    assertEquals(1, report.getViolations().size());
  }

  @Test
  void doesNotConfuseAnAssignmentToAnotherObjectWithOwnFieldInitialization() {
    var report =
        analyze(
            "class Sample { int number;"
                + " Sample(Sample other, int number) { other.number = number; } }");
    assertTrue(report.getViolations().isEmpty(), report.getViolations().toString());
  }

  @Test
  void rejectsEmptyEvidence() {
    assertThrows(
        IllegalStateException.class, () -> TypedSourceRuleFixture.check(directory, directory));
  }

  private static Report analyze(String source) {
    try (var analysis = PmdAnalysis.create(TypedSourceRuleFixture.configuration())) {
      analysis.addRuleSet(RuleSet.forSingleRule(new LombokConstructorRule()));
      analysis.files().addSourceFile(FileId.fromPathLikeString("Sample.java"), source);
      var report = analysis.performAnalysisAndCollectReport();
      assertTrue(report.getProcessingErrors().isEmpty(), report.getProcessingErrors().toString());
      assertTrue(
          report.getConfigurationErrors().isEmpty(), report.getConfigurationErrors().toString());
      return report;
    }
  }
}
