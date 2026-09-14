package io.github.jf3env.architecture.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.rule.RuleSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PersistenceBoundaryRuleTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "var query = transactions.execute(() -> entities.createQuery(\"select w from W w\"));"
            + " return query.getResultList();",
        "return transactions.execute(() -> entities.createQuery(\"select w from W w\", Object.class));",
        "return transactions.execute(() -> entities.createStoredProcedureQuery(\"procedure\"));",
        "return transactions.execute(() -> { escaped = entities.createQuery(\"select w from W w\"); return 1; });",
        "return transactions.execute(() -> { var q = entities.createQuery(\"select w from W w\");"
            + " Object alias = q; return alias; });",
        "return transactions.execute(() -> { Object alias; alias = entities.createQuery(\"select w from W w\");"
            + " return alias; });",
        "return transactions.execute(() -> List.of(entities.createQuery(\"select w from W w\")));",
        "return transactions.execute(() -> (Object) entities.createQuery(\"select w from W w\"));",
        "return manager().find(Object.class, 1);",
        "return provider.get().find(Object.class, 1);",
        "return transactions.execute(() -> (Supplier<EntityManager>) provider::get);",
        "return transactions.execute(() -> (Supplier<EntityManager>) this::manager);",
        "return transactions.execute(() -> entities.createQuery(\"select w from W w\").getResultStream());",
        "return transactions.execute(() -> entities.createQuery(\"select w from W w\").getResultStream().toList());",
        "return transactions.execute(() -> { var q = entities.createQuery(\"select w from W w\");"
            + " return (Supplier<?>) q::getResultList; });",
        "return transactions.execute(() -> { var q = entities.createQuery(\"select w from W w\");"
            + " return (Supplier<?>) () -> q.getResultList(); });",
        "return transactions.execute(() -> { var q = entities.createQuery(\"select w from W w\");"
            + " return consume(q); });",
        "return transactions.execute(() -> entities.unwrap(Object.class));",
        "return transactions.execute(() -> entities.getDelegate());",
        "return transactions.execute(() -> { Object delegate = entities.getDelegate(); return delegate; });",
        "return transactions.execute(() -> { try (var s = entities.createQuery(\"select w from W w\")"
            + ".getResultStream()) { return s.iterator(); } });",
        "return transactions.execute(() -> { try (var s = entities.createQuery(\"select w from W w\")"
            + ".getResultStream()) { return (Supplier<?>) s::toList; } });",
        "return other.execute(() -> manager().find(Object.class, 1));",
        "return transactions.execute(() -> new Supplier<Object>() {"
            + " public Object get() { return manager().find(Object.class, 1); } });"
      })
  void rejectsDerivedCapabilitiesAndUnprovenExecution(String body) throws IOException {
    var report = this.analyze(body);
    assertTrue(!report.getViolations().isEmpty(), "missing PERSISTENCE_BOUNDARY for: " + body);
    assertTrue(
        report.getViolations().stream()
            .allMatch(violation -> violation.getDescription().contains("PERSISTENCE_BOUNDARY")),
        report.getViolations().toString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "return transactions.execute(() -> manager().find(Object.class, 1));",
        "return transactions.execute(() -> provider.get().merge(\"materialized\"));",
        "return transactions.execute(() -> { var q = entities.createQuery(\"select w from W w\", Object.class);"
            + " q.setMaxResults(1); var alias = q; return alias.getResultList(); });",
        "return transactions.execute(() -> { try (var s = entities.createQuery(\"select w from W w\", Object.class)"
            + ".getResultStream()) { return s.filter(value -> value != null).toList(); } });",
        "var data = transactions.execute(() -> entities.find(Object.class, 1)); return consume(data);",
        "return List.of(1, 2).stream().toList();"
      })
  void acceptsImmediateOperationsAndMaterializedResults(String body) throws IOException {
    var report = this.analyze(body);
    assertTrue(report.getViolations().isEmpty(), report.getViolations().toString());
  }

  private net.sourceforge.pmd.reporting.Report analyze(String body) throws IOException {
    return TypedSourceRuleFixture.analyze(
        this.directory,
        """
        package com.ai.label.persistence.workspace;
        import jakarta.persistence.EntityManager;
        import java.util.List;
        import java.util.function.Supplier;
        class WorkspaceTransactions { <T> T execute(Supplier<T> task) { return task.get(); } }
        class Other { <T> T execute(Supplier<T> task) { return task.get(); } }
        class Fixture {
          EntityManager entities;
          WorkspaceTransactions transactions;
          Supplier<EntityManager> provider;
          Other other;
          Object escaped;
          EntityManager manager() { return null; }
          Object consume(Object value) { return value; }
          Object run() { %s }
        }
        """
            .formatted(body),
        new PersistenceBoundaryRule(TypedSourceRuleFixture.BOUNDARY));
  }

  @Test
  void rejectsDirectAccessAliasesAndConstructorReferences() {
    for (var body :
        new String[] {
          "return entities.find(Object.class, 1);",
          "return this.entities.find(Object.class, 1);",
          "return entities;",
          "java.util.function.Supplier<Boolean> open = entities::isOpen; return open.get();"
        }) {
      assertEquals(1, violations(body));
    }
  }

  @Test
  void acceptsAccessInsideTheTranslatingBoundary() {
    assertEquals(
        0, violations("return transactions.execute(() -> entities.find(Object.class, 1));"));
  }

  @Test
  void rejectsUnrelatedLambdasAndLookalikeMethods() {
    assertEquals(1, violations("return other.execute(() -> entities.find(Object.class, 1));"));
  }

  @Test
  void rejectsEscapingManagerAndDeferredWork() {
    assertEquals(1, violations("return transactions.execute(() -> entities);"));
    assertEquals(
        1, violations("return transactions.execute(() -> (Supplier<?>) () -> entities.isOpen());"));
  }

  @Test
  void transactionApiCannotBeUsedToBypassTranslation() {
    assertEquals(
        1,
        violations(
            "return io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> 1);"));
  }

  private static int violations(String body) {
    try (var analysis = PmdAnalysis.create(TypedSourceRuleFixture.configuration())) {
      analysis.addRuleSet(
          RuleSet.forSingleRule(new PersistenceBoundaryRule(TypedSourceRuleFixture.BOUNDARY)));
      analysis
          .files()
          .addSourceFile(
              FileId.fromPathLikeString("Fixture.java"),
              """
          package com.ai.label.persistence.workspace;
          import jakarta.persistence.EntityManager;
          import java.util.function.Supplier;
          class WorkspaceTransactions { Object execute(Supplier<?> task) { return task.get(); } }
          class Other { Object execute(Supplier<?> task) { return task.get(); } }
          class Fixture {
            EntityManager entities;
            WorkspaceTransactions transactions;
            Other other;
            Object run() { %s }
          }
          """
                  .formatted(body));
      var report = analysis.performAnalysisAndCollectReport();
      assertTrue(report.getProcessingErrors().isEmpty(), report.getProcessingErrors().toString());
      return report.getViolations().size();
    }
  }
}
