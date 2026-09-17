package io.github.jf3env.architecture;

import java.util.List;

/**
 * Rule identity and diagnostics, independent of PMD, Maven, and JUnit APIs.
 *
 * <p>{@code advisories} carries WARNING-level findings that describe a smell rather than a violated
 * contract. They are reported and written to the analysis report, but {@link #passed()}
 * deliberately ignores them: an advisory can never fail a build.
 */
public record SourceReport(
    int sourceFiles,
    List<String> rules,
    List<String> violations,
    List<String> errors,
    List<String> advisories) {
  public SourceReport {
    rules = List.copyOf(rules);
    violations = List.copyOf(violations);
    errors = List.copyOf(errors);
    advisories = List.copyOf(advisories);
  }

  public SourceReport(
      int sourceFiles, List<String> rules, List<String> violations, List<String> errors) {
    this(sourceFiles, rules, violations, errors, List.of());
  }

  /** Advisories are never part of the outcome. */
  public boolean passed() {
    return sourceFiles > 0 && !rules.isEmpty() && violations.isEmpty() && errors.isEmpty();
  }
}
