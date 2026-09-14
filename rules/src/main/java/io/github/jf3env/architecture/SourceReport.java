package io.github.jf3env.architecture;

import java.util.List;

/** Rule identity and diagnostics, independent of PMD, Maven, and JUnit APIs. */
public record SourceReport(
    int sourceFiles, List<String> rules, List<String> violations, List<String> errors) {
  public SourceReport {
    rules = List.copyOf(rules);
    violations = List.copyOf(violations);
    errors = List.copyOf(errors);
  }

  public boolean passed() {
    return sourceFiles > 0 && !rules.isEmpty() && violations.isEmpty() && errors.isEmpty();
  }
}
