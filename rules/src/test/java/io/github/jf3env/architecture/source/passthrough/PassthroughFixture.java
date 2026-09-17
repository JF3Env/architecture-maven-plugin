package io.github.jf3env.architecture.source.passthrough;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.rule.RuleSet;

/**
 * Pass-through counterexamples must also be legal Java, independently of PMD's parser.
 *
 * <p>The collector reads sources alone, so the fixture compiles the snippet only to prove it is
 * valid, then runs the collector over the same file and replays the analyzer on the collected facts
 * exactly as the goal does.
 */
final class PassthroughFixture {
  static final String PACKAGE = "package com.acme.orders.domain;\n";

  private PassthroughFixture() {}

  static List<PassthroughFinding> analyze(Path directory, String source) throws IOException {
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
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classes.toString(),
                input.toString());
    assertEquals(0, exit, "fixture must compile:\n" + source);
    var rule = new PassthroughRule();
    try (var analysis = PmdAnalysis.create(configuration())) {
      analysis.addRuleSet(RuleSet.forSingleRule(rule));
      analysis.files().addFile(input);
      var report = analysis.performAnalysisAndCollectReport();
      assertTrue(report.getProcessingErrors().isEmpty(), report.getProcessingErrors().toString());
      assertTrue(
          report.getConfigurationErrors().isEmpty(), report.getConfigurationErrors().toString());
      assertTrue(
          report.getViolations().isEmpty(),
          "the collector must never report a violation of its own: " + report.getViolations());
    }
    assertTrue(rule.checked() > 0, "the collector must inspect at least one method body");
    return PassthroughAnalyzer.analyze(rule.methods(), rule.calls());
  }

  private static PMDConfiguration configuration() {
    var configuration = new PMDConfiguration();
    configuration.setDefaultLanguageVersion(
        LanguageRegistry.PMD.getLanguageById("java").getVersion("24"));
    configuration.setClassLoader(PassthroughFixture.class.getClassLoader());
    configuration.setThreads(1);
    configuration.setIgnoreIncrementalAnalysis(true);
    configuration.setShowSuppressedViolations(true);
    return configuration;
  }
}
