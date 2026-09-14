package io.github.jf3env.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.sourceforge.pmd.lang.rule.RuleSet;
import org.junit.jupiter.api.Test;

class SourceRuleCatalogTest {
  @Test
  void missingAndDuplicateRequiredIdentitiesFail() {
    var catalog = new SourceRuleCatalog("com.acme");
    assertThrows(IllegalStateException.class, () -> catalog.identities(List.of()));
    var sets = catalog.load();
    assertTrue(
        assertThrows(
                IllegalStateException.class, () -> catalog.identities(List.of(sets.getFirst())))
            .getMessage()
            .contains("Missing required source rules"));
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> catalog.identities(List.of(sets.getFirst(), sets.getFirst())))
            .getMessage()
            .contains("Duplicate source rule"));
  }

  @Test
  void additionalDeclarationsAreIncludedWithoutChangingACount() {
    var catalog = new SourceRuleCatalog("com.acme");
    var sets = new ArrayList<>(catalog.load());
    var extra = sets.getFirst().getRules().iterator().next().deepCopy();
    extra.setName("AdditionalContract");
    sets.add(RuleSet.forSingleRule(extra));
    var names = catalog.identities(sets);
    assertTrue(names.contains("AdditionalContract"));
    assertEquals(catalog.identities(catalog.load()).size() + 1, names.size());
  }
}
