package io.github.jf3env.architecture.source;

import io.github.jf3env.architecture.iosp.VerifiedExceptionFactories;
import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.java.JavaLanguageModule;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorCall;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTMethodReference;
import net.sourceforge.pmd.lang.java.ast.ASTThrowStatement;
import net.sourceforge.pmd.lang.java.rule.AbstractJavaRule;
import net.sourceforge.pmd.lang.java.types.JClassType;
import net.sourceforge.pmd.lang.java.types.TypeTestUtil;

/**
 * Production failures belong to custom exception self-factories, including replacements for library
 * exceptions.
 */
public final class ExceptionConstructionRule extends AbstractJavaRule {
  private final String basePackagePrefix;

  public ExceptionConstructionRule(String basePackage) {
    if (basePackage == null || basePackage.isBlank()) {
      throw new IllegalArgumentException("A consumer basePackage is required");
    }
    this.basePackagePrefix = basePackage + ".";
    setName("ExceptionSelfFactory");
    setLanguage(JavaLanguageModule.getInstance());
    setMessage("Use a custom exception through its verified static self-factory");
  }

  @Override
  public Object visit(ASTConstructorCall creation, Object context) {
    var method = creation.ancestors(ASTMethodDeclaration.class).first();
    if (TypeTestUtil.isA(Throwable.class, creation.getTypeMirror())
        && (method == null || !VerifiedExceptionFactories.isFactory(method))) {
      this.reject(creation, context);
    }
    return super.visit(creation, context);
  }

  @Override
  public Object visit(ASTMethodReference reference, Object context) {
    if (reference.isConstructorReference()
        && TypeTestUtil.isA(Throwable.class, reference.getLhs().getTypeMirror())) {
      this.reject(reference, context);
    }
    return super.visit(reference, context);
  }

  @Override
  public Object visit(ASTThrowStatement statement, Object context) {
    if (!(statement.getExpr().getTypeMirror() instanceof JClassType type)
        || !type.getSymbol().getPackageName().startsWith(this.basePackagePrefix)) {
      this.reject(statement, context);
    }
    return super.visit(statement, context);
  }

  private void reject(Node node, Object context) {
    var message =
        "EXCEPTION_SELF_FACTORY: use a custom exception's verified static self-factory,"
            + " not a generic exception or an external constructor call/reference";
    asCtx(context).addViolationNoSuppress(node, node.getAstInfo(), "{0}", message);
  }

  @Override
  public ExceptionConstructionRule deepCopy() {
    return new ExceptionConstructionRule(
        this.basePackagePrefix.substring(0, this.basePackagePrefix.length() - 1));
  }
}
