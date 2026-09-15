package io.github.jf3env.architecture.source;

import java.lang.reflect.Modifier;
import java.util.Set;
import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.java.JavaLanguageModule;
import net.sourceforge.pmd.lang.java.ast.ASTAssignableExpr.ASTNamedReferenceExpr;
import net.sourceforge.pmd.lang.java.ast.ASTAssignmentExpression;
import net.sourceforge.pmd.lang.java.ast.ASTBlock;
import net.sourceforge.pmd.lang.java.ast.ASTClassDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTExpression;
import net.sourceforge.pmd.lang.java.ast.ASTExpressionStatement;
import net.sourceforge.pmd.lang.java.ast.ASTFieldAccess;
import net.sourceforge.pmd.lang.java.ast.ASTFieldDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTIfStatement;
import net.sourceforge.pmd.lang.java.ast.ASTInfixExpression;
import net.sourceforge.pmd.lang.java.ast.ASTLiteral;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTThisExpression;
import net.sourceforge.pmd.lang.java.ast.ASTThrowStatement;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTUnaryExpression;
import net.sourceforge.pmd.lang.java.ast.ASTVariableAccess;
import net.sourceforge.pmd.lang.java.ast.JModifier;
import net.sourceforge.pmd.lang.java.rule.AbstractJavaRule;
import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.symbols.JFieldSymbol;
import net.sourceforge.pmd.lang.java.symbols.JVariableSymbol;

/**
 * Mutable aggregate state has one guarded assignment boundary.
 *
 * <p>Aggregate roots are the classes annotated with the consumer's marker annotation; the package
 * they live in no longer identifies them, because context-first domain packages are named after the
 * ubiquitous language rather than after technical roles.
 */
public final class AggregateMutationRule extends AbstractJavaRule {
  private final String aggregateRootAnnotation;

  public AggregateMutationRule(String aggregateRootAnnotation) {
    if (aggregateRootAnnotation == null || aggregateRootAnnotation.isBlank()) {
      throw new IllegalArgumentException("An aggregate root marker annotation is required");
    }
    this.aggregateRootAnnotation = aggregateRootAnnotation;
    setName("AggregateInvariantSetter");
    setLanguage(JavaLanguageModule.getInstance());
    setMessage("Mutable aggregate state must be guarded by its private setter");
  }

  private boolean aggregate(ASTTypeDeclaration type) {
    return type.isAnnotationPresent(this.aggregateRootAnnotation);
  }

  private boolean aggregate(JClassSymbol type) {
    return type != null
        && type.getDeclaredAnnotations().stream()
            .anyMatch(annotation -> annotation.isOfType(this.aggregateRootAnnotation));
  }

  @Override
  public Object visit(ASTClassDeclaration type, Object context) {
    if (aggregate(type)
        && type.isAnyAnnotationPresent(Set.of("lombok.Setter", "lombok.Data"))
        && type.getDeclarations(ASTFieldDeclaration.class)
            .any(field -> !field.hasModifiers(JModifier.FINAL) && !field.isStatic())) {
      this.reject(type, context);
    }
    return super.visit(type, context);
  }

  @Override
  public Object visit(ASTFieldDeclaration field, Object context) {
    var owner = field.ancestors(ASTTypeDeclaration.class).first();
    if (owner != null
        && aggregate(owner)
        && !field.hasModifiers(JModifier.FINAL)
        && !field.isStatic()) {
      if (field.isAnnotationPresent("lombok.Setter")) {
        this.reject(field, context);
      }
      field
          .getVarIds()
          .filter(variable -> variable.getInitializer() != null)
          .forEach(variable -> reject(variable, context));
    }
    return super.visit(field, context);
  }

  @Override
  public Object visit(ASTAssignmentExpression assignment, Object context) {
    this.checkWrite(assignment.getLeftOperand(), assignment, !assignment.isCompound(), context);
    return super.visit(assignment, context);
  }

  @Override
  public Object visit(ASTUnaryExpression expression, Object context) {
    if (!expression.getOperator().isPure()) {
      this.checkWrite(expression.getOperand(), expression, false, context);
    }
    return super.visit(expression, context);
  }

