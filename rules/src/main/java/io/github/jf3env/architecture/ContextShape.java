package io.github.jf3env.architecture;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.lang.model.SourceVersion;

/**
 * The context-first package grammar of one consumer, derived from its base package.
 *
 * <p>Every type lives in {@code <base>.<segment>.<layer>..} where the segment is a bounded context
 * or the shared platform and the layer is one of {@code api}, {@code domain}, {@code application}
 * and {@code infrastructure}. Two exceptions are owned as well: the bootstrap type {@code
 * Application} directly in the base package, and package metadata ({@code package-info}) at a
 * context root. Bounded contexts are never configured; they are the first-level segments below the
 * base package other than the platform.
 */
public final class ContextShape {
  public static final List<String> LAYERS =
      List.of("api", "domain", "application", "infrastructure");
  public static final String DEFAULT_PLATFORM_PACKAGE = "platform";
  public static final String BOOTSTRAP_TYPE = "Application";
  public static final String PACKAGE_INFO = "package-info";

  private final String basePackage;
  private final String platformPackage;
  private final Pattern layered;

  private ContextShape(String basePackage, String platformPackage) {
    this.basePackage = basePackage;
    this.platformPackage = platformPackage;
    layered =
        Pattern.compile(
            Pattern.quote(basePackage)
                + "\\.([^.]+)\\.(api|domain|application|infrastructure)(?:\\.[^.]+)*");
  }

  public static ContextShape of(String basePackage) {
    return of(basePackage, DEFAULT_PLATFORM_PACKAGE);
  }

  public static ContextShape of(String basePackage, String platformPackage) {
    if (basePackage == null || !SourceVersion.isName(basePackage, SourceVersion.RELEASE_24)) {
      throw new IllegalArgumentException("A valid Java basePackage is required");
    }
    var platform = platformPackage == null ? DEFAULT_PLATFORM_PACKAGE : platformPackage;
    if (!SourceVersion.isIdentifier(platform) || SourceVersion.isKeyword(platform)) {
      throw new IllegalArgumentException(
          "platformPackage must be a single package segment: " + platformPackage);
    }
    return new ContextShape(basePackage, platform);
  }

  public String basePackage() {
    return basePackage;
  }

  /** The shared kernel's segment below the base package. */
  public String platformPackage() {
    return platformPackage;
  }

  /** The fully qualified shared kernel package. */
  public String platform() {
    return basePackage + "." + platformPackage;
  }

  public String defaultAggregateRootAnnotation() {
    return platform() + ".domain.AggregateRoot";
  }

  public String defaultUnitOfWorkType() {
    return platform() + ".application.UnitOfWork";
  }

  public String defaultIntegrationEventType() {
    return platform() + ".domain.IntegrationEvent";
  }

  /** The context or platform segment of a package below the base package, if any. */
  public Optional<String> segmentOf(String packageName) {
    var prefix = basePackage + ".";
    if (!packageName.startsWith(prefix)) return Optional.empty();
    var rest = packageName.substring(prefix.length());
    var end = rest.indexOf('.');
    return Optional.of(end < 0 ? rest : rest.substring(0, end));
  }

  /** The layer of a package shaped {@code <base>.<segment>.<layer>..}, if any. */
  public Optional<String> layerOf(String packageName) {
    var matcher = layered.matcher(packageName);
    return matcher.matches() ? Optional.of(matcher.group(2)) : Optional.empty();
  }

  public boolean isLayerPackage(String packageName) {
    return layered.matcher(packageName).matches();
  }

  public boolean isDomainPackage(String packageName) {
    return layerOf(packageName).filter("domain"::equals).isPresent();
  }

  public boolean isApplicationPackage(String packageName) {
    return layerOf(packageName).filter("application"::equals).isPresent();
  }

  /** The composition root {@code <base>.<segment>.infrastructure.wiring..}. */
  public boolean isWiringPackage(String packageName) {
    return packageName.matches(
        Pattern.quote(basePackage) + "\\.[^.]+\\.infrastructure\\.wiring(?:\\.[^.]+)*");
  }

  public boolean isPlatformPackage(String packageName) {
    return packageName.equals(platform()) || packageName.startsWith(platform() + ".");
  }

  public boolean isContextRoot(String packageName) {
    return segmentOf(packageName)
        .filter(segment -> packageName.equals(basePackage + "." + segment))
        .isPresent();
  }

  /**
   * Explains why a source unit or class file is outside the consumer's ownership, if it is.
   *
   * @param packageName the unit's package
   * @param topLevelTypes simple names of the top-level types declared in the unit; {@code
   *     package-info} for package metadata
   */
  public Optional<String> ownershipViolation(String packageName, Collection<String> topLevelTypes) {
    if (isLayerPackage(packageName)) return Optional.empty();
    if (packageName.equals(basePackage)) {
      return topLevelTypes.stream()
              .allMatch(name -> name.equals(BOOTSTRAP_TYPE) || name.equals(PACKAGE_INFO))
          ? Optional.empty()
          : Optional.of(
              "only the bootstrap type "
                  + BOOTSTRAP_TYPE
                  + " may reside directly in "
                  + basePackage);
    }
    if (isContextRoot(packageName)) {
      return topLevelTypes.stream().allMatch(PACKAGE_INFO::equals)
          ? Optional.empty()
          : Optional.of("only package metadata may reside at the context root " + packageName);
    }
    return Optional.of("expected " + description());
  }

  /** The ownership grammar, for diagnostics. */
  public String description() {
    return basePackage + ".<context>.{" + String.join(",", LAYERS) + "}";
  }

  /**
   * A regular expression selecting every domain package of every context and the platform. It uses
   * only the XPath regular-expression dialect, so PMD's XPath rules can evaluate it.
   */
  public String domainPackagePattern() {
    return "^" + basePackage.replace(".", "\\.") + "\\.[^.]+\\.domain(\\..+)?$";
  }
}
