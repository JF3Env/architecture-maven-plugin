package io.github.jf3env.architecture.bytecode;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Derives each domain's local persistence authority from the compiled inventory. */
final class PersistenceAuthority {
  private PersistenceAuthority() {}

  static List<DomainAuthority> derive(BytecodePolicy policy, JavaClasses classes) {
    var prefix = policy.basePackage() + ".domain.";
    var domains = new TreeMap<String, Domain>();
    for (var type : classes) {
      var packageName = type.getPackageName();
      if (!type.isTopLevelClass()
          || type.getSimpleName().equals("package-info")
          || !packageName.startsWith(prefix)) {
        continue;
      }
      var domain = domainOf(packageName, prefix);
      var authority = domains.computeIfAbsent(domain, ignored -> new Domain(prefix, domain));
      if (packageName.equals(authority.aggregateRootsPackage) && isAggregateRoot(type)) {
        authority.aggregateRoots.add(type.getName());
      }
      if (type.isInterface() && type.getSimpleName().endsWith("Repository")) {
        authority.repositories.add(type.getName());
        if (packageName.equals(authority.domainRoot)) {
          authority.rootRepositories.add(type.getName());
        }
      }
    }
    var authorities = new ArrayList<DomainAuthority>();
    for (var entry : domains.entrySet()) {
      var authority = entry.getValue();
      authorities.add(
          new DomainAuthority(
              entry.getKey(),
              authority.aggregateRoots,
              authority.repositories,
              authority.rootRepositories));
    }
    return List.copyOf(authorities);
  }

  static String domainOf(String packageName, String prefix) {
    var rest = packageName.substring(prefix.length());
    return rest.contains(".") ? rest.substring(0, rest.indexOf('.')) : rest;
  }

  private static boolean isAggregateRoot(JavaClass type) {
    return !type.isInterface() && !type.isAnnotation() && !type.isEnum() && !type.isRecord();
  }

  private static final class Domain {
    private final String domainRoot;
    private final String aggregateRootsPackage;
    private final List<String> aggregateRoots = new ArrayList<String>();
    private final List<String> repositories = new ArrayList<String>();
    private final List<String> rootRepositories = new ArrayList<String>();

    private Domain(String prefix, String domain) {
      domainRoot = prefix + domain;
      aggregateRootsPackage = domainRoot + ".aggregate";
    }
  }
}
