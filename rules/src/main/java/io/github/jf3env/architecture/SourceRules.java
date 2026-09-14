package io.github.jf3env.architecture;

import io.github.jf3env.architecture.source.TypedSourceRuleCatalog;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.rule.RuleSet;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;

/** One isolated, complete source-rule execution for a consumer module. */
public final class SourceRules {
  public SourceRules() {}

  public SourceReport analyze(SourceRequest request) throws IOException {
    var sources = sources(request);
    requireClasses(request.classesDirectory());
    try (var loader = classLoader(request)) {
      var configuration = new PMDConfiguration();
      configuration.setDefaultLanguageVersion(
          LanguageRegistry.PMD.getLanguageById("java").getVersion("24"));
      configuration.setClassLoader(loader);
      configuration.setThreads(1);
      configuration.setIgnoreIncrementalAnalysis(true);
      configuration.setShowSuppressedViolations(true);
      var catalog = new SourceRuleCatalog(request.basePackage());
      var sets = catalog.load();
      var scope = new SourceScopeRule(request);
      var typed = TypedSourceRuleCatalog.load(request.basePackage(), request.persistenceBoundary());
      try (var analysis = PmdAnalysis.create(configuration)) {
        sets.forEach(analysis::addRuleSet);
        analysis.addRuleSet(RuleSet.forSingleRule(scope));
        typed.forEach(rule -> analysis.addRuleSet(RuleSet.forSingleRule(rule)));
        sources.forEach(path -> analysis.files().addFile(path));
        var report = analysis.performAnalysisAndCollectReport();
        var identities = new ArrayList<>(catalog.identities(sets));
        typed.forEach(rule -> identities.add(rule.getName()));
        return result(report, sources.size(), scope.visited(), List.copyOf(identities));
      }
    }
  }

  private List<Path> sources(SourceRequest request) throws IOException {
    var sources = new TreeSet<Path>();
    for (var root : request.sourceRoots()) {
      if (!Files.isDirectory(root)) {
        throw new IOException("Missing handwritten source directory: " + root);
      }
      try (var files = Files.walk(root)) {
        for (var path : files.toList()) {
          if (Files.isSymbolicLink(path)) {
            throw new IOException("Symbolic link cannot establish source provenance: " + path);
          }
          if (Files.isRegularFile(path)
              && path.toString().endsWith(".java")
              && request.generatedRoots().stream().noneMatch(path::startsWith)) {
            sources.add(path);
          }
        }
      }
    }
    if (sources.isEmpty()) {
      throw new IOException("Empty handwritten source inventory");
    }
    return List.copyOf(sources);
  }

  private void requireClasses(Path directory) throws IOException {
    if (!Files.isDirectory(directory)) {
      throw new IOException("Compiled production classes are required: " + directory);
    }
    try (var files = Files.walk(directory)) {
      var classes = files.filter(path -> path.toString().endsWith(".class")).toList();
      if (classes.isEmpty()) {
        throw new IOException("Empty compiled-class inventory: " + directory);
      }
      for (var path : classes) {
        if (Files.isSymbolicLink(path)) {
          throw new IOException("Symbolic link cannot establish class provenance: " + path);
        }
        try {
          var model = ClassFile.of().parse(Files.readAllBytes(path));
          model.methods().forEach(method -> method.code().ifPresent(code -> code.elementList()));
        } catch (RuntimeException failure) {
          throw new IOException("Cannot inspect class file " + path, failure);
        }
      }
    }
  }

  private URLClassLoader classLoader(SourceRequest request) throws IOException {
    var entries = new ArrayList<Path>();
    entries.add(request.classesDirectory());
    entries.addAll(request.classpath());
    var urls = new ArrayList<URL>();
    for (var entry : entries.stream().distinct().toList()) {
      if (!Files.exists(entry)) {
        throw new IOException("Missing analysis classpath entry: " + entry);
      }
      urls.add(entry.toUri().toURL());
    }
    return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
  }

  private SourceReport result(Report report, int expected, int visited, List<String> identities) {
    var violations = new TreeSet<String>();
    report.getViolations().forEach(violation -> violations.add(diagnostic(violation)));
    report
        .getSuppressedViolations()
        .forEach(
            suppressed ->
                violations.add("SUPPRESSED " + diagnostic(suppressed.getRuleViolation())));
    var errors = new TreeSet<String>();
    report.getProcessingErrors().forEach(error -> errors.add(error.getDetail()));
    report.getConfigurationErrors().forEach(error -> errors.add(error.toString()));
    if (visited != expected) {
      errors.add("Incomplete source analysis: expected " + expected + ", inspected " + visited);
    }
    return new SourceReport(visited, identities, List.copyOf(violations), List.copyOf(errors));
  }

  private String diagnostic(RuleViolation violation) {
    return violation.getRule().getName()
        + " | "
        + violation.getFileId().getAbsolutePath()
        + ":"
        + violation.getBeginLine()
        + " | "
        + violation.getDescription();
  }
}
