package io.github.jf3env.architecture.bytecode;

import javax.lang.model.SourceVersion;

/** The consumer base package and its domain policy are scoped to one analysis. */
public record BytecodePolicy(String basePackage, DomainPolicy domain) {
  public BytecodePolicy {
    if (basePackage == null || !SourceVersion.isName(basePackage, SourceVersion.RELEASE_24)) {
      throw new IllegalArgumentException("A valid Java basePackage is required");
    }
    if (domain == null) {
      throw new IllegalArgumentException("An explicit domain authority policy is required");
    }
    var root = basePackage + ".domain." + domain.authorityDomain();
    if (!domain.aggregate().startsWith(root + ".aggregate.")
        || !domain
            .repository()
            .equals(
                root
                    + "."
                    + domain.repository().substring(domain.repository().lastIndexOf('.') + 1))) {
      throw new IllegalArgumentException(
          "Authority aggregate and root repository must belong to " + root);
    }
  }
}
