package io.github.jf3env.architecture.bytecode;

import com.tngtech.archunit.lang.ArchRule;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Required identities cannot be replaced, overwritten, or hidden by a logged count. */
final class RuleInventory {
  private final Map<String, ArchRule> rules = new TreeMap<>();

  RuleInventory() {}

  void add(String identity, ArchRule rule) {
    if (identity == null || identity.isBlank()) {
      throw new IllegalArgumentException("An architecture rule identity is required");
    }
    if (rule == null) {
      throw new IllegalArgumentException("Null architecture rule: " + identity);
    }
    if (rules.putIfAbsent(identity, rule.allowEmptyShould(false)) != null) {
      throw new IllegalArgumentException("Duplicate architecture rule: " + identity);
    }
  }

  Map<String, ArchRule> require(Set<String> required) {
    if (rules.isEmpty()) {
      throw new IllegalStateException("Empty architecture rule inventory");
    }
    var missing = new TreeSet<>(required);
    missing.removeAll(rules.keySet());
    if (!missing.isEmpty()) {
      throw new IllegalStateException("Missing required architecture rules: " + missing);
    }
    return Collections.unmodifiableMap(new TreeMap<>(rules));
  }
}
