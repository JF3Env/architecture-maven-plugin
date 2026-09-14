package io.github.jf3env.architecture.iosp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.rule.RuleSet;
import net.sourceforge.pmd.reporting.Report;

/**
 * Mandatory IOSP source gate, independent of Maven and JUnit.
 *
 * <p>IOSP structural analysis inspects the consumer's primary handwritten source root; a consumer
 * with additional compile source roots is covered by the typed source checkers and XML rulesets,
 * not by this inventory.
 */
public final class IospAnalysis {
  private IospAnalysis() {}

  public static Report analyze(Path sourceRoot, Path classes, String basePackage)
      throws IOException {
    return analyze(sourceRoot, classes, List.of(), basePackage);
  }

  public static Report analyze(
      Path sourceRoot, Path classes, List<Path> generatedRoots, String basePackage)
      throws IOException {
    return analyze(sourceRoot, classes, generatedRoots, basePackage, List.of());
  }

  /**
   * Dependencies only supply type-resolution evidence; they are never part of the analysed
   * inventory.
   */
  public static Report analyze(
      Path sourceRoot,
      Path classes,
      List<Path> generatedRoots,
      String basePackage,
      List<Path> classpath)
      throws IOException {
    if (!Files.isDirectory(sourceRoot) || !Files.isDirectory(classes)) {
      throw new IllegalStateException("IOSP: source and compiled-class directories are required");
    }
    var inventory = IospSources.inspect(sourceRoot, classes, generatedRoots, basePackage);
    var sources = inventory.sources();
    var configuration = configuration();
    var entries = new ArrayList<String>();
    entries.add(classes.toAbsolutePath().toString());
    classpath.forEach(entry -> entries.add(entry.toAbsolutePath().toString()));
    configuration.prependAuxClasspath(String.join(File.pathSeparator, entries));
    try (var analysis = PmdAnalysis.create(configuration)) {
      var rule =
          new IospRule(configuration.getClassLoader(), basePackage, inventory.applicationTypes());
      analysis.addRuleSet(RuleSet.forSingleRule(rule));
      sources.forEach(path -> analysis.files().addFile(path));
      var report = analysis.performAnalysisAndCollectReport();
      if (rule.checkedMethods() == 0) {
        throw new IllegalStateException(
            "IOSP analyzed no backend executable scopes; refusing an empty gate");
      }
      return report;
    }
  }

  public static Report analyzeSource(String source, ClassLoader loader, String basePackage) {
    return analyzeSource(source, loader, basePackage, Set.of());
  }

  public static Report analyzeSource(
      String source, ClassLoader loader, String basePackage, Set<String> applicationTypes) {
    var configuration = configuration();
    configuration.setClassLoader(loader);
    try (var analysis = PmdAnalysis.create(configuration)) {
      analysis.addRuleSet(
          RuleSet.forSingleRule(new IospRule(loader, basePackage, applicationTypes)));
      analysis.files().addSourceFile(FileId.fromPathLikeString("Fixture.java"), source);
      return analysis.performAnalysisAndCollectReport();
    }
  }

  public static void requireClean(Report report) {
    if (!report.getProcessingErrors().isEmpty() || !report.getConfigurationErrors().isEmpty()) {
      throw new IllegalStateException(
          "IOSP analysis failed: "
              + report.getProcessingErrors()
              + " "
              + report.getConfigurationErrors());
    }
    if (!report.getViolations().isEmpty() || !report.getSuppressedViolations().isEmpty()) {
      throw new IllegalStateException(
          "IOSP requires zero violations; found " + report.getViolations().size());
    }
  }

  private static PMDConfiguration configuration() {
    var configuration = new PMDConfiguration();
    configuration.setDefaultLanguageVersion(
        LanguageRegistry.PMD.getLanguageById("java").getVersion("24"));
    configuration.setClassLoader(IospAnalysis.class.getClassLoader());
    configuration.setThreads(1);
    configuration.setIgnoreIncrementalAnalysis(true);
    configuration.setShowSuppressedViolations(true);
    return configuration;
  }
}
