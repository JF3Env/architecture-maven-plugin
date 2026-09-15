package io.github.jf3env.architecture.bytecode;

import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.List;
import java.util.TreeSet;

/**
 * Derives the bounded contexts of one consumer from its compiled inventory, never from
 * configuration.
 */
final class BoundedContexts {
  private BoundedContexts() {}

  /** Every first-level segment below the base package other than the platform, sorted. */
  static List<String> derive(BytecodePolicy policy, JavaClasses classes) {
    var shape = policy.shape();
    var contexts = new TreeSet<String>();
    for (var type : classes) {
      shape
          .segmentOf(type.getPackageName())
          .filter(segment -> !segment.equals(policy.platformPackage()))
          .ifPresent(contexts::add);
    }
    if (contexts.isEmpty()) {
      throw new IllegalStateException(
          "No bounded context was found below "
              + policy.basePackage()
              + "; expected "
              + shape.description());
    }
    return List.copyOf(contexts);
  }
}
