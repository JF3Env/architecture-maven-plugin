package io.github.jf3env.architecture.source.placement;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.sourceforge.pmd.lang.java.JavaLanguageModule;
import net.sourceforge.pmd.lang.java.ast.ASTClassType;
import net.sourceforge.pmd.lang.java.ast.ASTCompilationUnit;
import net.sourceforge.pmd.lang.java.ast.ASTImplementsList;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.rule.AbstractJavaRule;
import net.sourceforge.pmd.lang.rule.RulePriority;

/**
 * Source-level collector of declaration facts for the advisory type-placement detector.
 *
 * <p>It runs on production sources alone: no compiled classes and no type resolution, so it can run
 * even while the build is red. For every compilation unit it records the declared package — which
 * is what proves a folder exists — and one fact per <em>top-level</em> type declaration. Nested
 * types are never recorded: a nested type has no folder of its own, so it can never be out of
 * place. A {@code package-info.java} declares no type and therefore contributes its package and
 * nothing else.
 *
 * <p>Besides the name, each fact carries the simple names listed in the declaration's {@code
 * implements} clause. Without type resolution that clause is the only evidence a source file offers
 * about the contract a type fulfils, and the analyzer needs it to tell a role name apart from a
 * framework provider that happens to share the suffix.
 *
 * <p>The rule never reports a violation of its own. It is a fact collector whose state is read
 * after the analysis, exactly like the source scope rule; the findings it feeds are advisories and
 * never fail a build.
 */
public final class PlacementRule extends AbstractJavaRule {

  /** The identity published by the goal that executes this collector. */
  public static final String NAME = "TypePlacementFactsCollector";

  /**
   * One top-level type declaration. {@code implementedNames} holds the simple name of every type in
   * the {@code implements} clause, with type arguments and any package qualifier dropped, so {@code
   * implements jakarta.ws.rs.ext.ExceptionMapper<FooException>} contributes {@code
   * ExceptionMapper}. It is empty for a declaration with no {@code implements} clause.
   */
  public record TypeFact(
      String file, int line, String packageName, String typeName, List<String> implementedNames) {

    public TypeFact {
      implementedNames = List.copyOf(implementedNames);
    }
  }

  private final Shared shared;

  public PlacementRule() {
    this(new Shared());
  }

  private PlacementRule(Shared shared) {
    this.shared = shared;
    setName(NAME);
    setLanguage(JavaLanguageModule.getInstance());
    setMessage("{0}");
    setPriority(RulePriority.LOW);
  }

  @Override
  public PlacementRule deepCopy() {
    return new PlacementRule(this.shared);
  }

  public List<TypeFact> types() {
    return this.shared.types;
  }

  /**
   * Every package observed in the analyzed sources. A role folder counts as existing only when a
   * compilation unit declares that package, which is the evidence the analyzer calibrates on.
   */
  public Set<String> packages() {
    return this.shared.packages;
  }

  public int checked() {
    return this.shared.checked.get();
  }

  private static final class Shared {
    private final List<TypeFact> types = new ArrayList<>();
    private final Set<String> packages = new LinkedHashSet<>();
    private final AtomicInteger checked = new AtomicInteger();
  }

  @Override
  public Object visit(ASTCompilationUnit unit, Object ctx) {
    this.shared.checked.incrementAndGet();
    var packageName = unit.getPackageName();
    if (!packageName.isEmpty()) {
      this.shared.packages.add(packageName);
      var file = unit.getReportLocation().getFileId().getAbsolutePath();
      for (var declaration : unit.getTypeDeclarations()) {
        if (declaration.isTopLevel()) {
          this.shared.types.add(
              new TypeFact(
                  file,
                  declaration.getBeginLine(),
                  packageName,
                  simpleNameOf(declaration),
                  implementedNamesOf(declaration)));
        }
      }
    }
    return super.visit(unit, ctx);
  }

  private static String simpleNameOf(ASTTypeDeclaration declaration) {
    var name = declaration.getSimpleName();
    return name == null ? "<unknown>" : name;
  }

  /**
   * The simple names of the declaration's own {@code implements} clause. Only the clause that
   * belongs to this declaration is read — {@code children} rather than {@code descendants} — so a
   * nested type's clause never leaks into its enclosing type's fact.
   */
  private static List<String> implementedNamesOf(ASTTypeDeclaration declaration) {
    var names = new ArrayList<String>();
    for (var list : declaration.children(ASTImplementsList.class)) {
      for (var type : list.children(ASTClassType.class)) {
        var name = type.getSimpleName();
        if (name != null && !name.isEmpty()) {
          names.add(name);
        }
      }
    }
    return names;
  }
}
