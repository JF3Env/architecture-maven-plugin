package io.github.jf3env.architecture.bytecode;

import javax.lang.model.SourceVersion;

/** The consumer base package is scoped to one analysis; domain authorities are derived. */
public record BytecodePolicy(String basePackage) {
  public BytecodePolicy {
    if (basePackage == null || !SourceVersion.isName(basePackage, SourceVersion.RELEASE_24)) {
      throw new IllegalArgumentException("A valid Java basePackage is required");
    }
  }
}
