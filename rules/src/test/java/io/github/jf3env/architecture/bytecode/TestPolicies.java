package io.github.jf3env.architecture.bytecode;

final class TestPolicies {
  private TestPolicies() {}

  static BytecodePolicy reference() {
    return new BytecodePolicy("com.ai.label");
  }

  static BytecodePolicy orders(String base) {
    return new BytecodePolicy(base);
  }
}
