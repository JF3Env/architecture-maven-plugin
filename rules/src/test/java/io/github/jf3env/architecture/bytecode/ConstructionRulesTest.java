package io.github.jf3env.architecture.bytecode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Construction ownership: one constructor and one construction owner per class. */
class ConstructionRulesTest {
  @TempDir Path directory;

  /** A public fixture type must live in a file carrying its own name. */
  private static String fileName(String source, int index) {
    var declaration =
        java.util.regex.Pattern.compile(
                "public\\s+(?:final\\s+)?(?:class|@?interface|record|enum)\\s+(\\w+)")
            .matcher(source);
    return declaration.find() ? declaration.group(1) + ".java" : "Fixture" + index + ".java";
  }

  @Test
  void acceptsOneOwnerCreatingSeveralProducts() throws IOException {
    assertTrue(
        this.check(
                """
        class Product {} class Other {}
        public class Wiring {
          public Object product() { return new Product(); }
          public Object other() { return new Other(); }
        }
        """)
            .isEmpty());
  }

  @Test
  void rejectsOverloadedConstructors() throws IOException {
    assertEquals(
        List.of("fixtures.Product: exactly one constructor is required"),
        this.check("class Product { Product() {} Product(int value) {} }"));
  }

  @Test
  void ignoresTheSyntheticSwitchMapJavacEmitsForAnEnumSwitch() throws IOException {
    assertTrue(
        this.check(
                """
        enum Mode { ON, OFF }
        public class Reader {
          public int read(Mode mode) {
            switch (mode) {
              case ON: return 1;
              default: return 0;
            }
          }
        }
        """)
            .isEmpty());
  }

  @Test
  void rejectsSeveralConstructionOwners() throws IOException {
    assertEquals(
        List.of("fixtures.Product: several construction owners [fixtures.First, fixtures.Second]"),
        this.check(
            """
        class Product {}
        class First { Product create() { return new Product(); } }
        class Second { Product create() { return new Product(); } }
        """));
  }

  @Test
  void constructorReferencesAlsoHaveAConstructionOwner() throws IOException {
    assertTrue(
        this.check(
                """
        class Product {}
        class First { java.util.function.Supplier<Product> create() { return Product::new; } }
        class Second { java.util.function.Supplier<Product> create() { return Product::new; } }
        """)
            .stream()
            .anyMatch(message -> message.contains("several construction owners")));
  }

  @Test
  void superInitializationDoesNotCountAsAnotherInstantiation() throws IOException {
    assertTrue(
        this.check(
                """
        class Base {}
        class First extends Base {}
        class Second extends Base {}
        class Wiring { Object create() { return new First(); } Object other() { return new Second(); } }
        """)
            .isEmpty());
  }

  @Test
  void recordsAndEnumsAreCarriersConstructedWhereTheyAreConsumed() throws IOException {
    var messages =
        this.check(
            """
        record Command(long count) { Command { if (count < 0) throw new IllegalArgumentException(); } Command(int count) { this((long) count); } }
        enum Kind { ONE, TWO }
        class First { Command create() { return new Command(1L); } }
        class Second { Command create() { return new Command(2); } }
        """);
    assertFalse(
        messages.stream().anyMatch(message -> message.contains("Command")), messages.toString());
    assertFalse(
        messages.stream().anyMatch(message -> message.contains("Kind")), messages.toString());
  }

  @Test
  void interfacesAnnotationsAndPackageMetadataDeclareNoConstruction() throws IOException {
    assertTrue(
        this.check(
                "public interface Contract {}",
                "public @interface Marker {}",
                "package fixtures; @Deprecated class Holder {}")
            .isEmpty());
  }

  private List<String> check(String... sources) throws IOException {
    var classes = Files.createTempDirectory(this.directory, "classes-");
    var arguments =
        new java.util.ArrayList<>(
            List.of("--release", "24", "-proc:none", "-d", classes.toString()));
    for (var index = 0; index < sources.length; index++) {
      var source = sources[index];
      var input = this.directory.resolve(fileName(source, index));
      Files.writeString(
          input, source.startsWith("package ") ? source : "package fixtures; " + source);
      arguments.add(input.toString());
    }
    var compiler = ToolProvider.getSystemJavaCompiler();
    var exit = compiler.run(null, null, null, arguments.toArray(String[]::new));
    assertTrue(exit == 0, "fixture must compile");
    var imported = new ClassFileImporter().importPath(classes);
    assertFalse(imported.isEmpty());
    return new ConstructionRules().violations(imported);
  }
}
