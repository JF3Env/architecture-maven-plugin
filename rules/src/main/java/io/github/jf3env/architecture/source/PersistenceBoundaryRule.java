package io.github.jf3env.architecture.source;

import java.util.HashSet;
import java.util.Set;
import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.java.JavaLanguageModule;
import net.sourceforge.pmd.lang.java.ast.ASTArgumentList;
import net.sourceforge.pmd.lang.java.ast.ASTAssignableExpr.ASTNamedReferenceExpr;
import net.sourceforge.pmd.lang.java.ast.ASTAssignmentExpression;
import net.sourceforge.pmd.lang.java.ast.ASTCastExpression;
import net.sourceforge.pmd.lang.java.ast.ASTCompilationUnit;
import net.sourceforge.pmd.lang.java.ast.ASTExpression;
import net.sourceforge.pmd.lang.java.ast.ASTExpressionStatement;
import net.sourceforge.pmd.lang.java.ast.ASTLambdaExpression;
import net.sourceforge.pmd.lang.java.ast.ASTMethodCall;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTMethodReference;
import net.sourceforge.pmd.lang.java.ast.ASTResource;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTVariableDeclarator;
import net.sourceforge.pmd.lang.java.ast.ASTVariableId;
import net.sourceforge.pmd.lang.java.rule.AbstractJavaRule;
import net.sourceforge.pmd.lang.java.symbols.JVariableSymbol;
import net.sourceforge.pmd.lang.java.types.JTypeMirror;
import net.sourceforge.pmd.lang.java.types.TypeTestUtil;

/**
 * EntityManager capabilities may only be used inside the consumer's translating transaction
 * boundary.
 *
 * <p>The boundary is the fully-qualified name of the one class whose {@code execute} method wraps
 * all JPA/EntityManager work; it is supplied by the consumer, not derived, because it names a
 * single application-wide contract rather than a per-domain authority.
 */
public final class PersistenceBoundaryRule extends AbstractJavaRule {
  private final String boundary;

  public PersistenceBoundaryRule(String boundary) {
    if (boundary == null || boundary.isBlank()) {
      throw new IllegalArgumentException(
          "A persistence transaction boundary class name is required");
    }
    this.boundary = boundary;
    setName("PersistenceBoundary");
    setLanguage(JavaLanguageModule.getInstance());
    setMessage(
        "Use EntityManager only inside the configured transaction boundary's execute method");
  }

  @Override
  public Object visit(ASTCompilationUnit unit, Object context) {
    var expressions =
        unit.descendants().crossFindBoundaries().filterIs(ASTExpression.class).toList();
    var variables = unit.descendants().crossFindBoundaries().filterIs(ASTVariableId.class).toList();
    Set<ASTExpression> capabilities = new HashSet<>();
    Set<JVariableSymbol> aliases = new HashSet<>();
    // Monotone, local provenance: casts and erased aliases cannot launder a capability.
    boolean changed;
    do {
      changed = false;
      for (var expression : expressions) {
        if (isCapability(expression, capabilities, aliases)) {
          changed |= capabilities.add(expression);
        }
        if (expression instanceof ASTAssignmentExpression assignment
            && capabilities.contains(assignment.getRightOperand())
            && assignment.getLeftOperand() instanceof ASTNamedReferenceExpr reference) {
          changed |= aliases.add(reference.getReferencedSym());
        }
      }
      for (var variable : variables) {
        if (capabilities.contains(variable.getInitializer())) {
          changed |= aliases.add(variable.getSymbol());
        }
      }
    } while (changed);
    for (var capability : capabilities) {
      var boundary = immediateWork(capability);
      if (boundary == null || !safeUse(capability, boundary)) {
        asCtx(context)
            .addViolationNoSuppress(
                capability,
                capability.getAstInfo(),
                "{0}",
                "PERSISTENCE_BOUNDARY: unguarded JPA access or escaping capability;"
                    + " materialize inside the configured transaction boundary's"
                    + " execute method");
      }
    }
    return super.visit(unit, context);
  }

