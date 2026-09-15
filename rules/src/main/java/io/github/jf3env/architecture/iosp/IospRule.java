package io.github.jf3env.architecture.iosp;

import io.github.jf3env.architecture.ContextShape;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.java.JavaLanguageModule;
import net.sourceforge.pmd.lang.java.ast.ASTArgumentList;
import net.sourceforge.pmd.lang.java.ast.ASTArrayAccess;
import net.sourceforge.pmd.lang.java.ast.ASTArrayAllocation;
import net.sourceforge.pmd.lang.java.ast.ASTAssignmentExpression;
import net.sourceforge.pmd.lang.java.ast.ASTCastExpression;
import net.sourceforge.pmd.lang.java.ast.ASTCompactConstructorDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorCall;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTEnumConstant;
import net.sourceforge.pmd.lang.java.ast.ASTExplicitConstructorInvocation;
import net.sourceforge.pmd.lang.java.ast.ASTFieldAccess;
import net.sourceforge.pmd.lang.java.ast.ASTFieldDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTInfixExpression;
import net.sourceforge.pmd.lang.java.ast.ASTInitializer;
import net.sourceforge.pmd.lang.java.ast.ASTLambdaExpression;
import net.sourceforge.pmd.lang.java.ast.ASTMethodCall;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTMethodReference;
import net.sourceforge.pmd.lang.java.ast.ASTReturnStatement;
import net.sourceforge.pmd.lang.java.ast.ASTThisExpression;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTUnaryExpression;
import net.sourceforge.pmd.lang.java.ast.ASTVariableAccess;
import net.sourceforge.pmd.lang.java.ast.ASTVariableDeclarator;
import net.sourceforge.pmd.lang.java.ast.BinaryOp;
import net.sourceforge.pmd.lang.java.ast.InvocationNode;
import net.sourceforge.pmd.lang.java.ast.UnaryOp;
import net.sourceforge.pmd.lang.java.rule.AbstractJavaRule;
import net.sourceforge.pmd.lang.java.types.JClassType;
import net.sourceforge.pmd.lang.java.types.JMethodSig;
import net.sourceforge.pmd.lang.java.types.JTypeMirror;
import net.sourceforge.pmd.lang.java.types.OverloadSelectionResult;
import net.sourceforge.pmd.lang.java.types.TypeTestUtil;
import net.sourceforge.pmd.lang.rule.RulePriority;
import net.sourceforge.pmd.reporting.RuleContext;

/** A structural IOSP contract for every handwritten backend executable scope. */
public final class IospRule extends AbstractJavaRule {
  private static final Set<String> LOCAL_RESOURCES =
      Set.of(
          "java.util.ArrayList",
          "java.util.LinkedList",
          "java.util.HashMap",
          "java.util.LinkedHashMap",
          "java.util.HashSet",
          "java.util.LinkedHashSet",
          "java.util.TreeMap",
          "java.util.TreeSet",
          "java.util.EnumMap",
          "java.lang.StringBuilder",
          "java.lang.StringBuffer",
          "java.util.zip.CRC32");
  private final IospCallPolicy calls;
  private final CompiledInvocations invocations;
  private final ClassLoader loader;
  private final AtomicInteger checkedMethods;
  private final Set<String> applicationTypes;
  private final String basePackage;
  private final ContextShape shape;

  public IospRule(String basePackage) {
    this(IospRule.class.getClassLoader(), basePackage);
  }

  public IospRule(ClassLoader loader, String basePackage) {
    this(loader, basePackage, Set.of());
  }

  public IospRule(ClassLoader loader, String basePackage, Set<String> applicationTypes) {
    this(loader, basePackage, new AtomicInteger(), Set.copyOf(applicationTypes));
  }

