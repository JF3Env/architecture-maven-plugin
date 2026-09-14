package io.github.jf3env.architecture.iosp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class ExceptionFactoryIospTest {
  @Test
  void staticExceptionCreationDoesNotMixWithValidationOrDelegation() {
    this.assertClean(
        """
        package com.ai.label.domain.probe;
        class MissingException extends RuntimeException {
          private MissingException(String message) { super(message); }
          static MissingException of(String message) { return new MissingException(message); }
        }
        class Consumer {
          int validate(int value) {
            if (value < 0) throw MissingException.of("negative");
            return value * 2;
          }
          String read(java.util.function.Supplier<java.util.Optional<String>> source) {
            return source.get().orElseThrow(() -> MissingException.of("missing"));
          }
          java.util.function.Function<String, MissingException> factory() { return MissingException::of; }
        }
        """);
  }

  @Test
  void compiledExceptionFactoriesAreAlsoPlumbing() {
    this.assertClean(
        """
        package com.ai.label.domain.probe;
        import io.github.jf3env.architecture.iosp.ExceptionFactoryIospTest.CompiledMissing;
        class Consumer {
          int validate(java.util.UUID id, int value) {
            if (value < 0) throw CompiledMissing.forWorkspace(id);
            return value * 2;
          }
        }
        """);
  }

  @Test
  void compiledOverloadsWithExtraLogicAreNotExempt() {
    var report =
        IospAnalysis.analyzeSource(
            """
        package com.ai.label.domain.probe;
        import io.github.jf3env.architecture.iosp.ExceptionFactoryIospTest.CompiledProblem;
        class Consumer {
          int validate(int value) {
            if (value < 0) throw CompiledProblem.of("negative");
            return value * 2;
          }
        }
        """,
            this.getClass().getClassLoader(),
            "com.ai.label");
    assertTrue(report.getProcessingErrors().isEmpty(), report.getProcessingErrors().toString());
    assertTrue(
        report.getViolations().stream()
            .anyMatch(
                violation ->
                    violation.getDescription().contains("IOSP_MIXED")
                        && violation.getDescription().contains("Consumer#validate")));
  }

  public static class CompiledMissing extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private CompiledMissing(java.util.UUID id) {
      super("missing " + id);
    }

    public static CompiledMissing forWorkspace(java.util.UUID id) {
      return new CompiledMissing(id);
    }
  }

  public static class CompiledProblem extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public static CompiledProblem of() {
      return new CompiledProblem();
    }

    public static CompiledProblem of(String message) {
      System.out.println(message);
      return new CompiledProblem();
    }
  }

  @Test
  void otherStaticExceptionMethodsRemainDelegation() {
    var report =
        IospAnalysis.analyzeSource(
            """
        package com.ai.label.domain.probe;
        class Problem extends RuntimeException {
          static Problem of(String message) { System.out.println(message); return new Problem(); }
        }
        class Consumer {
          int validate(int value) {
            if (value < 0) throw Problem.of("negative");
            return value * 2;
          }
        }
        """,
            this.getClass().getClassLoader(),
            "com.ai.label");
    assertTrue(report.getProcessingErrors().isEmpty(), report.getProcessingErrors().toString());
    assertTrue(
        report.getViolations().stream()
            .anyMatch(
                violation ->
                    violation.getDescription().contains("IOSP_MIXED")
                        && violation.getDescription().contains("Consumer#validate")));
  }

  private void assertClean(String source) {
    var report =
        IospAnalysis.analyzeSource(source, this.getClass().getClassLoader(), "com.ai.label");
    assertTrue(report.getProcessingErrors().isEmpty(), report.getProcessingErrors().toString());
    assertTrue(report.getViolations().isEmpty(), report.getViolations().toString());
  }
}
