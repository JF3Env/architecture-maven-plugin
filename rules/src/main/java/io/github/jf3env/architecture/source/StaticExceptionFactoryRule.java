package io.github.jf3env.architecture.source;

import io.github.jf3env.architecture.iosp.VerifiedExceptionFactories;
import net.sourceforge.pmd.lang.java.JavaLanguageModule;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.rule.AbstractJavaRule;

/**
 * The only handwritten production static methods are direct self-factories on Exception subtypes.
 */
public final class StaticExceptionFactoryRule extends AbstractJavaRule {
  public StaticExceptionFactoryRule() {
    setName("StaticExceptionFactory");
    setLanguage(JavaLanguageModule.getInstance());
    setMessage("Static methods are restricted to direct exception self-factories");
  }

  @Override
  public Object visit(ASTMethodDeclaration method, Object context) {
    if (method.isStatic() && !VerifiedExceptionFactories.isFactory(method)) {
      asCtx(context)
          .addViolationNoSuppress(
              method,
              method.getAstInfo(),
              "{0}",
              "STATIC_EXCEPTION_FACTORY: "
                  + method.getName()
                  + " must only return a new instance of its own Exception subtype,"
                  + " forwarding parameters or literals without extra logic");
    }
    return super.visit(method, context);
  }
}
