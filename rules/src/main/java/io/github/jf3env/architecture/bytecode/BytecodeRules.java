package io.github.jf3env.architecture.bytecode;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Per-consumer, resource-only analysis of the complete compiled application. */
public final class BytecodeRules {
  public BytecodeRules() {}

  public BytecodeReport analyze(BytecodeRequest request) throws IOException {
    var expected = new ClassInventory().inspect(request.outputDirectory());
    var previous = Thread.currentThread().getContextClassLoader();
    try (var loader = classLoader(request)) {
      Thread.currentThread().setContextClassLoader(loader);
      var configuration = ArchConfiguration.get();
      if (!configuration.resolveMissingDependenciesFromClassPath()
          || configuration.getClassResolver().isPresent()) {
        throw new IllegalStateException("The mandatory classpath resolver cannot be overridden");
      }
      var classes = new ClassFileImporter().importPath(request.outputDirectory());
      verifyInventory(classes, expected);
      return evaluate(request.policy(), classes);
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  private URLClassLoader classLoader(BytecodeRequest request) throws IOException {
    var entries = new ArrayList<Path>();
    entries.add(request.outputDirectory());
    entries.addAll(request.classpath());
    var urls = new ArrayList<URL>();
    for (var entry : entries.stream().distinct().toList()) {
      if (!Files.exists(entry)) throw new IOException("Missing bytecode classpath entry: " + entry);
      urls.add(entry.toUri().toURL());
    }
    return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
  }

  private void verifyInventory(JavaClasses classes, Set<String> expected) {
    var imported = new TreeSet<String>();
    for (var type : classes) {
      imported.add(type.getName());
      if (!type.isFullyImported())
        throw new IllegalStateException("Incomplete class evidence: " + type.getName());
      for (var dependency : type.getDirectDependenciesFromSelf()) {
        var target = dependency.getTargetClass();
        target = target.isArray() ? target.getBaseComponentType() : target;
        if (!target.isPrimitive() && !target.isFullyImported()) {
          throw new IllegalStateException(
              "Unresolved class evidence: " + type.getName() + " depends on " + target.getName());
        }
      }
    }
    if (!expected.equals(imported)) {
      throw new IllegalStateException(
          "Incomplete architecture class inventory: expected "
              + expected
              + ", imported "
              + imported);
    }
  }

  private BytecodeReport evaluate(BytecodePolicy policy, JavaClasses classes) {
    var rules = new BytecodeRuleCatalog(policy).rules();
    var violations = new TreeSet<String>();
    var errors = new TreeSet<String>();
    for (var entry : rules.entrySet()) {
      try {
        var evaluated = entry.getValue().evaluate(classes);
        evaluated
            .getFailureReport()
            .getDetails()
            .forEach(
                detail ->
                    violations.add(
                        entry.getKey()
                            + " | "
                            + entry.getValue().getDescription()
                            + " | "
                            + detail));
      } catch (RuntimeException | AssertionError failure) {
        errors.add(entry.getKey() + " | " + failure.getMessage());
      }
    }
    try {
      new ConstructionRules(policy.basePackage())
          .violations(classes)
          .forEach(detail -> violations.add("CONSTRUCTION_POLICY | " + detail));
    } catch (RuntimeException failure) {
      errors.add("CONSTRUCTION_POLICY | " + failure);
    }
    return new BytecodeReport(
        classes.size(), List.copyOf(rules.keySet()), List.copyOf(violations), List.copyOf(errors));
  }
}
