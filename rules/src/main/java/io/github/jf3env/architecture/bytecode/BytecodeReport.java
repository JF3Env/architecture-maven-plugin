package io.github.jf3env.architecture.bytecode;

import java.util.List;

public record BytecodeReport(
    int classFiles, List<String> rules, List<String> violations, List<String> errors) {
  public BytecodeReport {
    rules = List.copyOf(rules);
    violations = List.copyOf(violations);
    errors = List.copyOf(errors);
  }

  public boolean passed() {
    return classFiles > 0 && !rules.isEmpty() && violations.isEmpty() && errors.isEmpty();
  }
}
