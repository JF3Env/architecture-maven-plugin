package io.github.jf3env.architecture.bytecode;

import java.util.Set;
import javax.lang.model.SourceVersion;

/** Explicit ARCH-12 identities; changing domains must not silently disable a policy. */
public record DomainPolicy(
    String authorityDomain,
    String forbiddenDomain,
    String aggregate,
    String repository,
    Set<String> services,
    String forbiddenRepository) {
  public DomainPolicy {
    for (var name : new String[] {authorityDomain, forbiddenDomain, forbiddenRepository}) {
      if (name == null || !SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
        throw new IllegalArgumentException(
            "A simple Java identifier is required for domain policy: " + name);
      }
    }
    if (authorityDomain.equals(forbiddenDomain)) {
      throw new IllegalArgumentException("The authority domain cannot also be forbidden");
    }
    for (var type : new String[] {aggregate, repository}) {
      if (type == null
          || !SourceVersion.isName(type, SourceVersion.RELEASE_24)
          || !type.contains(".")) {
        throw new IllegalArgumentException("A fully qualified authority type is required: " + type);
      }
    }
    services = Set.copyOf(services);
    if (services.isEmpty()) {
      throw new IllegalArgumentException("At least one authority service identity is required");
    }
    for (var service : services) {
      if (!SourceVersion.isIdentifier(service) || !service.endsWith("Service")) {
        throw new IllegalArgumentException(
            "A simple service identity ending in Service is required: " + service);
      }
    }
  }
}
