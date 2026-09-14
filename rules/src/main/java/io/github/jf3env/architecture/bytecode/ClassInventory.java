package io.github.jf3env.architecture.bytecode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Parse every application class before trusting an importer's potentially partial result. */
final class ClassInventory {
  ClassInventory() {}

  Set<String> inspect(Path directory) throws IOException {
    if (!Files.isDirectory(directory))
      throw new IOException("Missing application classes directory: " + directory);
    var names = new TreeSet<String>();
    try (var files = Files.walk(directory)) {
      for (var file : files.sorted().toList()) {
        if (Files.isSymbolicLink(file))
          throw new IOException("Symbolic link cannot establish class inventory: " + file);
        if (!file.toString().endsWith(".class")) continue;
        try {
          var reader = new ClassReader(Files.readAllBytes(file));
          reader.accept(
              new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                  return new MethodVisitor(Opcodes.ASM9) {};
                }
              },
              0);
          var name = reader.getClassName().replace('/', '.');
          if (!names.add(name)) throw new IllegalStateException("Duplicate class " + name);
        } catch (RuntimeException | IOException failure) {
          throw new IOException(
              "Cannot inspect class file " + file + ": " + failure.getMessage(), failure);
        }
      }
    }
    if (names.isEmpty()) throw new IOException("No application classes were inspected");
    return Set.copyOf(names);
  }
}