  private IospRule(
      ClassLoader loader,
      String basePackage,
      AtomicInteger checkedMethods,
      Set<String> applicationTypes) {
    if (basePackage == null || basePackage.isBlank()) {
      throw new IllegalArgumentException("A consumer basePackage is required");
    }
    setName("IospMixedAbstraction");
    setLanguage(JavaLanguageModule.getInstance());
    setMessage("{0}");
    setPriority(RulePriority.HIGH);
    this.calls = new IospCallPolicy(loader);
    this.invocations = new CompiledInvocations(loader);
    this.loader = loader;
    this.basePackage = basePackage;
    this.shape = ContextShape.of(basePackage);
    this.checkedMethods = checkedMethods;
    this.applicationTypes = applicationTypes;
  }

  private Facts constructorFacts(Node constructor) {
    var facts = new Facts(true, this.inService(constructor));
    if (facts.strictInitialization) {
      facts.construct(constructor, "service constructor initialization");
    } else {
      // Own-state validation is not a factory call. Additional allocations still compose products.
      facts.products.add(constructor);
    }
    return facts;
  }

  private boolean inBackend(Node node) {
    var owner = node.ancestors(ASTTypeDeclaration.class).first();
    return owner != null
        && owner.getTypeMirror().getSymbol().getPackageName().startsWith(this.basePackage + ".");
  }

  private static boolean workingResource(Node node) {
    return node instanceof ASTArrayAllocation
        || node instanceof ASTConstructorCall call
            && call.getTypeMirror() instanceof JClassType type
            && LOCAL_RESOURCES.contains(type.getSymbol().getBinaryName());
  }

  private boolean inService(Node node) {
    for (var parent = node.getParent(); parent != null; parent = parent.getParent()) {
      if (parent instanceof ASTTypeDeclaration type && this.serviceType(type.getTypeMirror(), 0)) {
        return true;
      }
    }
    return false;
  }

  private boolean serviceType(JClassType type, int depth) {
    if (depth > 64) {
      throw new IllegalStateException("IOSP: invalid or excessively deep service inheritance");
    }
    var symbol = type.getSymbol();
    var packageName = symbol.getPackageName();
    var name = symbol.getSimpleName();
    if ((this.shape.isDomainPackage(packageName) || this.shape.isApplicationPackage(packageName))
        && (name.endsWith("Service") || name.endsWith("Handler"))) {
      return true;
    }
    var parent = type.getSuperClass();
    return parent != null && this.serviceType(parent, depth + 1);
  }

  private static boolean creatorName(String name) {
    return name.endsWith("Factory") || name.endsWith("Mapper") || name.endsWith("Builder");
  }

  private boolean domainProducer(ASTTypeDeclaration owner) {
    var packageName = owner.getTypeMirror().getSymbol().getPackageName();
    return this.shape.isWiringPackage(packageName)
        && owner.ancestors(ASTTypeDeclaration.class).isEmpty()
        && owner.getSimpleName().matches("[A-Z][A-Za-z0-9]*Producer")
        && (owner
                .descendants(ASTMethodDeclaration.class)
                .any(method -> method.isAnnotationPresent("jakarta.enterprise.inject.Produces"))
            || owner
                .descendants(ASTFieldDeclaration.class)
                .any(field -> field.isAnnotationPresent("jakarta.enterprise.inject.Produces")));
  }

  private Node wiredProduct(Node product) {
    var method = product.ancestors(ASTMethodDeclaration.class).first();
    var owner = product.ancestors(ASTTypeDeclaration.class).first();
    if (method == null
        || owner == null
        || !domainProducer(owner)
        || !method.isAnnotationPresent("jakarta.enterprise.inject.Produces")) {
      return product;
    }
    // Constructor arguments form one bean graph; all calls and computations are still checked by
    // scan.
    while (product.getParent() instanceof ASTArgumentList arguments
        && arguments.getParent() instanceof ASTConstructorCall construction) {
      product = construction;
    }
    return product;
  }

