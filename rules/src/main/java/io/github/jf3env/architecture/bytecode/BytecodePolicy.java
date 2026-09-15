package io.github.jf3env.architecture.bytecode;

import io.github.jf3env.architecture.ContextShape;
import java.util.List;
import javax.lang.model.SourceVersion;

/**
 * One consumer's context-first policy, scoped to a single analysis.
 *
 * <p>Bounded contexts are never configured: they are derived from the compiled inventory as the
 * first-level packages under {@code basePackage}, excluding {@code platformPackage}. Only the
 * shared kernel's marker types are named, because they live in the consumer's own platform and
 * cannot be referenced by this library. Every marker defaults to the plan's location below the
 * platform package.
 */
public record BytecodePolicy(
    String basePackage,
    String platformPackage,
    String unitOfWorkType,
    String integrationEventType,
    String aggregateRootAnnotation,
    List<String> frameworkPackages) {

  /** The framework surface a bounded context's domain may never touch. */
  public static final List<String> DEFAULT_FRAMEWORK_PACKAGES =
      List.of(
          "jakarta..",
          "io.quarkus..",
          "org.hibernate..",
          "com.fasterxml..",
          "org.apache.sis..",
          "org.mapstruct..");

  public BytecodePolicy {
    var shape = ContextShape.of(basePackage, platformPackage);
    platformPackage = shape.platformPackage();
    unitOfWorkType = defaulted(unitOfWorkType, shape.defaultUnitOfWorkType());
    integrationEventType = defaulted(integrationEventType, shape.defaultIntegrationEventType());
    aggregateRootAnnotation =
        defaulted(aggregateRootAnnotation, shape.defaultAggregateRootAnnotation());
    frameworkPackages =
        frameworkPackages == null || frameworkPackages.isEmpty()
            ? DEFAULT_FRAMEWORK_PACKAGES
            : List.copyOf(frameworkPackages);
    for (var framework : frameworkPackages) {
      if (framework == null || framework.isBlank()) {
        throw new IllegalArgumentException("A framework package pattern cannot be blank");
      }
    }
  }

  /** The policy a consumer gets when it configures nothing beyond its base package. */
  public static BytecodePolicy of(String basePackage) {
    return new BytecodePolicy(basePackage, null, null, null, null, null);
  }

  public ContextShape shape() {
    return ContextShape.of(basePackage, platformPackage);
  }

  private static String defaulted(String configured, String fallback) {
    var value = configured == null || configured.isBlank() ? fallback : configured;
    if (!SourceVersion.isName(value, SourceVersion.RELEASE_24)) {
      throw new IllegalArgumentException(
          "A valid fully qualified marker type is required: " + value);
    }
    return value;
  }
}
