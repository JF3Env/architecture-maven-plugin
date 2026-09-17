package io.github.jf3env.architecture.source.passthrough;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.java.JavaLanguageModule;
import net.sourceforge.pmd.lang.java.ast.ASTArrayAllocation;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorCall;
import net.sourceforge.pmd.lang.java.ast.ASTExpressionStatement;
import net.sourceforge.pmd.lang.java.ast.ASTMethodCall;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTReturnStatement;
import net.sourceforge.pmd.lang.java.ast.ASTThisExpression;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTVariableAccess;
import net.sourceforge.pmd.lang.java.rule.AbstractJavaRule;
import net.sourceforge.pmd.lang.rule.RulePriority;

/**
 * Source-level collector of forwarding facts for the pass-through smell detector.
 *
 * <p>It runs on production sources alone: no compiled classes and no type resolution, so it can run
 * even while the build is red. It records, for every executable method, whether its body is a
 * single delegating call to another method in the same class (a "pure forwarder") and the name of
 * that target, plus every in-class call site. The analyzer turns those facts into S1/S3 findings.
 * Classification is deliberately conservative: a method is a pure forwarder only when its body is
 * exactly one return/expression that is a same-class call and allocates nothing.
 *
 * <p>The rule never reports a violation of its own. It is a fact collector whose state is read
 * after the analysis, exactly like the source scope rule; the findings it feeds are advisories and
 * never fail a build.
 */
public final class PassthroughRule extends AbstractJavaRule {

  /** The identity published by the goal that executes this collector. */
  public static final String NAME = "PassthroughFactsCollector";

  public record MethodFact(
      String file,
      int line,
      String className,
      String methodName,
      int arity,
      boolean isPrivate,
      boolean isOverride,
      boolean isProducer,
      boolean pureForward,
      boolean forwardInClass,
      String forwardTarget,
      boolean paramOnlyProjected,
      String wrapTarget) {

    public String id() {
      return this.methodName + "/" + this.arity;
    }
  }

  public record CallSite(
      String file, int line, String enclosingMethod, String callee, boolean inClass) {}

  private final Shared shared;

  public PassthroughRule() {
    this(new Shared());
  }

  private PassthroughRule(Shared shared) {
    this.shared = shared;
    setName(NAME);
    setLanguage(JavaLanguageModule.getInstance());
    setMessage("{0}");
    setPriority(RulePriority.LOW);
  }

  @Override
  public PassthroughRule deepCopy() {
    return new PassthroughRule(this.shared);
  }

  public List<MethodFact> methods() {
    return this.shared.methods;
  }

  public List<CallSite> calls() {
    return this.shared.calls;
  }

  public int checked() {
    return this.shared.checked.get();
  }

  private static final class Shared {
    private final List<MethodFact> methods = new ArrayList<>();
    private final List<CallSite> calls = new ArrayList<>();
    private final AtomicInteger checked = new AtomicInteger();
  }

  @Override
  public Object visit(ASTMethodDeclaration method, Object ctx) {
    if (method.getBody() != null) {
      this.shared.checked.incrementAndGet();
      var forward = forwardTarget(method);
      var wrap = wrapTarget(method);
      this.shared.methods.add(
          new MethodFact(
              fileOf(method),
              method.getBeginLine(),
              classNameOf(method),
              method.getName(),
              arityOf(method),
              isPrivate(method),
              method.isAnnotationPresent("Override"),
              method.isAnnotationPresent("jakarta.enterprise.inject.Produces"),
              forward != null,
              forward != null && forward.inClass(),
              forward == null ? null : forward.target(),
              paramOnlyProjected(method),
              wrap == null ? null : wrap.target()));
    }
    return super.visit(method, ctx);
  }

  @Override
  public Object visit(ASTMethodCall call, Object ctx) {
    var owner = call.ancestors(ASTTypeDeclaration.class).first();
    if (owner != null) {
      var qualifier = call.getQualifier();
      var inClass = qualifier == null || qualifier instanceof ASTThisExpression;
      var encMethod = call.ancestors(ASTMethodDeclaration.class).first();
      this.shared.calls.add(
          new CallSite(
              fileOf(call),
              call.getBeginLine(),
              encMethod == null ? "<field>" : encMethod.getName() + "/" + arityOf(encMethod),
              call.getMethodName() + "/" + argCount(call),
              inClass));
    }
    return super.visit(call, ctx);
  }

  private record Forward(String target, boolean inClass) {}

