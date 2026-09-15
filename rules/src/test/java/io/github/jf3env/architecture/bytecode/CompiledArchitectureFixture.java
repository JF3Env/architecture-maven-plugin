package io.github.jf3env.architecture.bytecode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;

/**
 * Valid Java counterexamples, isolated from unrelated architecture rules and stale class files.
 *
 * <p>A declaration keyed {@code <package>.package-info} holds the package annotations; it is
 * written before the package statement so that javac emits the package metadata class.
 */
final class CompiledArchitectureFixture {
  private CompiledArchitectureFixture() {}

  static JavaClasses compile(Path temporary, Map<String, String> declarations) throws IOException {
    var sources = Files.createTempDirectory(temporary, "sources-");
    var classes = Files.createTempDirectory(temporary, "classes-");
    var arguments =
        new ArrayList<>(List.of("--release", "24", "-proc:none", "-d", classes.toString()));
    for (var declaration : declarations.entrySet()) {
      var name = declaration.getKey();
      var path = sources.resolve(name.replace('.', '/') + ".java");
      Files.createDirectories(path.getParent());
      var packageName = name.substring(0, name.lastIndexOf('.'));
      var simpleName = name.substring(name.lastIndexOf('.') + 1);
      var statement = "package " + packageName + ";\n";
      Files.writeString(
          path,
          simpleName.equals("package-info")
              ? declaration.getValue() + "\n" + statement
              : statement + declaration.getValue());
      arguments.add(path.toString());
    }
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, arguments.toArray(String[]::new)),
        "counterexample must compile: " + declarations);
    var imported = new ClassFileImporter().importPath(classes);
    assertFalse(imported.isEmpty(), "counterexample class inventory must not be empty");
    declarations
        .keySet()
        .forEach(name -> assertTrue(imported.contain(name), "missing fixture type " + name));
    return imported;
  }

  static void accepts(ArchRule rule, JavaClasses classes) {
    var result = rule.evaluate(classes);
    assertFalse(result.hasViolation(), result.getFailureReport().toString());
  }

  static void rejects(ArchRule rule, JavaClasses classes, String... witnesses) {
    var result = rule.evaluate(classes);
    assertTrue(result.hasViolation(), "missing diagnostic for " + rule.getDescription());
    var details = String.join("\n", result.getFailureReport().getDetails());
    for (var witness : witnesses) {
      assertTrue(details.contains(witness), "missing witness " + witness + ":\n" + details);
    }
  }
}