  private static boolean isCapability(
      ASTExpression expression, Set<ASTExpression> capabilities, Set<JVariableSymbol> aliases) {
    if (jpa(expression)) {
      return true;
    }
    if (expression instanceof ASTMethodReference reference) {
      if (reference.getOverloadSelectionInfo().isFailed()) {
        throw new IllegalStateException(
            "Cannot resolve persistence capability method reference at "
                + reference.getReportLocation());
      }
      return jpa(reference.getReferencedMethod().getReturnType());
    }
    if (expression instanceof ASTNamedReferenceExpr reference) {
      return reference.getReferencedSym() != null && aliases.contains(reference.getReferencedSym());
    }
    if (expression instanceof ASTCastExpression cast) {
      return capabilities.contains(cast.getOperand());
    }
    if (expression instanceof ASTAssignmentExpression assignment) {
      return capabilities.contains(assignment.getRightOperand());
    }
    if (expression instanceof ASTMethodCall call && capabilities.contains(call.getQualifier())) {
      return Set.of("unwrap", "getDelegate", "getResultStream", "iterator", "spliterator")
              .contains(call.getMethodName())
          || stream(call);
    }
    return false;
  }

  private static boolean jpa(ASTExpression expression) {
    return jpa(expression.getTypeMirror());
  }

  private static boolean jpa(JTypeMirror type) {
    return TypeTestUtil.isA("jakarta.persistence.EntityManager", type)
        || TypeTestUtil.isA("jakarta.persistence.Query", type);
  }

  private static boolean stream(ASTExpression expression) {
    return TypeTestUtil.isA("java.util.stream.BaseStream", expression.getTypeMirror());
  }

  private ASTLambdaExpression immediateWork(Node node) {
    for (var ancestor : node.ancestors()) {
      if (ancestor instanceof ASTMethodDeclaration || ancestor instanceof ASTTypeDeclaration) {
        return null;
      }
      if (ancestor instanceof ASTLambdaExpression lambda) {
        if (lambda.getParent() instanceof ASTArgumentList arguments
            && arguments.getNumChildren() == 1
            && arguments.getParent() instanceof ASTMethodCall call
            && call.getMethodName().equals("execute")
            && !call.getOverloadSelectionInfo().isFailed()
            && call.getMethodType()
                .getSymbol()
                .getEnclosingClass()
                .getBinaryName()
                .equals(this.boundary)) {
          return lambda;
        }
        return null;
      }
    }
    return null;
  }

  private boolean safeUse(ASTExpression capability, ASTLambdaExpression boundary) {
    var parent = capability.getParent();
    if (parent instanceof ASTMethodCall operation && operation.getQualifier() == capability) {
      return !operation.getOverloadSelectionInfo().isFailed()
          && (jpa(capability) || stream(capability) && resourceOwned(capability));
    }
    if (parent instanceof ASTCastExpression) {
      return true; // The cast itself is checked with the same provenance.
    }
    if (parent instanceof ASTVariableDeclarator variable) {
      return localTo(variable.getVarId(), boundary)
          && (!stream(capability) || variable.getVarId().isResourceDeclaration());
    }
    if (parent instanceof ASTAssignmentExpression assignment
        && !assignment.isCompound()
        && assignment.getLeftOperand() instanceof ASTNamedReferenceExpr reference
        && reference.getReferencedSym() != null) {
      return localTo(reference.getReferencedSym().tryGetNode(), boundary) && !stream(capability);
    }
    return parent instanceof ASTExpressionStatement
        && (capability instanceof ASTMethodCall || capability instanceof ASTAssignmentExpression)
        && !stream(capability);
  }

  private boolean localTo(ASTVariableId variable, ASTLambdaExpression boundary) {
    return variable != null
        && (variable.isLocalVariable() || variable.isResourceDeclaration())
        && immediateWork(variable) == boundary;
  }

  private static boolean resourceOwned(ASTExpression expression) {
    if (expression.ancestors(ASTResource.class).nonEmpty()) {
      return true;
    }
    if (expression instanceof ASTNamedReferenceExpr reference
        && reference.getReferencedSym() != null) {
      var declaration = reference.getReferencedSym().tryGetNode();
      return declaration != null && declaration.isResourceDeclaration();
    }
    return expression instanceof ASTMethodCall call
        && call.getQualifier() != null
        && resourceOwned(call.getQualifier());
  }

  @Override
  public Object visit(ASTMethodCall call, Object context) {
    var owner = call.ancestors(ASTTypeDeclaration.class).first();
    if (call.getQualifier() != null
        && TypeTestUtil.isA(
            "io.quarkus.narayana.jta.QuarkusTransaction", call.getQualifier().getTypeMirror())
        && (owner == null || !owner.getBinaryName().equals(this.boundary))) {
      asCtx(context)
          .addViolationNoSuppress(
              call, call.getAstInfo(), "{0}", "PERSISTENCE_BOUNDARY: transaction bypass");
    }
    return super.visit(call, context);
  }

  @Override
  public PersistenceBoundaryRule deepCopy() {
    return new PersistenceBoundaryRule(this.boundary);
  }
}