  private static void assignment(ASTAssignmentExpression expression, Facts facts) {
    if (expression.isCompound()) {
      facts.operation(expression, "compound assignment");
    } else if (facts.initialization
        && sameScope(expression, facts.scope)
        && mechanicalInitialization(expression)) {
      // Constructor parameter wiring is construction, not collaborator execution.
    } else if (!(expression.getLeftOperand() instanceof ASTVariableAccess variable)
        || variable.getSignature().getSymbol().isField()) {
      facts.operation(expression, "state write");
    }
  }

  private static boolean mechanicalInitialization(ASTAssignmentExpression expression) {
    var left = expression.getLeftOperand();
    return (left instanceof ASTVariableAccess variable
            && variable.getSignature().getSymbol().isField())
        || (left instanceof ASTFieldAccess field
            && field.getQualifier() instanceof ASTThisExpression);
  }

  private static boolean productType(JTypeMirror type) {
    return type instanceof JClassType clazz
        && !LOCAL_RESOURCES.contains(clazz.getSymbol().getBinaryName())
        && !TypeTestUtil.isA(Throwable.class, type);
  }

  private static boolean returnedProduct(Node node, Facts facts) {
    if (!sameScope(node, facts.scope)) {
      return false;
    }
    Node parent = node.getParent();
    if (node == facts.scope && node instanceof ASTMethodReference
        || parent == facts.scope && parent instanceof ASTLambdaExpression) {
      return true;
    }
    if (parent instanceof ASTReturnStatement) {
      return true;
    }
    if (parent instanceof ASTVariableDeclarator variable) {
      if (facts.initialization && variable.getVarId().isField()) {
        return true;
      }
      var uses = variable.getVarId().getLocalUsages();
      return !uses.isEmpty()
          && uses.stream()
              .allMatch(
                  use ->
                      use.getParent() instanceof ASTReturnStatement && sameScope(use, facts.scope));
    }
    return facts.initialization
        && parent instanceof ASTAssignmentExpression assignment
        && mechanicalInitialization(assignment);
  }

  private static boolean sameScope(Node node, Node scope) {
    for (Node parent = node; parent != null; parent = parent.getParent()) {
      if (parent == scope) {
        return true;
      }
      if (parent instanceof ASTLambdaExpression) {
        return false;
      }
    }
    return false;
  }

  private static void report(ASTMethodDeclaration method, Facts facts, RuleContext context) {
    var id = method.getSymbol().getEnclosingClass().getBinaryName() + "#" + method.getName();
    report(method, id, facts, context);
  }

  private static void report(Node method, String id, Facts facts, RuleContext context) {
    facts.forbiddenCreations.forEach(
        (allocation, target) -> {
          var detail =
              "instantiates type "
                  + target
                  + "; use an authorized creator; exceptions require a verified static self-factory";
          violation(allocation, id, context, "IOSP_CREATOR_LOCATION", detail);
        });
    if (facts.unresolved != null) {
      context.addViolationNoSuppress(
          method,
          method.getAstInfo(),
          "{0}",
          "IOSP_UNRESOLVED: "
              + id
              + " has an unproved invocation at line "
              + facts.unresolved.getBeginLine()
              + "; binding or construction dispatch needs complete current evidence");
    }
    if (facts.delegation != null && (facts.operation != null || facts.construction != null)) {
      var message =
          "IOSP_MIXED: "
              + id
              + " delegates via "
              + facts.delegation
              + " but implements "
              + (facts.operation != null ? facts.operation : facts.construction)
              + "; extract the implementation into a named operation/factory and keep this method as coordination";
      context.addViolationNoSuppress(method, method.getAstInfo(), "{0}", message);
    }
    if (facts.construction != null && facts.operation != null) {
      violation(
          method,
          id,
          context,
          "IOSP_MIXED",
          "constructs " + facts.construction + " but implements " + facts.operation);
    }
    if (facts.products.size() > 1) {
      violation(
          method,
          id,
          context,
          "IOSP_COMPOSED_CONSTRUCTION",
          "constructs multiple products; coordinate separate leaf factories");
    }
    if (facts.consumed != null) {
      violation(
          method,
          id,
          context,
          "IOSP_CONSTRUCTION_USE",
          "constructs and consumes a product at line "
              + facts.consumed.getBeginLine()
              + "; return the product from a leaf factory before using it");
    }
    if (facts.strictInitialization && facts.delegation != null) {
      violation(
          method,
          id,
          context,
          "IOSP_MIXED",
          "executes " + facts.delegation + " during construction/initialization");
    }
  }

