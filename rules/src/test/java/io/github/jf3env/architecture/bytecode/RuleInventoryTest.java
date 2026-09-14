package io.github.jf3env.architecture.bytecode;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuleInventoryTest {
  @Test
  void additionalRulesAreIncludedWithoutChangingARequiredCount() {
    var inventory = new RuleInventory();
    inventory.add("BASE", classes().should().bePublic());
    inventory.add("ADDITIONAL", classes().should().beTopLevelClasses());
    var rules = inventory.require(Set.of("BASE"));
    assertEquals(List.of("ADDITIONAL", "BASE"), List.copyOf(rules.keySet()));
    assertThrows(UnsupportedOperationException.class, rules::clear);
  }

  @Test
  void removingAContractCannotBeHiddenByAddingAnotherRule() {
    var inventory = new RuleInventory();
    inventory.add("BASE", classes().should().bePublic());
    inventory.add("ADDITIONAL", classes().should().beTopLevelClasses());
    var failure =
        assertThrows(
            IllegalStateException.class, () -> inventory.require(Set.of("BASE", "MISSING")));
    assertEquals("Missing required architecture rules: [MISSING]", failure.getMessage());
  }

  @Test
  void emptyNullDuplicateAndInvalidDeclarationsFailClosed() {
    var inventory = new RuleInventory();
    assertThrows(IllegalStateException.class, () -> inventory.require(Set.of()));
    assertEquals(
        "Null architecture rule: NULL",
        assertThrows(IllegalArgumentException.class, () -> inventory.add("NULL", null))
            .getMessage());
    assertThrows(
        IllegalArgumentException.class, () -> inventory.add("", classes().should().bePublic()));
    assertThrows(
        IllegalArgumentException.class, () -> inventory.add(null, classes().should().bePublic()));
    inventory.add("BASE", classes().should().bePublic());
    assertEquals(
        "Duplicate architecture rule: BASE",
        assertThrows(
                IllegalArgumentException.class,
                () -> inventory.add("BASE", classes().should().bePrivate()))
            .getMessage());
  }

  @Test
  void allRequiredBackendContractsArePresent() {
    var rules = new BytecodeRuleCatalog(TestPolicies.reference(), List.of()).rules();
    assertTrue(rules.keySet().containsAll(new BytecodeContracts().requiredRules()));
    assertTrue(!new BytecodeContracts().requiredRules().isEmpty());
  }

  @Test
  void hostConfigurationCannotMakeAnEmptyRuleSelectionPass() {
    var inventory = new RuleInventory();
    inventory.add("BASE", classes().that().haveSimpleName("AbsentType").should().bePublic());
    var rule = inventory.require(Set.of("BASE")).get("BASE");
    var imported = new ClassFileImporter().importClasses(Object.class);
    ArchConfiguration.withThreadLocalScope(
        configuration -> {
          configuration.setProperty("archRule.failOnEmptyShould", "false");
          assertThrows(AssertionError.class, () -> rule.evaluate(imported));
        });
  }
}
