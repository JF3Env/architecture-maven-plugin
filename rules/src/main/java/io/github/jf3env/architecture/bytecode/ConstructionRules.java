package io.github.jf3env.architecture.bytecode;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Allocation owners and factory consumers remain distinct from field ownership. */
final class ConstructionRules {
  private static final List<String> ROLE_SUFFIXES =
      List.of(
          "Service",
          "Repository",
          "Resource",
          "Mapper",
          "Builder",
          "Factory",
          "Producer",
          "Command",
          "Query",
          "Result",
          "Value",
          "Projection",
          "Exception");
  private final DomainProducerRules producers;

  ConstructionRules(String basePackage) {
    producers = new DomainProducerRules(basePackage);
  }

  List<String> violations(JavaClasses classes) {
    var violations = new ArrayList<String>();
    var owners = creationOwners(classes);
    for (var type : classes) {
      if (type.getSimpleName().endsWith("Factory") && !type.isAnnotation()) {
        checkFactoryLocation(type, violations);
        var contracts = factoryContracts(type);
        if (type.isInterface() || contracts.isEmpty()) {
          checkFactory(type, classes, violations);
        } else if (contracts.size() != 1) {
          violations.add(
              type.getName()
                  + ": exactly one replaceable factory contract is required, found "
                  + names(contracts));
        } else if (!type.getSimpleName().endsWith(contracts.iterator().next().getSimpleName())) {
          violations.add(
              type.getName()
                  + ": strategy must prefix its factory contract name "
                  + names(contracts));
        }
      }
      if (type.isInterface() || type.isAnnotation() || type.getSimpleName().equals("package-info"))
        continue;
      if (type.getConstructors().size() != 1) {
        violations.add(type.getName() + ": exactly one constructor is required");
      }
      var creators = owners.getOrDefault(type.getName(), Set.of());
      if (creators.size() > 1) {
        violations.add(type.getName() + ": several construction owners " + names(creators));
      }
    }
    checkDomainProducts(classes, owners, violations);
    violations.addAll(producers.violations(classes));
    return List.copyOf(violations);
  }

  private void checkDomainProducts(
      JavaClasses classes, Map<String, Set<JavaClass>> owners, List<String> violations) {
    for (var product : classes) {
      if (!isDomain(product.getPackageName())) continue;
      owners.getOrDefault(product.getName(), Set.of()).stream()
          .filter(owner -> !isDomain(owner.getPackageName()))
          .filter(ignored -> !isWiredCollaborator(product))
          .forEach(
              owner ->
                  violations.add(
                      product.getName()
                          + ": domain products are created in the domain, but "
                          + owner.getName()
                          + " instantiates it"));
    }
  }

  private boolean isWiredCollaborator(JavaClass product) {
    return product.getSimpleName().endsWith("Service")
        || product.getAllRawInterfaces().stream()
            .anyMatch(
                contract ->
                    isDomain(contract.getPackageName())
                        && contract.getPackageName().endsWith(".factory"));
  }

  private boolean isDomain(String name) {
    return name.equals("domain")
        || name.startsWith("domain.")
        || name.contains(".domain.")
        || name.endsWith(".domain");
  }

  private String consumerBase(String consumer) {
    return ROLE_SUFFIXES.stream()
        .filter(suffix -> consumer.endsWith(suffix) && consumer.length() > suffix.length())
        .findFirst()
        .map(suffix -> consumer.substring(0, consumer.length() - suffix.length()))
        .orElse(consumer);
  }