  private static void violation(
      Node node, String id, RuleContext context, String code, String detail) {
    context.addViolationNoSuppress(node, node.getAstInfo(), "{0}", code + ": " + id + " " + detail);
  }

  @Override
  public IospRule deepCopy() {
    return new IospRule(this.loader, this.basePackage, this.checkedMethods, this.applicationTypes);
  }

  public int checkedMethods() {
    return this.checkedMethods.get();
  }

  @Override
  public Object visit(ASTMethodDeclaration method, Object context) {
    if (method.getBody() != null && inBackend(method)) {
      this.checkedMethods.incrementAndGet();
      var facts = new Facts();
      this.scan(method.getBody(), facts);
      report(method, facts, (RuleContext) context);
    }
    return super.visit(method, context);
  }

  @Override
  public Object visit(ASTConstructorDeclaration constructor, Object context) {
    if (inBackend(constructor)) {
      var facts = constructorFacts(constructor);
      this.scan(constructor.getBody(), facts);
      report(constructor, constructor.getName() + "#<init>", facts, (RuleContext) context);
      this.checkedMethods.incrementAndGet();
    }
    return super.visit(constructor, context);
  }

  @Override
  public Object visit(ASTCompactConstructorDeclaration constructor, Object context) {
    if (inBackend(constructor)) {
      var facts = constructorFacts(constructor);
      this.scan(constructor.getBody(), facts);
      report(
          constructor,
          constructor.getEnclosingType().getBinaryName() + "#<init>",
          facts,
          (RuleContext) context);
      this.checkedMethods.incrementAndGet();
    }
    return super.visit(constructor, context);
  }

  @Override
  public Object visit(ASTInitializer initializer, Object context) {
    if (inBackend(initializer)) {
      var facts = new Facts(true, inService(initializer));
      if (!initializer.isStatic()) {
        facts.products.add(initializer);
      }
      if (facts.strictInitialization) {
        facts.boundary(initializer, "service initializer");
      }
      this.scan(initializer.getBody(), facts);
      report(
          initializer,
          "initializer at line " + initializer.getBeginLine(),
          facts,
          (RuleContext) context);
      this.checkedMethods.incrementAndGet();
    }
    return super.visit(initializer, context);
  }

  @Override
  public Object visit(ASTFieldDeclaration field, Object context) {
    if (inBackend(field)) {
      for (var variable : field.children(ASTVariableDeclarator.class)) {
        if (variable.getInitializer() != null) {
          var initializer = variable.getInitializer();
          var callback =
              initializer instanceof ASTLambdaExpression
                  || initializer instanceof ASTMethodReference;
          var facts = new Facts(!callback, !callback && inService(field));
          if (!callback && !field.isStatic()) {
            facts.products.add(variable);
          }
          if (facts.strictInitialization && !workingResource(initializer)) {
            facts.boundary(variable, "service field initialization");
          }
          this.scan(initializer, facts);
          report(variable, "field " + variable.getName(), facts, (RuleContext) context);
          this.checkedMethods.incrementAndGet();
        }
      }
    }
    return super.visit(field, context);
  }

  @Override
  public Object visit(ASTEnumConstant constant, Object context) {
    if (inBackend(constant)) {
      var facts = new Facts();
      if (constant.getArguments() != null) {
        this.scan(constant.getArguments(), facts);
      }
      report(constant, "enum constant " + constant.getName(), facts, (RuleContext) context);
      this.checkedMethods.incrementAndGet();
    }
    return super.visit(constant, context);
  }

