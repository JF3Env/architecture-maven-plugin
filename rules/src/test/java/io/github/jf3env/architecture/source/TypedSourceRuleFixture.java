package io.github.jf3env.architecture.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.java.rule.AbstractJavaRule;
import net.sourceforge.pmd.lang.rule.RuleSet;
import net.sourceforge.pmd.reporting.Report;

/** Source-rule counterexamples must also be legal Java, independently of PMD's parser. */
final class TypedSourceRuleFixture {
  static final String BASE_PACKAGE = "com.ai.label";
  static final String BOUNDARY = "com.ai.label.persistence.workspace.WorkspaceTransactions";

  private TypedSourceRuleFixture() {}

  static PMDConfiguration configuration() {
    var configuration = new PMDConfiguration();
    configuration.setDefaultLanguageVersion(
        LanguageRegistry.PMD.getLanguageById("java").getVersion("24"));
    configuration.setClassLoader(TypedSourceRuleFixture.class.getClassLoader());
    configuration.setThreads(1);
    configuration.setIgnoreIncrementalAnalysis(true);
    configuration.setShowSuppressedViolations(true);
    return configuration;
  }

  static void addRules(PmdAnalysis analysis) {
    TypedSourceRuleCatalog.load(BASE_PACKAGE, BOUNDARY)
        .forEach(rule -> analysis.addRuleSet(RuleSet.forSingleRule(rule)));
  }

  static void requireClean(Report report) {
    if (!report.getProcessingErrors().isEmpty() || !report.getConfigurationErrors().isEmpty()) {
      throw new IllegalStateException(
          "Source architecture analysis failed: "
              + report.getProcessingErrors()
              + " "
              + report.getConfigurationErrors());
    }
    if (!report.getViolations().isEmpty() || !report.getSuppressedViolations().isEmpty()) {
      throw new IllegalStateException(
          "Source architecture policy failed: " + report.getViolations());
    }
  }

  static void check(Path sourceRoot, Path classesRoot) throws IOException {
    if (!Files.isDirectory(sourceRoot) || !Files.isDirectory(classesRoot)) {
      throw new IllegalStateException(
          "Typed source policy requires source and compiled-class directories");
    }
    var configuration = configuration();
    configuration.prependAuxClasspath(classesRoot.toAbsolutePath().toString());
    try (var analysis = PmdAnalysis.create(configuration);
        var files = Files.walk(sourceRoot)) {
      var sources =
          files
              .filter(path -> path.toString().endsWith(".java"))
              .filter(path -> !path.getFileName().toString().equals("package-info.java"))
              .toList();
      if (sources.isEmpty()) {
        throw new IllegalStateException(
            "No production Java sources were inspected for typed source rules");
      }
      addRules(analysis);
      sources.forEach(path -> analysis.files().addFile(path));
      requireClean(analysis.performAnalysisAndCollectReport());
    }
  }

  static Report analyze(Path directory, String source, AbstractJavaRule... rules)
      throws IOException {
    var input = directory.resolve("Fixture.java");
    var classes = Files.createTempDirectory(directory, "classes-");
    Files.writeString(input, source);
    var exit =
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "--release",
                "24",
                "-proc:none",
                "-d",
                classes.toString(),
                input.toString());
    assertEquals(0, exit, "fixture must compile:\n" + source);
    try (var analysis = PmdAnalysis.create(configuration())) {
      if (rules.length == 0) {
        addRules(analysis);
      }
      for (var rule : rules) {
        analysis.addRuleSet(RuleSet.forSingleRule(rule));
      }
      analysis.files().addFile(input);
      var report = analysis.performAnalysisAndCollectReport();
      assertTrue(report.getProcessingErrors().isEmpty(), report.getProcessingErrors().toString());
      assertTrue(
          report.getConfigurationErrors().isEmpty(), report.getConfigurationErrors().toString());
      return report;
    }
  }
}
