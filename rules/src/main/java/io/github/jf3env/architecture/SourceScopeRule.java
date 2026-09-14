package io.github.jf3env.architecture;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import net.sourceforge.pmd.lang.java.JavaLanguageModule;
import net.sourceforge.pmd.lang.java.ast.ASTClassType;
import net.sourceforge.pmd.lang.java.ast.ASTCompilationUnit;
import net.sourceforge.pmd.lang.java.rule.AbstractJavaRule;

/** Source selection must never silently filter out an unexpected ownership root. */
final class SourceScopeRule extends AbstractJavaRule {
  private final Pattern packages;
  private final List<Path> roots;
  private final SourceRequest request;
  private final AtomicInteger visited;

  SourceScopeRule(SourceRequest request) {
    this(request, new AtomicInteger());
  }

  private SourceScopeRule(SourceRequest request, AtomicInteger visited) {
    this.request = request;
    this.visited = visited;
    packages =
        Pattern.compile(
            Pattern.quote(request.basePackage())
                + "\\.(domain|persistence|infra)\\.[^.]+(?:\\.[^.]+)*");
    roots = request.sourceRoots();
    setLanguage(JavaLanguageModule.getInstance());
    setName("SOURCE_INVENTORY");
    setMessage("SOURCE_INVENTORY: {0}");
  }

  @Override
  public Object visit(ASTCompilationUnit unit, Object data) {
    visited.incrementAndGet();
    var name = unit.getPackageName();
    var file =
        Path.of(unit.getTextDocument().getFileId().getAbsolutePath()).toAbsolutePath().normalize();
    if (!packages.matcher(name).matches()) {
      asCtx(data).addViolation(unit, "unexpected ownership package " + name);
    } else if (roots.stream()
        .noneMatch(root -> root.resolve(name.replace('.', '/')).equals(file.getParent()))) {
      asCtx(data).addViolation(unit, "package does not match source path " + file);
    }
    if (unit.getTypeDeclarations().isEmpty()
        && !file.getFileName().toString().equals("package-info.java")) {
      asCtx(data).addViolation(unit, "no type declarations in " + file);
    }
    for (var type : unit.descendants(ASTClassType.class).crossFindBoundaries()) {
      var symbol = type.getTypeMirror().getSymbol();
      if (symbol == null || symbol.isUnresolved()) {
        asCtx(data).addViolation(type, "unresolved type " + type.getSimpleName());
      }
    }
    return data;
  }

  int visited() {
    return visited.get();
  }

  @Override
  public SourceScopeRule deepCopy() {
    return new SourceScopeRule(request, visited);
  }
}