  /**
   * A pure forwarder is a body of exactly one statement that is a return (or expression) whose
   * expression is a call to a method in the same class, allocating nothing. The call target's name
   * is returned for chain analysis.
   */
  private static Forward forwardTarget(ASTMethodDeclaration method) {
    var body = method.getBody();
    if (body.getNumChildren() != 1) {
      return null;
    }
    var statement = body.getChild(0);
    ASTMethodCall call = null;
    if (statement instanceof ASTReturnStatement ret && ret.getExpr() instanceof ASTMethodCall mc) {
      call = mc;
    } else if (statement instanceof ASTExpressionStatement es
        && es.getExpr() instanceof ASTMethodCall mc) {
      call = mc;
    }
    if (call == null || hasConstruction(call)) {
      return null;
    }
    var qualifier = call.getQualifier();
    var inClass = qualifier == null || qualifier instanceof ASTThisExpression;
    return new Forward(call.getMethodName() + "/" + argCount(call), inClass);
  }

  /**
   * A wrap method is a single-return body whose call takes exactly one argument that is itself a
   * construction ({@code new V(...)}): the method only packages its inputs into a value object to
   * hand to another method.
   */
  private static Forward wrapTarget(ASTMethodDeclaration method) {
    var body = method.getBody();
    if (body.getNumChildren() != 1
        || !(body.getChild(0) instanceof ASTReturnStatement ret)
        || !(ret.getExpr() instanceof ASTMethodCall call)) {
      return null;
    }
    var args = call.getArguments();
    if (args == null
        || args.getNumChildren() != 1
        || !(args.getChild(0) instanceof ASTConstructorCall)) {
      return null;
    }
    var qualifier = call.getQualifier();
    var inClass = qualifier == null || qualifier instanceof ASTThisExpression;
    if (!inClass) {
      return null;
    }
    return new Forward(call.getMethodName() + "/" + argCount(call), true);
  }

  /**
   * A param-only projection has exactly one parameter and every reference to it in the body reads
   * the parameter through one of its accessors ({@code p.getX()}); the parameter is never passed
   * whole, computed over, or mutated.
   */
  private static boolean paramOnlyProjected(ASTMethodDeclaration method) {
    if (arityOf(method) != 1) {
      return false;
    }
    String param;
    try {
      var params = method.getSymbol().getFormalParameters();
      if (params.size() != 1) {
        return false;
      }
      param = params.get(0).getSimpleName();
    } catch (RuntimeException e) {
      return false;
    }
    var used = new int[1];
    return allUsesAreAccessorQualifiers(method.getBody(), param, used) && used[0] > 0;
  }

  private static boolean allUsesAreAccessorQualifiers(Node node, String param, int[] used) {
    for (var index = 0; index < node.getNumChildren(); index++) {
      var child = node.getChild(index);
      if (child instanceof ASTVariableAccess name && name.getName().equals(param)) {
        used[0]++;
        if (!(child.getParent() instanceof ASTMethodCall call && call.getQualifier() == child)) {
          return false;
        }
      }
      if (!allUsesAreAccessorQualifiers(child, param, used)) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasConstruction(Node node) {
    if (node instanceof ASTConstructorCall || node instanceof ASTArrayAllocation) {
      return true;
    }
    for (var index = 0; index < node.getNumChildren(); index++) {
      if (hasConstruction(node.getChild(index))) {
        return true;
      }
    }
    return false;
  }

  /**
   * The per-file grouping key the analyzer indexes by. The absolute path is used instead of the
   * file identifier's {@code toString()} so an advisory reads exactly like this library's violation
   * diagnostics; both facts and call sites derive it the same way, so grouping is unaffected.
   */
  private static String fileOf(Node node) {
    return node.getReportLocation().getFileId().getAbsolutePath();
  }

  private static String classNameOf(ASTMethodDeclaration method) {
    var owner = method.ancestors(ASTTypeDeclaration.class).first();
    return owner == null ? "<unknown>" : owner.getSimpleName();
  }

  private static boolean isPrivate(ASTMethodDeclaration method) {
    try {
      return (method.getSymbol().getModifiers() & Modifier.PRIVATE) != 0;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static int arityOf(ASTMethodDeclaration method) {
    try {
      return method.getSymbol().getArity();
    } catch (RuntimeException e) {
      return 0;
    }
  }

  private static int argCount(ASTMethodCall call) {
    var args = call.getArguments();
    return args == null ? 0 : args.getNumChildren();
  }
}
