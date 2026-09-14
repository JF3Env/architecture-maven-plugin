package io.github.jf3env.architecture;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.sourceforge.pmd.lang.rule.Rule;
import net.sourceforge.pmd.lang.rule.RuleSet;
import net.sourceforge.pmd.lang.rule.RuleSetLoader;
import net.sourceforge.pmd.properties.PropertyDescriptor;

final class SourceRuleCatalog {
  private static final Set<String> REQUIRED =
      Set.of(
          "NoStaticMethods",
          "RequireTypeImports",
          "AvoidOptionalGet",
          "DomainMethodsMustNotReturnNull");
  private final String basePackage;

  SourceRuleCatalog(String basePackage) {
    this.basePackage = basePackage;
  }

  List<RuleSet> load() {
    var loader = new RuleSetLoader().loadResourcesWith(SourceRuleCatalog.class.getClassLoader());
    var sets = new ArrayList<RuleSet>();
    for (var name : List.of("architecture.xml", "domain-null-contracts.xml")) {
      var ruleset = loader.loadFromResource("io/github/jf3env/architecture/" + name);
      for (var rule : ruleset.getRules()) {
        var property = rule.getPropertyDescriptor("domainPackage");
        if (property != null) {
          configure(rule, property, basePackage + ".domain.");
        }
      }
      sets.add(ruleset);
    }
    identities(sets);
    return List.copyOf(sets);
  }

  List<String> identities(List<RuleSet> sets) {
    var names = new TreeSet<String>();
    for (var set : sets) {
      if (set.size() == 0) {
        throw new IllegalStateException("Empty source rule set: " + set.getName());
      }
      for (var rule : set.getRules()) {
        if (!names.add(rule.getName())) {
          throw new IllegalStateException("Duplicate source rule: " + rule.getName());
        }
      }
    }
    var missing = new TreeSet<>(REQUIRED);
    missing.removeAll(names);
    if (!missing.isEmpty()) {
      throw new IllegalStateException("Missing required source rules: " + missing);
    }
    return List.copyOf(names);
  }

  private <T> void configure(Rule rule, PropertyDescriptor<T> property, String value) {
    rule.setProperty(property, property.serializer().fromString(value));
  }
}
