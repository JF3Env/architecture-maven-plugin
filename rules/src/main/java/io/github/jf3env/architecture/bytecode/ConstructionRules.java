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

/**
 * Every class has one constructor and one construction owner.
 *
 * <p>Records and enums are the target architecture's carriers (commands, results, values, events)
 * and are constructed where they are consumed, so they are not subject to the ownership policy.
 * Interfaces, annotations and package metadata declare no construction, and neither do the
 * synthetic classes javac emits on its own, such as the {@code Outer$1} holder of an enum switch
 * map, which has no constructor and no source a consumer could change.
 */
final class ConstructionRules {
  ConstructionRules() {}

  List<String> violations(JavaClasses classes) {
    var violations = new ArrayList<String>();
    var owners = creationOwners(classes);
    for (var type : classes) {
      if (type.isInterface()
          || type.isAnnotation()
          || type.isEnum()
          || type.isRecord()
          || type.getModifiers().contains(JavaModifier.SYNTHETIC)
          || type.getSimpleName().equals("package-info")) {
        continue;
      }
      if (type.getConstructors().size() != 1) {
        violations.add(type.getName() + ": exactly one constructor is required");
      }
      var creators = owners.getOrDefault(type.getName(), Set.of());
      if (creators.size() > 1) {
        violations.add(type.getName() + ": several construction owners " + names(creators));
      }
    }
    return List.copyOf(violations);
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

  private List<String> names(Set<JavaClass> classes) {
    return classes.stream().map(JavaClass::getName).sorted().toList();
  }
}
