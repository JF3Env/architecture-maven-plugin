package io.github.jf3env.architecture.bytecode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.ArchConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BytecodeInventoryTest {
  @TempDir Path temporary;

  @Test
  void emptyMissingCorruptAndDuplicateInventoriesFailClosed() throws IOException {
    var inventory = new ClassInventory();
    assertThrows(IOException.class, () -> inventory.inspect(temporary.resolve("missing")));
    assertThrows(IOException.class, () -> inventory.inspect(temporary));
    var output = compile("class Probe {}", List.of());
    assertEquals(Set.of("consumer.example.orders.domain.Probe"), inventory.inspect(output));
    Files.write(output.resolve("Broken.class"), new byte[] {0, 1, 2});
    var corrupt = assertThrows(IOException.class, () -> inventory.inspect(output));
    assertTrue(corrupt.getMessage().contains("Cannot inspect class file"));
    assertTrue(corrupt.getMessage().contains("Broken.class"));
    Files.delete(output.resolve("Broken.class"));
    Files.copy(
        output.resolve("consumer/example/orders/domain/Probe.class"),
        output.resolve("Duplicate.class"));
    assertTrue(
        assertThrows(IOException.class, () -> inventory.inspect(output))
            .getMessage()
            .contains("Duplicate class consumer.example.orders.domain.Probe"));
  }

  @Test
  void symlinksCannotHideAnotherClassInventory() throws IOException {
    var output = compile("class Probe {}", List.of());
    Files.createSymbolicLink(output.resolve("foreign"), temporary);
    assertTrue(
        assertThrows(IOException.class, () -> new ClassInventory().inspect(output))
            .getMessage()
            .contains("Symbolic link"));
  }

  @Test
  void dependencyResolutionIsScopedAndDependenciesAreNotApplicationInputs() throws IOException {
    var external = temporary.resolve("External.java");
    Files.writeString(external, "package external.library; public class External {}");
    var dependency = Files.createDirectory(temporary.resolve("dependency"));
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, "-proc:none", "-d", dependency.toString(), external.toString()));
    var output = compile("class Probe { external.library.External field; }", List.of(dependency));
    var previous = Thread.currentThread().getContextClassLoader();
    var policy = TestPolicies.orders("consumer.example");
    var report =
        new BytecodeRules().analyze(new BytecodeRequest(policy, output, List.of(dependency)));
    assertEquals(
        1, report.classFiles(), "dependency classes must not expand the application inventory");
    assertSame(previous, Thread.currentThread().getContextClassLoader());
    var unresolved =
        assertThrows(
            IllegalStateException.class,
            () -> new BytecodeRules().analyze(new BytecodeRequest(policy, output, List.of())));
    assertTrue(
        unresolved.getMessage().contains("external.library.External"), unresolved.toString());
    assertSame(previous, Thread.currentThread().getContextClassLoader());
  }

  @Test
  void missingClasspathEntriesRetainTheirIdentity() throws IOException {
    var output = compile("class Probe {}", List.of());
    var request =
        new BytecodeRequest(
            TestPolicies.orders("consumer.example"),
            output,
            List.of(temporary.resolve("missing.jar")));
    assertTrue(
        assertThrows(IOException.class, () -> new BytecodeRules().analyze(request))
            .getMessage()
            .contains("missing.jar"));
  }

  @Test
  void consumerClassesAreNeverInitialized() throws IOException {
    var output =
        compile(
            "class Probe { static { if (System.nanoTime() != 0) throw new AssertionError(\"INITIALIZED\"); } }",
            List.of());
    assertEquals(
        1,
        new BytecodeRules()
            .analyze(
                new BytecodeRequest(TestPolicies.orders("consumer.example"), output, List.of()))
            .classFiles());
  }

  @Test
  void weakenedResolutionIsRejectedWithoutChangingTheCallersConfiguration() throws IOException {
    var output = compile("class Probe {}", List.of());
    var request = new BytecodeRequest(TestPolicies.orders("consumer.example"), output, List.of());
    ArchConfiguration.withThreadLocalScope(
        configuration -> {
          configuration.setResolveMissingDependenciesFromClassPath(false);
          assertThrows(IllegalStateException.class, () -> new BytecodeRules().analyze(request));
          assertFalse(ArchConfiguration.get().resolveMissingDependenciesFromClassPath());
        });
  }

  private Path compile(String declaration, List<Path> dependencies) throws IOException {
    var source = temporary.resolve("Probe.java");
    Files.writeString(source, "package consumer.example.orders.domain; " + declaration);
    var output = Files.createTempDirectory(temporary, "classes-");
    var arguments =
        new java.util.ArrayList<>(
            List.of("--release", "24", "-proc:none", "-d", output.toString()));
    if (!dependencies.isEmpty()) {
      arguments.addAll(
          List.of(
              "-classpath",
              dependencies.stream()
                  .map(Path::toString)
                  .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator))));
    }
    arguments.add(source.toString());
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, arguments.toArray(String[]::new)));
    return output;
  }
}
