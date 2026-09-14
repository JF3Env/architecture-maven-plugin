package io.github.jf3env.architecture.bytecode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

class TransactionOwnershipTest {
  private static final ArchRule TRANSACTIONS =
      new BytecodeRuleCatalog(TestPolicies.reference())
          .rules()
          .get("TRANSACTION_ANNOTATIONS_BELONG_TO_PERSISTENCE");

  @Test
  void rejectsTransactionsOutsidePersistence() {
    var classes = new ClassFileImporter().importClasses(Invalid.class);
    assertTrue(TRANSACTIONS.evaluate(classes).hasViolation());
  }

  @Test
  void acceptsCodeWithoutDeclarativeTransactions() {
    var classes = new ClassFileImporter().importClasses(Valid.class);
    assertFalse(TRANSACTIONS.evaluate(classes).hasViolation());
  }

  static class Invalid {
    @Transactional
    void run() {}
  }

  static class Valid {
    void run() {}
  }
}
