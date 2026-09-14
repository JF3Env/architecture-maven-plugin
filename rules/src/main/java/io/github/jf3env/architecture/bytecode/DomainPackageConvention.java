package io.github.jf3env.architecture.bytecode;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/** Original capability/role grammar with an instance-local consumer prefix. */
final class DomainPackageConvention {
  private static final Map<String, String> ROLE_SUFFIXES =
      Map.of(
          "command",
          "Command",
          "query",
          "Query",
          "result",
          "Result",
          "value",
          "Value",
          "projection",
          "Projection",
          "exceptions",
          "Exception",
          "factory",
          "Factory");
  private final String domainPrefix;

  DomainPackageConvention(String basePackage) {
    domainPrefix = basePackage + ".domain.";
  }

  boolean isServiceLocation(String packageName) {
    var segments = domainSegments(packageName);
    return isCapabilityRoot(segments, segments.length);
  }

  Optional<String> serviceTypeViolation(
      String packageName, String simpleName, boolean isException) {
    var segments = domainSegments(packageName);
    if (simpleName.endsWith("Service")) {
      return isCapabilityRoot(segments, segments.length)
          ? Optional.empty()
          : Optional.of("a Service must reside directly in domain.<area>.services.<service-name>");
    }
    var firstRole = firstRoleIndex(segments);
    var sharedServiceRole = firstRole == 2 && segments[1].equals("services");
    if (firstRole < 2
        || (!sharedServiceRole && !isCapabilityRoot(segments, firstRole))
        || Arrays.stream(segments, firstRole, segments.length)
            .anyMatch(segment -> segment.equals("service") || segment.equals("services"))) {
      return Optional.of(
          "expected a service capability or shared service scope followed by role packages;"
              + " only Service classes may reside at capability roots");
    }
    var role = segments[segments.length - 1];
    var suffix = ROLE_SUFFIXES.get(role);
    if (suffix == null) {
      return Optional.of("unknown role package '" + role + "'; expected " + ROLE_SUFFIXES.keySet());
    }
    if (!simpleName.endsWith(suffix)) {
      return Optional.of("package '" + role + "' requires suffix '" + suffix + "'");
    }
    if (isException != role.equals("exceptions")) {
      return Optional.of("only Exception subtypes belong in exceptions, with suffix Exception");
    }
    return Optional.empty();
  }

  Optional<String> componentRole(String packageName, String simpleName) {
    return ROLE_SUFFIXES.entrySet().stream()
        .filter(entry -> simpleName.endsWith(entry.getValue()))
        .map(Map.Entry::getKey)
        .findFirst()
        .or(
            () -> {
              var terminal = packageName.substring(packageName.lastIndexOf('.') + 1);
              return ROLE_SUFFIXES.containsKey(terminal) ? Optional.of(terminal) : Optional.empty();
            });
  }

  private int firstRoleIndex(String[] segments) {
    for (var index = 2; index < segments.length; index++) {
      if (ROLE_SUFFIXES.containsKey(segments[index])) return index;
    }
    return -1;
  }

  boolean isOwnRootContractAccess(
      String originPackage, String originName, String targetPackage, boolean targetIsInterface) {
    var origin = domainSegments(originPackage);
    return originName.endsWith("Service")
        && isCapabilityRoot(origin, origin.length)
        && targetIsInterface
        && targetPackage.equals(domainPrefix + origin[0]);
  }

  private boolean isCapabilityRoot(String[] segments, int length) {
    if (length != 3 || !segments[1].equals("services")) return false;
    return Arrays.stream(segments, 2, length).noneMatch(this::isRoleSegment);
  }

  private boolean isRoleSegment(String segment) {
    return ROLE_SUFFIXES.containsKey(segment)
        || segment.equals("service")
        || segment.equals("services");
  }

  private String[] domainSegments(String name) {
    return name.startsWith(domainPrefix)
        ? name.substring(domainPrefix.length()).split("\\.")
        : new String[0];
  }
}
