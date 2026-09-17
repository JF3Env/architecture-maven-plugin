package io.github.jf3env.architecture.source.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jf3env.architecture.source.placement.PlacementRule.TypeFact;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.tools.ToolProvider;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.rule.RuleSet;

/**
 * Placement counterexamples must also be legal Java, independently of PMD's parser.
 *
 * <p>Folder existence is the calibration signal, so a fixture is a small tree rather than a single
 * snippet: every source is written under its own source root and package directory, compiled to
 * prove it is valid, then handed to the collector and to the analyzer exactly as the goal does.
 */
final class PlacementFixture {
  static final String MAIN = "src/main/java";
  static final String TEST = "src/test/java";

  private PlacementFixture() {}

  /** One compilation unit: where it lives, and what it declares below its package statement. */
  record Source(String root, String packageName, String fileName, String body) {
    static Source of(String packageName, String fileName, String body) {
      return new Source(MAIN, packageName, fileName, body);
    }
  }

  record Result(List<PlacementFinding> findings, List<TypeFact> types, Set<String> packages) {
    List<String> typeNames() {
      return this.types.stream().map(TypeFact::typeName).toList();
    }
  }

  static Result analyze(Path directory, List<Source> sources) throws IOException {
    var files = new ArrayList<Path>();
    for (var source : sources) {
      var file =
          directory
              .resolve(source.root())
              .resolve(source.packageName().replace('.', '/'))
              .resolve(source.fileName() + ".java");
      Files.createDirectories(file.getParent());
      Files.writeString(file, "package " + source.packageName() + ";\n" + source.body());
      files.add(file);
    }
    var classes = Files.createTempDirectory(directory, "classes-");
    var arguments =
        new ArrayList<String>(List.of("--release", "24", "-proc:none", "-d", classes.toString()));
    files.forEach(file -> arguments.add(file.toString()));
    var exit =
        ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, arguments.toArray(String[]::new));
    assertEquals(0, exit, "every fixture source must compile: " + sources);
    var rule = new PlacementRule();
    try (var analysis = PmdAnalysis.create(configuration())) {
      analysis.addRuleSet(RuleSet.forSingleRule(rule));
      files.forEach(file -> analysis.files().addFile(file));
      var report = analysis.performAnalysisAndCollectReport();
      assertTrue(report.getProcessingErrors().isEmpty(), report.getProcessingErrors().toString());
      assertTrue(
          report.getConfigurationErrors().isEmpty(), report.getConfigurationErrors().toString());
      assertTrue(
          report.getViolations().isEmpty(),
          "the collector must never report a violation of its own: " + report.getViolations());
    }
    assertEquals(files.size(), rule.checked(), "the collector must inspect every compilation unit");
    return new Result(
        PlacementAnalyzer.analyze(rule.types(), rule.packages()),
        List.copyOf(rule.types()),
        Set.copyOf(rule.packages()));
  }

  private static PMDConfiguration configuration() {
    var configuration = new PMDConfiguration();
    configuration.setDefaultLanguageVersion(
        LanguageRegistry.PMD.getLanguageById("java").getVersion("24"));
    configuration.setClassLoader(PlacementFixture.class.getClassLoader());
    configuration.setThreads(1);
    configuration.setIgnoreIncrementalAnalysis(true);
    configuration.setShowSuppressedViolations(true);
    return configuration;
  }
}
