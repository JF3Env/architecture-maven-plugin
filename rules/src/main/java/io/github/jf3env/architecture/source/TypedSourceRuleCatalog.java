package io.github.jf3env.architecture.source;

import java.util.List;
import net.sourceforge.pmd.lang.java.rule.AbstractJavaRule;

/**
 * The six typed source checkers, configured for one consumer's base package and persistence
 * boundary.
 */
public final class TypedSourceRuleCatalog {
  private TypedSourceRuleCatalog() {}

  public static List<AbstractJavaRule> load(String basePackage, String persistenceBoundary) {
    return List.of(
        new LombokConstructorRule(),
        new LombokAccessorRule(),
        new StaticExceptionFactoryRule(),
        new AggregateMutationRule(),
        new ExceptionConstructionRule(basePackage),
        new PersistenceBoundaryRule(persistenceBoundary));
  }
}