  private void scan(Node node, Facts facts) {
    if (facts.scope == null) {
      facts.scope = node;
    }
    // Definitions have their own executable scope. Lambdas are deliberately NOT skipped.
    if (node instanceof ASTTypeDeclaration
        || node instanceof ASTMethodDeclaration
        || node instanceof ASTConstructorDeclaration
        || node instanceof ASTCompactConstructorDeclaration
        || node instanceof ASTInitializer
        || node instanceof ASTFieldDeclaration) {
      return;
    }
    switch (node) {
      case ASTMethodCall call -> this.invocation(call.getOverloadSelectionInfo(), call, facts);
      case ASTMethodReference reference -> {
        if (reference.isConstructorReference()) {
          var resolution = reference.getOverloadSelectionInfo();
          if (!resolution.isFailed()) {
            this.creatorLocation(reference, resolution.getMethodType().getDeclaringType(), facts);
          }
          if (resolution.isFailed()) {
            facts.unresolved(reference);
          } else if (productType(resolution.getMethodType().getDeclaringType())) {
            facts.construct(reference, "constructor reference");
            if (!returnedProduct(reference, facts)) {
              facts.consumed(reference);
            }
          } else {
            facts.operation(reference, "constructor reference");
          }
        } else {
          this.invocation(reference.getOverloadSelectionInfo(), reference, facts);
        }
      }
      case ASTConstructorCall construction -> {
        var target =
            construction.isAnonymousClass()
                ? construction.getAnonymousClassDeclaration().getTypeMirror()
                : construction.getTypeMirror();
        this.creatorLocation(construction, target, facts);
        if (construction.getOverloadSelectionInfo().isFailed()
            && this.invocations.resolve(construction).isEmpty()) {
          facts.unresolved(construction);
        } else if (productType(target)) {
          Node product =
              this.calls.isBuilderAllocation(construction)
                  ? this.constructionChain(construction)
                  : construction;
          product = wiredProduct(product);
          facts.construct(product, "object construction");
          if (!returnedProduct(product, facts)) {
            facts.consumed(product);
          }
        } else {
          facts.operation(construction, "local resource or exception construction");
        }
      }
      case ASTExplicitConstructorInvocation invocation -> {
        // this()/super() are construction too; argument expressions remain in this scope.
        if (invocation.getOverloadSelectionInfo().isFailed()
            && this.invocations.resolve(invocation).isEmpty()) {
          facts.unresolved(invocation);
        }
      }
      case ASTArrayAllocation allocation -> facts.operation(allocation, "array allocation");
      case ASTArrayAccess access -> facts.operation(access, "indexed array access");
      case ASTInfixExpression expression -> {
        if (!Set.of(BinaryOp.EQ, BinaryOp.NE, BinaryOp.CONDITIONAL_AND, BinaryOp.CONDITIONAL_OR)
                .contains(expression.getOperator())
            && !expression.isCompileTimeConstant()) {
          facts.operation(expression, "operator " + expression.getOperator());
        }
      }
      case ASTCastExpression expression -> facts.operation(expression, "type conversion");
      case ASTUnaryExpression expression -> {
        if (expression.getOperator() != UnaryOp.NEGATION) {
          facts.operation(expression, "operator " + expression.getOperator());
        }
      }
      case ASTAssignmentExpression expression -> assignment(expression, facts);
      default -> {}
    }
    for (var index = 0; index < node.getNumChildren(); index++) {
      this.scan(node.getChild(index), facts);
    }
  }

  private void creatorLocation(
      Node allocation, net.sourceforge.pmd.lang.java.types.JTypeMirror target, Facts facts) {
    if (!(target instanceof JClassType type)
        || (!TypeTestUtil.isA(Throwable.class, type) && !this.applicationType(allocation, type))) {
      return;
    }
    var owner = allocation.ancestors(ASTTypeDeclaration.class).first();
    var method = allocation.ancestors(ASTMethodDeclaration.class).first();
    if (method != null
        && VerifiedExceptionFactories.isFactory(method)
        && owner != null
        && type.equals(owner.getTypeMirror())) {
      return;
    }
    if (TypeTestUtil.isA(Throwable.class, type)
        || owner == null
        || (!creatorName(owner.getSimpleName()) && !domainProducer(owner))) {
      facts.forbiddenCreations.put(allocation, type.getSymbol().getBinaryName());
    }
  }

