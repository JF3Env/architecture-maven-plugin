package io.github.jf3env.architecture.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import net.sourceforge.pmd.reporting.Report;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LombokAccessorRuleTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "long counter; public long getCounter() { return this.counter; }",
        "long counter; protected long getCounter() { return counter; }",
        "long counter; private final long getCounter() { return counter; }",
        "long counter; long getCounter() { return counter; }",
        "boolean ready; public boolean isReady() { return ready; }",
        "boolean isReady; public boolean isReady() { return isReady; }",
        "Boolean ready; public Boolean getReady() { return ready; }",
        "String uRL; public String getURL() { return uRL; }",
        "long counter; public void setCounter(long value) { this.counter = value; }",
        "long counter; private void setCounter(long counter) { this.counter = counter; }",
        "boolean isReady; void setReady(boolean value) { isReady = value; }",
        "long counter; public long counter() { return counter; }",
        "long counter; public Fixture counter(long value) { this.counter = value; return this; }",
        "long counter; public Fixture setCounter(long value) { counter = value; return this; }",
        "long counter; public void counter(long value) { counter = value; }",
        "@lombok.Getter long counter; public long getCounter() { return counter; }",
        "@lombok.experimental.Accessors(prefix = {}) long counter; long getCounter() { return counter; }",
        "long counter; @lombok.Generated public long getCounter() { return counter; }",
        "long counter; @SuppressWarnings(\"PMD\") public long getCounter() { return counter; }",
        "long counter; @SuppressWarnings(\"PMD.LombokSimpleAccessor\")"
            + " public long getCounter() { return counter; }",
        "long counter; public long getCounter() { return counter; } // NOPMD\n",
        "@lombok.experimental.Accessors(prefix = \"m_\") long m_counter;"
            + " public long getCounter() { return m_counter; }",
        "@lombok.experimental.Accessors(prefix = {\"_\", \"m\"}) long mCounter;"
            + " public void setCounter(long value) { mCounter = value; }",
        "@lombok.NoArgsConstructor static class Nested { long counter; long getCounter() { return counter; } }"
      })
  void mandatorySourceGateRejectsMechanicalHandwrittenAccessors(String members) throws IOException {
    var report = this.analyze("@lombok.NoArgsConstructor class Fixture { " + members + " }");
    assertEquals(1, report.getViolations().size(), report.getViolations().toString());
    var message = report.getViolations().getFirst().getDescription();
    assertTrue(message.contains("LOMBOK_SIMPLE_ACCESSOR"), message);
    assertTrue(
        message.contains("Fixture") && (message.contains("@Getter") || message.contains("@Setter")),
        message);
    assertTrue(
        report.getSuppressedViolations().isEmpty(), report.getSuppressedViolations().toString());
    assertThrows(IllegalStateException.class, () -> TypedSourceRuleFixture.requireClean(report));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "@lombok.Getter @lombok.Setter @lombok.NoArgsConstructor class Fixture { long counter; }",
        "@lombok.Data class Fixture { long counter; }",
        "@lombok.Getter @lombok.Setter @lombok.experimental.Accessors(fluent = true)"
            + " @lombok.NoArgsConstructor class Fixture { long counter; }",
        """
          @lombok.NoArgsConstructor class Fixture { long counter;\
           private void setCounter(long value) { if (value < 0)\
           throw Invalid.of(); this.counter = value; } }
          class Invalid extends RuntimeException { private Invalid(String message) { super(message); }\
           static Invalid of() { return new Invalid("invalid"); } }""",
        "@lombok.NoArgsConstructor class Fixture { long counter; long getCounter() { return counter + 1; } }",
        "@lombok.NoArgsConstructor class Fixture { long[] values; long[] getValues() { return values.clone(); } }",
        "@lombok.NoArgsConstructor class Fixture { long counter; Fixture other;"
            + " long getCounter() { return other.counter; } }",
        "@lombok.NoArgsConstructor class Fixture { long counter; synchronized long getCounter() { return counter; } }",
        "@lombok.NoArgsConstructor class Fixture { long counter;"
            + " @lombok.Synchronized long getCounter() { return counter; } }",
        "@lombok.NoArgsConstructor class Fixture { long counter;"
            + " long getCounter() throws java.io.IOException { return counter; } }",
        "@lombok.NoArgsConstructor class Fixture { long counter; <T> long getCounter() { return counter; } }",
        "@lombok.NoArgsConstructor class Fixture { int counter; long getCounter() { return counter; } }",
        "@lombok.NoArgsConstructor class Fixture { long counter; long current() { return counter; } }",
        "@lombok.NoArgsConstructor class Fixture { Boolean ready; Boolean isReady() { return ready; } }",
        "@lombok.NoArgsConstructor class Fixture { long counter;"
            + " void setCounter(long counter) { counter = counter; } }",
        "@lombok.NoArgsConstructor class Fixture { long counter;"
            + " void setCounter(long value) { this.counter = Math.abs(value); } }",
        "@lombok.NoArgsConstructor class Fixture { long counter; Fixture other;"
            + " void setCounter(long value) { other.counter = value; } }",
        "@lombok.NoArgsConstructor class Base { protected long counter; }"
            + " @lombok.NoArgsConstructor class Fixture extends Base { long getCounter() { return counter; } }",
        "record Fixture(long counter) {}",
        "interface Fixture { long getCounter(); }"
      })
  void acceptsGeneratedAccessorsAndNonMechanicalContracts(String declaration) throws IOException {
    var report = this.analyze(declaration);
    assertTrue(report.getViolations().isEmpty(), report.getViolations().toString());
    TypedSourceRuleFixture.requireClean(report);
  }

  @Test
  void classLevelLombokAnnotationsCannotHideHandwrittenMembers() throws IOException {
    var report =
        this.analyze(
            """
        @lombok.Getter @lombok.NoArgsConstructor
        @lombok.experimental.Accessors(prefix = "m_", fluent = true)
        class Fixture { long m_counter; long counter() { return m_counter; } }
        """);
    assertEquals(1, report.getViolations().size(), report.getViolations().toString());
    assertTrue(
        report.getViolations().getFirst().getDescription().contains("LOMBOK_SIMPLE_ACCESSOR"));
  }

  @Test
  void lombokActuallyGeneratesTheSignaturesVisibilityAndFluentContracts() throws Exception {
    var source =
        """
        package com.ai.label.infra.probe;
        @lombok.Getter @lombok.Setter @lombok.NoArgsConstructor
        class Bean {
          private long counter;
          private boolean isReady;
          private Boolean active;
          @lombok.Getter(lombok.AccessLevel.PROTECTED)
          @lombok.Setter(lombok.AccessLevel.PRIVATE) private String uRL;
        }
        @lombok.Getter @lombok.Setter @lombok.NoArgsConstructor
        @lombok.experimental.Accessors(fluent = true, chain = true, makeFinal = true, prefix = "m_")
        class Fluent { private long m_counter; }
        @lombok.Setter @lombok.NoArgsConstructor
        @lombok.experimental.Accessors(fluent = true, chain = false)
        class Unchained { private long counter; }
        """;
    var input = this.directory.resolve("GeneratedControls.java");
    var classes = Files.createDirectory(this.directory.resolve("generated"));
    Files.writeString(input, source);
    var exit =
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "--release",
                "24",
                "-processor",
                "lombok.launch.AnnotationProcessorHider$AnnotationProcessor",
                "-d",
                classes.toString(),
                input.toString());
    assertEquals(0, exit, "Lombok replacement controls must compile with annotation processing");
    try (var loader =
        new URLClassLoader(new URL[] {classes.toUri().toURL()}, this.getClass().getClassLoader())) {
      var bean = loader.loadClass("com.ai.label.infra.probe.Bean");
      assertEquals(long.class, bean.getDeclaredMethod("getCounter").getReturnType());
      assertEquals(void.class, bean.getDeclaredMethod("setCounter", long.class).getReturnType());
      assertEquals(boolean.class, bean.getDeclaredMethod("isReady").getReturnType());
      assertEquals(void.class, bean.getDeclaredMethod("setReady", boolean.class).getReturnType());
      assertEquals(Boolean.class, bean.getDeclaredMethod("getActive").getReturnType());
      assertTrue(Modifier.isProtected(bean.getDeclaredMethod("getURL").getModifiers()));
      assertTrue(Modifier.isPrivate(bean.getDeclaredMethod("setURL", String.class).getModifiers()));
      var fluent = loader.loadClass("com.ai.label.infra.probe.Fluent");
      assertEquals(fluent, fluent.getDeclaredMethod("counter", long.class).getReturnType());
      assertTrue(Modifier.isFinal(fluent.getDeclaredMethod("counter").getModifiers()));
      var unchained = loader.loadClass("com.ai.label.infra.probe.Unchained");
      assertEquals(void.class, unchained.getDeclaredMethod("counter", long.class).getReturnType());
    }
    var report = TypedSourceRuleFixture.analyze(this.directory, source);
    assertTrue(report.getViolations().isEmpty(), report.getViolations().toString());
  }

  private Report analyze(String declaration) throws IOException {
    var header =
        """
        package com.ai.label.infra.probe;
        """;
    return TypedSourceRuleFixture.analyze(this.directory, header + declaration);
  }
}
