package io.github.jf3env.architecture.bytecode;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Each domain owns exactly one top-level CDI composition class. */
final class DomainProducerRules {
  private static final String PRODUCES = "jakarta.enterprise.inject.Produces";
  private final String root;
  private final String domainPrefix;
  private final String producerPackage;

  DomainProducerRules(String basePackage) {
    root = basePackage + ".";
    domainPrefix = root + "domain.";
    producerPackage = root + "infra.domains.producers";
  }

  List<String> violations(JavaClasses classes) {
    var violations = new ArrayList<String>();
    Set<String> domains = new TreeSet<>();
    var producers = new ArrayList<JavaClass>();
    for (var type : classes) {
      if (type.getPackageName().startsWith(domainPrefix)) {
        domains.add(type.getPackageName().substring(domainPrefix.length()).split("\\.")[0]);
      }
      if (type.getSimpleName().endsWith("Producer") || hasProducedBeans(type)) producers.add(type);
    }
    for (var domain : domains) {
      var expected = producerName(domain);
      var count = producers.stream().filter(type -> type.getSimpleName().equals(expected)).count();
      if (count != 1) {
        violations.add(
            domain
                + ": exactly one domain producer is required in "
                + producerPackage
                + " named "
                + expected
                + ", found "
                + count);
      }
    }
    producers.forEach(producer -> checkProducer(producer, domains, violations));
    return List.copyOf(violations);
  }

  private void checkProducer(JavaClass producer, Set<String> domains, List<String> violations) {
    if (!producer.getPackageName().equals(producerPackage)) {
      violations.add(producer.getName() + ": producers belong in " + producerPackage);
    }
    var domain =
        domains.stream()
            .filter(name -> producerName(name).equals(producer.getSimpleName()))
            .findFirst();
    if (domain.isEmpty()
        || !producer.isTopLevelClass()
        || producer.isInterface()
        || producer.isAnnotation()) {
      violations.add(
          producer.getName()
              + ": domain producer must be a top-level class named <Domain>Producer for an existing domain");
    }
    if (!hasProducedBeans(producer)) {
      violations.add(producer.getName() + ": domain producer must declare a CDI @Produces member");
    }
    domain.ifPresent(
        name -> {
          producer.getMethods().stream()
              .filter(method -> method.isAnnotatedWith(PRODUCES))
              .forEach(
                  method -> checkProduct(producer, name, method.getRawReturnType(), violations));
          producer.getFields().stream()
              .filter(field -> field.isAnnotatedWith(PRODUCES))
              .forEach(field -> checkProduct(producer, name, field.getRawType(), violations));
        });
  }

  private String producerName(String domain) {
    return Character.toUpperCase(domain.charAt(0)) + domain.substring(1) + "Producer";
  }

  private void checkProduct(
      JavaClass producer, String domain, JavaClass product, List<String> violations) {
    var name = (product.isArray() ? product.getBaseComponentType() : product).getPackageName();
    if (name.startsWith(root)) {
      var segments = name.substring(root.length()).split("\\.");
      if (segments.length < 2
          || !Arrays.asList("domain", "infra", "persistence").contains(segments[0])
          || !segments[1].equals(domain)) {
        violations.add(
            producer.getName()
                + ": cannot produce a bean from another domain: "
                + product.getName());
      }
    }
  }

  private boolean hasProducedBeans(JavaClass type) {
    return type.getMethods().stream().anyMatch(method -> method.isAnnotatedWith(PRODUCES))
        || type.getFields().stream().anyMatch(field -> field.isAnnotatedWith(PRODUCES));
  }
}
