package io.github.jf3env.architecture.bytecode;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Field declarations establish ownership; method dependencies do not. */
final class StructuralOwnershipCondition extends ArchCondition<JavaClass> {
  private final String root;
  private final String domainPrefix;
  private final DomainPackageConvention convention;
  private final Map<String, List<JavaField>> fieldsByComponent = new HashMap<>();

  StructuralOwnershipCondition(String basePackage) {
    super("reside in the nearest common structural-owner package's role subpackage");
    root = basePackage + ".";
    domainPrefix = root + "domain.";
    convention = new DomainPackageConvention(basePackage);
  }

  private String commonPackage(String left, String right) {
    var common = left;
    while (!right.equals(common) && !right.startsWith(common + ".")) {
      var separator = common.lastIndexOf('.');
      if (separator < 0) return "";
      common = common.substring(0, separator);
    }
    return common;
  }

  private Optional<String> domainPackage(String packageName) {
    if (!packageName.startsWith(domainPrefix)) return Optional.empty();
    var separator = packageName.indexOf('.', domainPrefix.length());
    return Optional.of(separator < 0 ? packageName : packageName.substring(0, separator));
  }

  @Override
  public void init(Collection<JavaClass> types) {
    fieldsByComponent.clear();
    types.stream()
        .filter(type -> type.getPackageName().startsWith(root))
        .flatMap(type -> type.getFields().stream())
        .filter(field -> !field.getModifiers().contains(JavaModifier.STATIC))
        .filter(field -> !field.getModifiers().contains(JavaModifier.SYNTHETIC))
        .forEach(this::indexField);
  }

  private void indexField(JavaField field) {
    field.getType().getAllInvolvedRawTypes().stream()
        .map(type -> type.isArray() ? type.getBaseComponentType() : type)
        .filter(type -> !type.equals(field.getOwner()))
        .distinct()
        .forEach(
            type ->
                fieldsByComponent
                    .computeIfAbsent(type.getName(), ignored -> new ArrayList<>())
                    .add(field));
  }

  @Override
  public void check(JavaClass type, ConditionEvents events) {
    var domain = domainPackage(type.getPackageName());
    var name = type.getSimpleName();
    if (domain.isEmpty()
        || !type.isTopLevelClass()
        || type.isInterface()
        || type.isAnnotation()
        || name.equals("package-info")
        || name.endsWith("Service")) return;
    var role = convention.componentRole(type.getPackageName(), type.getSimpleName());
    var owners = fieldsByComponent.getOrDefault(type.getName(), List.of());
    if (role.isEmpty() || owners.isEmpty()) return;
    var evidence =
        owners.stream()
            .sorted(Comparator.comparing(JavaField::getFullName))
            .map(field -> field.getFullName() + " " + field.getSourceCodeLocation())
            .collect(Collectors.joining(", "));
    if (owners.stream()
        .anyMatch(field -> !domainPackage(field.getOwner().getPackageName()).equals(domain))) {
      events.add(
          SimpleConditionEvent.violated(
              type,
              type.getName()
                  + " has structural owners across domain or layer boundaries; no shared role"
                  + " package within its domain can contain this ownership. Fields: "
                  + evidence));
      return;
    }
    var common =
        owners.stream()
            .map(field -> field.getOwner().getPackageName())
            .reduce(this::commonPackage)
            .orElseThrow();
    var roleName = role.orElseThrow();
    var expected = common.endsWith("." + roleName) ? common : common + "." + roleName;
    if (!type.getPackageName().equals(expected)) {
      events.add(
          SimpleConditionEvent.violated(
              type,
              type.getName()
                  + " must reside in "
                  + expected
                  + " (nearest common structural-owner scope: "
                  + common
                  + ", role: "
                  + roleName
                  + "), but resides in "
                  + type.getPackageName()
                  + ". Fields: "
                  + evidence));
    }
  }
}
