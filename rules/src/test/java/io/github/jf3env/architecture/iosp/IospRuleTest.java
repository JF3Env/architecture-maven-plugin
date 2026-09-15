package io.github.jf3env.architecture.iosp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.sourceforge.pmd.reporting.Report;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IospRuleTest {
  private static final String PREFIX =
      """
      package com.ai.label.probe.domain;
      import java.util.List;
      import java.util.UUID;
      interface Repository {
        int load();
        List<Integer> values();
        void save(int value);
        void result(Result value);
      }
      final class Data {
        private int value;
        int plain() { return value; }
        int getComputed() { return value * 2; }
        int getOverloaded(int scale) { return value * scale; }
      }
      final class Result {
        private final int value;
        Result(int value) { this.value = value; }
        int plain() { return value; }
        static final class ResultBuilder {
          static ResultBuilder builder() { return new ResultBuilder(); }
          private int value;
          ResultBuilder value(int value) { this.value = value; return this; }
          Result build() { return new Result(value); }
        }
      }
      final class ResultFactory {
        static Result create(int value) { return new Result(value); }
      }
      class ExampleService {
        Repository repo;
        int calculate(int value) { return value * 2; }
        Result createResult(int value) { return ResultFactory.create(value); }
      """;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "int run() { var value = repo.load(); return calculate(value); }",
        "void run() { repo.save(calculate(repo.load())); }",
        "int primitive(int value) { return Math.abs(value) * 2; }",
        "static class ProjectionFactory {"
            + " Result create(Data data) { return new Result(data.plain()); } }",
        "Result factory(int value) { return Result.ResultBuilder.builder().value(value).build(); }",
        "static class ManualFactory { Result create(int value) {"
            + " return new Result.ResultBuilder().value(value).build(); } }",
        "List<Integer> run() { return repo.values().stream().map(this::calculate).toList(); }",
        "void run() { for (var value : repo.values()) { repo.save(calculate(value)); } }",
        "void run() { try { repo.save(repo.load()); }"
            + " catch (IllegalArgumentException error) { throw error; } }",
        "int run() { int value; value = repo.load(); return calculate(value); }",
        "void run(List<Result> findings, int value) { findings.add(createResult(value)); }",
        "static class AliasFactory { Result create(int value) {"
            + " var product = new Result(value); return product; } }",
        "int run(boolean ready) { return ready ? repo.load() : calculate(0); }",
        "int run(boolean ready) { if (ready) return repo.load(); return calculate(0); }",
        "void run(String selected, String actual)"
            + " { if (selected.equals(actual)) repo.save(repo.load()); }",
        "private class Helper { final Repository collaborator;"
            + " Helper(Repository collaborator) { this.collaborator = collaborator; } }",
        "java.util.function.IntSupplier action = () -> calculate(repo.load());",
        "int primitive(int[] samples) { var buffer = new java.util.ArrayList<Integer>();"
            + " for (int value : samples) buffer.add(value * 2); return buffer.size(); }"
      })
  void acceptsSegregatedCoordinationAndOperations(String member) {
    var report = this.analyze(member);
    assertTrue(report.getViolations().isEmpty(), report.getViolations().toString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "int run() { return repo.load() * 2; }",
        "void run() { repo.save(repo.load() + 1); }",
        "Result run() { return new Result(repo.load()); }",
        "void run() { repo.result(Result.ResultBuilder.builder().value(repo.load()).build()); }",
        "Result run() { int value = repo.load();"
            + " return Result.ResultBuilder.builder().value(value).build(); }",
        "String run() { repo.load(); return UUID.randomUUID().toString(); }",
        "List<Integer> run() { return repo.values().stream().map(value -> value * 2).toList(); }",
        "List<Result> run() { return repo.values().stream().map(Result::new).toList(); }",
        "Result factory(Data data) { return new Result(data.getComputed()); }",
        "Result factory(Data data) { return new Result(data.getOverloaded(2)); }",
        "int state; void run() { state = repo.load(); }",
        "int run() { var value = repo.load(); return value > 0 ? value : 0; }",
        "int run() { var value = repo.load(); value++; return value; }",
        "int run(int[] values) { return values[repo.load()]; }",
        "Object run() { return new int[repo.load()]; }",
        "int run() { return (int) ((long) repo.load()); }",
        "boolean run(Object value) { repo.load(); return value instanceof String; }",
        "private int hidden(int value) { return calculate(value) + 1; }",
        "private class Helper { int hidden(int value) { return calculate(value) + 1; } }",
        "java.util.function.IntSupplier action = () -> repo.load() + 1;"
      })
  void rejectsMixedBodiesIncludingArgumentsCallbacksAndPrivateHelpers(String member) {
    var report = this.analyze(member);
    assertTrue(
        report.getViolations().stream()
            .anyMatch(violation -> violation.getDescription().contains("IOSP_MIXED")),
        report.getViolations().toString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "void run(List<Result> findings, int value) { findings.add(new Result(value)); }",
        "int run() { Result value = new Result(1); return value.value; }",
        "Result run(int value) { return new Result(value * 2); }",
        "List<Result> run() { return List.of(new Result(1), new Result(2)); }",
        "Object run() { return new Object[] { new Result(1), new Result(2) }; }",
        "void run(Runnable work) { new Thread(work).start(); }",
        "int run(int n) { return Result.ResultBuilder.builder().value(n).build().plain(); }",
        "boolean run(int n) { return Result.ResultBuilder.builder().value(n).build().equals(n); }",
        "List<Result> run(List<Integer> values) { return values.stream()"
            + ".map(x -> { return new Result(x); }).toList(); }",
        "List<Result> run(List<Integer> values) { return values.stream()"
            + ".map(x -> { var r = new Result(x); return r; }).toList(); }",
        "List<Result> run(List<Integer> values) { return values.stream()"
            + ".map(x -> { return Result.ResultBuilder.builder().value(x).build(); }).toList(); }",
        "List<Result> run(List<Integer> values)"
            + " { return values.stream().map(Result::new).toList(); }",
        "List<Result> run(List<Integer> values)"
            + " { return values.stream().map(x -> new Result(x)).toList(); }",
        "Result run() { new Result(1); return new Result(2); }",
        "private class Helper { Helper() { repo.load(); } }",
        "private class Helper { int value = repo.load(); }",
        "private class Helper { { repo.load(); } }",
        "private class Helper { Helper() { int value = repo.load() + 1; } }",
        "private class Helper { int value; Helper(int source) { value = source * 2; } }",
        "int value = repo.load() + 1;",
        "int run() { class Local { Local() { repo.load(); } } return repo.load(); }",
        "Runnable run() { return new Runnable() { { repo.load(); } public void run() {} }; }"
      })
  void rejectsConstructionConsumptionCompositionAndInitializationEvasions(String member) {
    var report = this.analyze(member);
    assertFalse(report.getViolations().isEmpty(), member);
  }

  @Test
  void factoryExtractionImprovesSegregationAndInliningCannotEvadeIt() {
    var inline = this.analyze("void run(List<Result> findings) { findings.add(new Result(1)); }");
    var extracted =
        this.analyze("void run(List<Result> findings) { findings.add(createResult(1)); }");
    assertTrue(
        inline.getViolations().stream()
            .anyMatch(v -> v.getDescription().contains("IOSP_CONSTRUCTION_USE")));
    assertTrue(extracted.getViolations().isEmpty(), extracted.getViolations().toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"WorkerBuilder", "Composer", "Worker"})
  void businessCallsAreNotConstructionBecauseOfTheOwnerName(String name) {
    var report =
        this.analyze(
            "final class "
                + name
                + " { int load() { return repo.load(); } Result build() { return new Result(1); } } int run("
                + name
                + " worker) { return worker.load() + 1; }");
    assertTrue(
        report.getViolations().stream().anyMatch(v -> v.getDescription().contains("IOSP_MIXED")),
        report.getViolations().toString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "int run() { int value = repo.load(); if (value > 0) return value; return 0; }",
        "int run() { int value = repo.load(); return value > 0 ? value : 0; }"
      })
  void imperativeAndExpressionConditionsHaveTheSamePolicy(String member) {
    var report = this.analyze(member);
    assertTrue(
        report.getViolations().stream().anyMatch(v -> v.getDescription().contains("IOSP_MIXED")),
        report.getViolations().toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"add", "send"})
  void applicationCollectionOverloadsAreDelegationsRegardlessOfTheirName(String name) {
    var report =
        this.analyze(
            "static final class Bag extends java.util.ArrayList<Integer> {"
                + " void "
                + name
                + "(Repository repo) { repo.save(1); } }"
                + " int run(Bag bag, Repository repo, int n) { bag."
                + name
                + "(repo); return n + 1; }");
    assertTrue(
        report.getViolations().stream().anyMatch(v -> v.getDescription().contains("IOSP_MIXED")),
        report.getViolations().toString());
  }

  @Test
  void suppressionCannotTurnAnIospViolationIntoAPass() {
    var report =
        this.analyze(
            """
        @SuppressWarnings("PMD.IospMixedAbstraction")
        int run() { return repo.load() + 1; } // NOPMD
        """);
    assertEquals(1, report.getViolations().size());
    assertTrue(report.getSuppressedViolations().isEmpty());
  }

  @Test
  void commentsAndLiteralTextDoNotBecomeOperations() {
    var report =
        this.analyze(
            """
        String description() { return "new Result(repo.load() + 1)"; }
        int run() { /* value++ and new Result() are just prose */ return repo.load(); }
        """);
    assertTrue(report.getViolations().isEmpty(), report.getViolations().toString());
  }

  @Test
  void unresolvedCallsFailClosedInsteadOfReceivingAccessorExemptions() {
    var report = this.analyze("int run() { return missing.getValue() + repo.load(); }");
    assertTrue(
        report.getViolations().stream()
            .anyMatch(violation -> violation.getDescription().contains("IOSP_UNRESOLVED")));
  }

  @Test
  void inheritedServiceMethodsStayInScopeWithoutAServiceSuffix() {
    var report =
        IospAnalysis.analyzeSource(
            PREFIX
                + "}"
                + """
        class Disguised extends ExampleService {
          int hidden() { return repo.load() + 1; }
        }
        """,
            this.getClass().getClassLoader(),
            "com.ai.label");
    assertTrue(report.getProcessingErrors().isEmpty(), report.getProcessingErrors().toString());
    assertTrue(
        report.getViolations().stream()
            .anyMatch(violation -> violation.getDescription().contains("Disguised#hidden")));
  }

  @Test
  void diagnosticsIdentifyBothWitnessesAndTheirSourceLines() {
    var report = this.analyze("int run() { return repo.load() + 1; }");
    var description = report.getViolations().getFirst().getDescription();
    assertTrue(description.contains("Repository#load"), description);
    assertTrue(description.contains("operator +"), description);
    assertTrue(description.contains("line "), description);
    assertFalse(description.contains("null"), description);
  }

  private Report analyze(String member) {
    var report =
        IospAnalysis.analyzeSource(
            PREFIX + member + "}", this.getClass().getClassLoader(), "com.ai.label");
    assertTrue(report.getProcessingErrors().isEmpty(), report.getProcessingErrors().toString());
    assertTrue(
        report.getConfigurationErrors().isEmpty(), report.getConfigurationErrors().toString());
    return report;
  }
}