  private void checkWrite(Node target, Node write, boolean plainAssignment, Object context) {
    if (!(target instanceof ASTNamedReferenceExpr reference)
        || !(reference.getReferencedSym() instanceof JFieldSymbol field)
        || field.isFinal()
        || Modifier.isStatic(field.getModifiers())
        || !aggregate(field.getEnclosingClass())) {
      return;
    }
    var method = write.ancestors(ASTMethodDeclaration.class).first();
    var expected =
        "set"
            + Character.toUpperCase(field.getSimpleName().charAt(0))
            + field.getSimpleName().substring(1);
    if (!plainAssignment
        || method == null
        || !method.hasModifiers(JModifier.PRIVATE)
        || method.isStatic()
        || !method.getName().equals(expected)
        || method.getArity() != 1
        || !field.getEnclosingClass().equals(method.getSymbol().getEnclosingClass())
        || !guardedParameterWrite(method, target, write)) {
      this.reject(write, context);
    }
  }

  private static boolean guardedParameterWrite(
      ASTMethodDeclaration method, Node target, Node write) {
    var parameter = method.getFormalParameters().get(0).getVarId();
    if (!(write instanceof ASTAssignmentExpression assignment)
        || !(assignment.getRightOperand() instanceof ASTVariableAccess value)
        || !parameter.getSymbol().equals(value.getReferencedSym())
        || !assignment.getLeftOperand().getTypeMirror().equals(parameter.getTypeMirror())
        || !currentInstance(target)) {
      return false;
    }
    var body = method.getBody();
    if (body == null || body.getNumChildren() < 2) {
      return false;
    }
    var last = body.getChild(body.getNumChildren() - 1);
    if (!(last instanceof ASTExpressionStatement) || last.getChild(0) != write) {
      return false;
    }
    // Only immediate rejecting guards followed by one direct write are proved here.
    // No alternate paths, deferred code or intervening effects are accepted by offset alone.
    for (var index = 0; index < body.getNumChildren() - 1; index++) {
      if (!(body.getChild(index) instanceof ASTIfStatement guard)
          || !rejectingGuard(guard, parameter.getSymbol())) {
        return false;
      }
    }
    return true;
  }

  private static boolean currentInstance(Node target) {
    return target instanceof ASTVariableAccess
        || target instanceof ASTFieldAccess access
            && access.getQualifier() instanceof ASTThisExpression self
            && self.getQualifier() == null;
  }

  private static boolean rejectingGuard(ASTIfStatement guard, JVariableSymbol parameter) {
    return !guard.hasElse()
        && pureCondition(guard.getCondition(), parameter)
        && guard
            .getCondition()
            .descendantsOrSelf()
            .filterIs(ASTVariableAccess.class)
            .any(variable -> parameter.equals(variable.getReferencedSym()))
        && (guard.getThenBranch() instanceof ASTThrowStatement
            || guard.getThenBranch() instanceof ASTBlock block
                && block.getNumChildren() == 1
                && block.getChild(0) instanceof ASTThrowStatement);
  }

  private static boolean pureCondition(ASTExpression condition, JVariableSymbol parameter) {
    return condition
        .descendantsOrSelf()
        .filterIs(ASTExpression.class)
        .all(
            expression ->
                expression instanceof ASTLiteral
                    || expression instanceof ASTInfixExpression
                    || expression instanceof ASTUnaryExpression unary
                        && unary.getOperator().isPure()
                    || expression instanceof ASTVariableAccess variable
                        && parameter.equals(variable.getReferencedSym()));
  }

  private void reject(Node node, Object context) {
    var message =
        "AGGREGATE_INVARIANT_SETTER: use immediate rejecting guards and assign the unchanged parameter"
            + " directly to this field in its private setter; no generated setters or bypass writes";
    asCtx(context).addViolationNoSuppress(node, node.getAstInfo(), "{0}", message);
  }

  @Override
  public AggregateMutationRule deepCopy() {
    return new AggregateMutationRule(this.aggregateRootAnnotation);
  }
}