  private Map<String, Set<JavaClass>> creationOwners(JavaClasses classes) {
    Map<String, Set<JavaClass>> owners = new HashMap<>();
    for (var owner : classes) {
      try {
        var source = Path.of(owner.getSource().orElseThrow().getUri());
        new ClassReader(Files.readAllBytes(source))
            .accept(
                new ClassVisitor(Opcodes.ASM9) {
                  @Override
                  public MethodVisitor visitMethod(
                      int access,
                      String name,
                      String descriptor,
                      String signature,
                      String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                      @Override
                      public void visitTypeInsn(int opcode, String type) {
                        if (opcode == Opcodes.NEW) record(type);
                      }

                      @Override
                      public void visitInvokeDynamicInsn(
                          String name, String descriptor, Handle bootstrap, Object... arguments) {
                        for (var argument : arguments) {
                          if (argument instanceof Handle handle
                              && handle.getTag() == Opcodes.H_NEWINVOKESPECIAL) {
                            record(handle.getOwner());
                          }
                        }
                      }

                      private void record(String name) {
                        owners
                            .computeIfAbsent(
                                name.replace('/', '.'), ignored -> new LinkedHashSet<>())
                            .add(owner);
                      }
                    };
                  }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      } catch (IOException failure) {
        throw new UncheckedIOException(
            "Cannot inspect construction ownership for " + owner.getName(), failure);
      }
    }
    return owners;
  }

  private void checkFactoryLocation(JavaClass factory, List<String> violations) {
    if (!factory.isTopLevelClass()) {
      violations.add(factory.getName() + ": factories must be top-level and grouped by consumer");
    }
    if (!factory.getPackageName().endsWith(".factory")) {
      violations.add(
          factory.getName() + ": factories reside in the factory package of their consumer");
    }
  }

  private Set<JavaClass> factoryContracts(JavaClass strategy) {
    var contracts = new LinkedHashSet<JavaClass>();
    strategy.getAllRawInterfaces().stream()
        .filter(contract -> contract.getSimpleName().endsWith("Factory"))
        .forEach(contracts::add);
    contracts.removeIf(
        contract ->
            strategy.getAllRawInterfaces().stream()
                .anyMatch(other -> other.getAllRawInterfaces().contains(contract)));
    return contracts;
  }

  private void checkFactory(JavaClass contract, JavaClasses classes, List<String> violations) {
    var family = new LinkedHashSet<JavaClass>();
    family.add(contract);
    for (var type : classes) {
      if (type.getAllRawInterfaces().contains(contract)
          || type.getAllRawSuperclasses().contains(contract)) family.add(type);
    }
    var consumers = new LinkedHashSet<JavaClass>();
    for (var type : classes) {
      if (contract.equals(type)) continue;
      if (type.getFields().stream()
          .filter(field -> !field.getModifiers().contains(JavaModifier.STATIC))
          .anyMatch(
              field ->
                  field.getType().getAllInvolvedRawTypes().stream()
                      .map(used -> used.isArray() ? used.getBaseComponentType() : used)
                      .anyMatch(used -> family.contains(used) && !used.equals(type))))
        consumers.add(type);
      type.getMethodCallsFromSelf().stream()
          .filter(
              call -> family.contains(call.getTargetOwner()) && !call.getTargetOwner().equals(type))
          .forEach(call -> consumers.add(type));
      type.getMethodReferencesFromSelf().stream()
          .filter(
              reference ->
                  family.contains(reference.getTargetOwner())
                      && !reference.getTargetOwner().equals(type))
          .forEach(reference -> consumers.add(type));
    }
    if (consumers.size() != 1) {
      violations.add(
          contract.getName() + ": exactly one consumer is required, found " + names(consumers));
    } else {
      var consumer = consumers.iterator().next();
      var expectedName = consumerBase(consumer.getSimpleName()) + "Factory";
      if (!contract.getSimpleName().equals(expectedName)) {
        violations.add(contract.getName() + ": factory contract must be named " + expectedName);
      }
      var expectedPackage = consumer.getPackageName() + ".factory";
      if (!contract.getPackageName().equals(expectedPackage)) {
        violations.add(
            "FACTORY_CONSUMER_PACKAGE: consumer "
                + consumer.getName()
                + ", contract "
                + contract.getName()
                + ", actual package "
                + contract.getPackageName()
                + ", expected package "
                + expectedPackage);
      }
    }
  }

  private List<String> names(Set<JavaClass> classes) {
    return classes.stream().map(JavaClass::getName).sorted().toList();
  }
}