  private boolean applicationType(Node allocation, JClassType type) {
    String name = type.getSymbol().getBinaryName();
    if (!this.applicationTypes.isEmpty()) {
      return this.applicationTypes.contains(name);
    }
    // Source-only fixtures have no repository inventory. Only declarations in that source count.
    return allocation
        .getAstInfo()
        .getRootNode()
        .descendants(ASTTypeDeclaration.class)
        .crossFindBoundaries()
        .any(declaration -> name.equals(declaration.getBinaryName()));
  }

  private Node constructionChain(Node start) {
    Node chain = start;
    while (chain.getParent() instanceof ASTMethodCall parent
        && parent.getQualifier() == chain
        && this.provenConstructionStep(parent)) {
      chain = parent;
    }
    return chain;
  }

  private boolean provenConstructionStep(ASTMethodCall call) {
    return this.binding(call.getOverloadSelectionInfo(), call)
        .map(signature -> calls.classify(signature, call) == IospCallPolicy.Kind.CONSTRUCTION)
        .orElse(false);
  }

  private void invocation(OverloadSelectionResult resolution, Node node, Facts facts) {
    var selected = this.binding(resolution, node);
    if (selected.isEmpty()) {
      facts.unresolved(node);
      return;
    }
    var method = selected.orElseThrow();
    var name = method.getSymbol().getEnclosingClass().getBinaryName() + "#" + method.getName();
    switch (this.calls.classify(method, node)) {
      case DELEGATION -> facts.delegation(node, name);
      case IMPLEMENTATION -> facts.operation(node, name);
      case UNPROVEN -> facts.unresolved(node);
      case CONSTRUCTION -> {
        Node chain = this.constructionChain(node);
        facts.construct(chain, "builder construction " + name);
        if (!returnedProduct(chain, facts)) {
          facts.consumed(chain);
        }
      }
      case PLUMBING -> {}
    }
  }

  private Optional<JMethodSig> binding(OverloadSelectionResult resolution, Node node) {
    if (!resolution.isFailed() && !resolution.getMethodType().getSymbol().isUnresolved()) {
      return Optional.of(resolution.getMethodType());
    }
    return node instanceof InvocationNode invocation
        ? this.invocations.resolve(invocation)
        : Optional.empty();
  }

  private static final class Facts {
    private final Set<Node> products = new LinkedHashSet<>();
    private final Map<Node, String> forbiddenCreations = new LinkedHashMap<>();
    private final boolean initialization;
    private final boolean strictInitialization;
    private String delegation;
    private String operation;
    private String construction;
    private Node consumed;
    private Node unresolved;
    private Node scope;

    Facts() {
      this(false, false);
    }

    Facts(boolean initialization, boolean strictInitialization) {
      this.initialization = initialization;
      this.strictInitialization = strictInitialization;
    }

    void construct(Node node, String detail) {
      this.products.add(node);
      this.boundary(node, detail);
    }

    void boundary(Node node, String detail) {
      if (this.construction == null) {
        this.construction = detail + " (line " + node.getBeginLine() + ")";
      }
    }

    void consumed(Node node) {
      if (this.consumed == null) {
        this.consumed = node;
      }
    }

    void delegation(Node node, String detail) {
      if (this.delegation == null) {
        this.delegation = detail + " (line " + node.getBeginLine() + ")";
      }
    }

    void operation(Node node, String detail) {
      if (this.operation == null) {
        this.operation = detail + " (line " + node.getBeginLine() + ")";
      }
    }

    void unresolved(Node node) {
      if (this.unresolved == null) {
        this.unresolved = node;
      }
    }
  }
}
