package io.github.jf3env.architecture.bytecode;

final class TestPolicies {
  private TestPolicies() {}

  static BytecodePolicy reference() {
    return BytecodePolicy.of("com.ai.label");
  }

  static BytecodePolicy orders(String base) {
    return BytecodePolicy.of(base);
  }
}
