package io.github.jf3env.architecture.source;

import java.util.List;
import net.sourceforge.pmd.lang.java.rule.AbstractJavaRule;

/**
 * The typed source checkers that survive the context-first contract, configured for one consumer's
 * aggregate-root marker annotation.
 */
public final class TypedSourceRuleCatalog {
  private TypedSourceRuleCatalog() {}

  public static List<AbstractJavaRule> load(String aggregateRootAnnotation) {
    return List.of(new AggregateMutationRule(aggregateRootAnnotation));
  }
}
