package io.github.jf3env.architecture.iosp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.constant.ClassDesc;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.sourceforge.pmd.lang.java.ast.ASTAssignmentExpression;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorCall;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTExplicitConstructorInvocation;
import net.sourceforge.pmd.lang.java.ast.ASTExpression;
import net.sourceforge.pmd.lang.java.ast.ASTExpressionStatement;
import net.sourceforge.pmd.lang.java.ast.ASTFieldAccess;
import net.sourceforge.pmd.lang.java.ast.ASTFieldDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTInitializer;
import net.sourceforge.pmd.lang.java.ast.ASTMethodCall;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTReturnStatement;
import net.sourceforge.pmd.lang.java.ast.ASTStatement;
import net.sourceforge.pmd.lang.java.ast.ASTThisExpression;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTTypeExpression;
import net.sourceforge.pmd.lang.java.ast.ASTVariableAccess;
import net.sourceforge.pmd.lang.java.ast.InvocationNode;
import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.symbols.JConstructorSymbol;
import net.sourceforge.pmd.lang.java.symbols.JExecutableSymbol;
import net.sourceforge.pmd.lang.java.symbols.JFieldSymbol;
import net.sourceforge.pmd.lang.java.symbols.JMethodSymbol;
import net.sourceforge.pmd.lang.java.symbols.JTypeDeclSymbol;
import net.sourceforge.pmd.lang.java.symbols.JVariableSymbol;
import net.sourceforge.pmd.lang.java.types.JArrayType;
import net.sourceforge.pmd.lang.java.types.JClassType;
import net.sourceforge.pmd.lang.java.types.JMethodSig;
import net.sourceforge.pmd.lang.java.types.JPrimitiveType;
import net.sourceforge.pmd.lang.java.types.JTypeMirror;
import net.sourceforge.pmd.lang.java.types.TypeSystem;

/**
 * Name-independent proof of a small, structural construction protocol, not of a "builder" type.
 *
 * <p>A setter stores its sole parameter in an own instance field and returns this. A terminal
 * allocates one distinct product using only fields with proven setters. Only those linked methods,
 * and zero-argument static factories allocating that protocol, qualify. Other methods on the very
 * same class remain unproven. Names and annotations contribute no evidence.
 *
 * <p>Source declarations take precedence over resources. Classfiles are parsed and verified without
 * loading application classes. Constructors must directly extend Object and only store parameters,
 * in order, in distinct own fields. No calls other than Object's constructor, conversions,
 * branches, delegation, instance initializers, inherited storage or captured enclosing instances
 * are admitted. Thus cyclic calls are rejected without recursive interpretation. An allocation or
 * static factory also requires no executable class initialization: only Object as superclass, no
 * interfaces, constant-variable initializers and empty static blocks. Richer initialization is
 * deliberately unproven, even if it might be harmless.
 *
 * <p>Declaration evidence alone does not establish virtual dispatch. A call site must also pass
 * {@link #hasExactReceiver(ASTMethodCall)}. Ordinary non-final Lombok builders qualify on a fresh,
 * exactly typed allocation or proven factory followed exclusively by proven fluent setters.
 *
 * <p>Evidence is bounded and cached only within one request. Missing/unknown evidence returns
 * false; unreadable or corrupt resources fail visibly. This deliberately excludes richer builder
 * idioms (defaults, singular collections, validation, staged interfaces and toBuilder).
 */
final class VerifiedConstruction {
  private static final int MAX_MEMBERS = 256;
  private static final int MAX_INSTRUCTIONS = 256;
  private static final int MAX_CLASS_BYTES = 4 * 1024 * 1024;
  private static final int MAX_RECEIVER_STEPS = 32;
  private final ClassLoader loader;

  VerifiedConstruction(ClassLoader loader) {
    this.loader = loader;
  }

  private static boolean eligible(int modifiers) {
    return (modifiers & (Modifier.ABSTRACT | Modifier.NATIVE | Modifier.SYNCHRONIZED)) == 0;
  }

  private static String descriptor(JTypeMirror type) {
    type = type.getErasure();
    if (type instanceof JArrayType array) {
      return "[" + descriptor(array.getComponentType());
    }
    if (type instanceof JPrimitiveType primitive) {
      return switch (primitive.getSimpleName()) {
        case "boolean" -> "Z";
        case "byte" -> "B";
        case "short" -> "S";
        case "char" -> "C";
        case "int" -> "I";
        case "long" -> "J";
        case "float" -> "F";
        case "double" -> "D";
        default -> "?";
      };
    }
    if (type.isVoid()) {
      return "V";
    }
    return type.getSymbol() instanceof JClassSymbol owner && !owner.isUnresolved()
        ? "L" + internalName(owner) + ";"
        : "?";
  }

  private static String internalName(JClassSymbol owner) {
    return owner.getBinaryName().replace('.', '/');
  }

  private static boolean sameOwner(JClassSymbol expected, JTypeDeclSymbol actual) {
    return actual instanceof JClassSymbol owner
        && !expected.isUnresolved()
        && !owner.isUnresolved()
        && expected.getBinaryName().equals(owner.getBinaryName());
  }

  boolean isConstructionStep(JMethodSig signature) {
    return new Proof(this.loader).method(signature);
  }

  /**
   * Proves an instrumental builder allocation, independently of method-body and dispatch evidence.
   *
   * <p>The resolved, non-anonymous allocation must create the exact owner of a linked
   * setter/terminal protocol through a proven constructor, including class initialization. Final
   * owners and methods do not bypass this proof. A caller may group this node with a chain only
   * when its subsequent calls are proven construction steps with exact dispatch. Arguments,
   * consumption and executable-scope boundaries remain separate call-site evidence.
   */
  boolean isBuilderAllocation(ASTConstructorCall call) {
    return this.isBuilderAllocation(call, invocation -> Optional.empty());
  }

  /**
   * Uses the same original-first binding contract as {@link #hasExactReceiver(ASTMethodCall,
   * Function)}.
   */
  boolean isBuilderAllocation(
      ASTConstructorCall call, Function<InvocationNode, Optional<JMethodSig>> recoveredBindings) {
    var proof = new Proof(this.loader, recoveredBindings);
    var signature = proof.bindings.resolve(call, 0);
    return signature != null
        && signature.getSymbol() instanceof JConstructorSymbol constructor
        && proof.allocation(call, constructor.getEnclosingClass())
        && !proof.protocol(constructor.getEnclosingClass()).isEmpty();
  }

  /**
   * Proves resolved dispatch. Also require {@link #isConstructionStep(JMethodSig)}.
   *
   * <p>Final owners, final/private instance methods and type-qualified static calls have fixed
   * dispatch. Otherwise the receiver must be a fresh exact allocation or factory/setter chain.
   * Variables, casts, anonymous classes, aliases and arbitrary helper calls supply no fresh-object
   * evidence. A final setter returning this does not make a subsequent virtual call exact on an
   * unknown receiver. Arguments and receiver evaluation of fixed-dispatch calls remain separate
   * call-site evidence for the caller's traversal, not a purity guarantee from this predicate.
   * Absorbing an allocated receiver into a construction chain also requires {@link
   * #isBuilderAllocation(ASTConstructorCall)}.
   */
  boolean hasExactReceiver(ASTMethodCall call) {
    return this.hasExactReceiver(call, invocation -> Optional.empty());
  }

  /**
   * Uses original PMD bindings whenever selection succeeded. Recovery supplies typing only, never a
   * body or dispatch exemption. Recovered targets must match the AST receiver, member name and
   * uniquely applicable argument signature.
   */
  boolean hasExactReceiver(
      ASTMethodCall call, Function<InvocationNode, Optional<JMethodSig>> recoveredBindings) {
    return new ReceiverProof(new Proof(this.loader, recoveredBindings)).call(call);
  }

  private record MethodKey(String owner, String name, String descriptor) {
    private static MethodKey of(JExecutableSymbol symbol) {
      var erased = symbol.getGenericSignature().getErasure();
      var type = new StringBuilder("(");
      erased
          .getFormalParameters()
          .forEach(parameter -> type.append(VerifiedConstruction.descriptor(parameter)));
      type.append(')');
      type.append(
          symbol instanceof JConstructorSymbol
              ? "V"
              : VerifiedConstruction.descriptor(erased.getReturnType()));
      return new MethodKey(
          internalName(symbol.getEnclosingClass()),
          symbol instanceof JConstructorSymbol ? "<init>" : symbol.getSimpleName(),
          type.toString());
    }

    private static MethodKey of(ClassModel owner, MethodModel method) {
      return new MethodKey(
          owner.thisClass().asInternalName(),
          method.methodName().stringValue(),
          method.methodType().stringValue());
    }
  }

  private record FieldKey(String name, String descriptor) {}

  /** Bounded descent through receiver expressions only; no alias or general data-flow analysis. */
  private static final class ReceiverProof {
    private final Proof proof;

    private ReceiverProof(Proof proof) {
      this.proof = proof;
    }

    private static boolean fixed(int modifiers, boolean finalOwner) {
      return finalOwner || (modifiers & (Modifier.FINAL | Modifier.PRIVATE | Modifier.STATIC)) != 0;
    }

    private static boolean staticQualifier(ASTMethodCall call) {
      return call.getQualifier() == null || call.getQualifier() instanceof ASTTypeExpression;
    }

    private boolean call(ASTMethodCall call) {
      var signature = this.proof.bindings.resolve(call, 0);
      if (signature == null || !(signature.getSymbol() instanceof JMethodSymbol method)) {
        return false;
      }
      if (method.isStatic()) {
        return staticQualifier(call) && this.fixedDispatch(method);
      }
      return this.fixedDispatch(method)
          || this.fresh(call.getQualifier(), method.getEnclosingClass());
    }

    private boolean fixedDispatch(JMethodSymbol method) {
      var owner = method.getEnclosingClass();
      if (owner.tryGetNode() != null) {
        return fixed(method.getModifiers(), owner.isFinal());
      }
      var model = this.proof.classModel(internalName(owner)).orElse(null);
      var declaration = BytecodeProof.find(model, MethodKey.of(method));
      return declaration != null
          && declaration.flags().has(AccessFlag.STATIC) == method.isStatic()
          && fixed(declaration.flags().flagsMask(), model.flags().has(AccessFlag.FINAL));
    }

    private boolean fresh(ASTExpression expression, JClassSymbol expected) {
      for (var depth = 0; depth < MAX_RECEIVER_STEPS; depth++) {
        if (expression instanceof ASTConstructorCall allocation) {
          return this.proof.allocation(allocation, expected);
        }
        if (!(expression instanceof ASTMethodCall link)) {
          return false;
        }
        var signature = this.proof.bindings.resolve(link, 0);
        if (signature == null
            || !(signature.getSymbol() instanceof JMethodSymbol method)
            || !sameOwner(expected, signature.getReturnType().getErasure().getSymbol())
            || !this.proof.method(signature)) {
          return false;
        }
        if (method.isStatic()) {
          // The factory allocates its exact result; recheck live static dispatch.
          return staticQualifier(link) && this.fixedDispatch(method);
        }
        if (!sameOwner(expected, method.getEnclosingClass())) {
          return false;
        }
        // Only a proven setter returns its own owner type. Even a final/private setter returns
        // the same potentially hostile subtype: require freshness all the way to the root.
        expression = link.getQualifier();
      }
      return false;
    }
  }

  /**
   * Per-query binding validation; neither successful nor failed recovery survives a proof request.
   */
  private static final class Bindings {
    private final Function<InvocationNode, Optional<JMethodSig>> recovered;
    private final TypeSystem types;
    private final Map<InvocationNode, Optional<JMethodSig>> resolved = new IdentityHashMap<>();
    private final Map<String, ASTTypeDeclaration> sources = new HashMap<>();

    private Bindings(ClassLoader loader, Function<InvocationNode, Optional<JMethodSig>> recovered) {
      this.recovered = recovered;
      this.types = TypeSystem.usingClassLoaderClasspath(loader);
    }

    private static boolean known(JMethodSig signature) {
      return !signature.getSymbol().isUnresolved()
          && !signature.getSymbol().getEnclosingClass().isUnresolved();
    }

    private static boolean accessible(int modifiers, JClassSymbol owner, JClassSymbol caller) {
      if (Modifier.isPublic(modifiers)
          || owner.getNestRoot().getBinaryName().equals(caller.getNestRoot().getBinaryName())) {
        return true;
      }
      return !Modifier.isPrivate(modifiers)
          && owner.getPackageName().equals(caller.getPackageName());
    }

    private static boolean sameSignature(JMethodSig actual, JMethodSig offered) {
      if (!MethodKey.of(actual.getSymbol()).equals(MethodKey.of(offered.getSymbol()))
          || actual.getModifiers() != offered.getModifiers()
          || !actual
              .getReturnType()
              .equals(project(offered.getReturnType(), actual.getTypeSystem(), 0))) {
        return false;
      }
      for (var index = 0; index < actual.getArity(); index++) {
        var formal = actual.getFormalParameters().get(index);
        if (!formal.equals(
            project(offered.getFormalParameters().get(index), formal.getTypeSystem(), 0))) {
          return false;
        }
      }
      return true;
    }

    private static boolean staticContext(ASTMethodCall call) {
      for (var parent = call.getParent(); parent != null; parent = parent.getParent()) {
        if (parent instanceof ASTMethodDeclaration method) {
          return method.isStatic();
        }
        if (parent instanceof ASTInitializer initializer) {
          return initializer.isStatic();
        }
        if (parent instanceof ASTFieldDeclaration field) {
          return field.isStatic();
        }
        if (parent instanceof ASTTypeDeclaration) {
          return false;
        }
      }
      return false;
    }

    private static JTypeMirror project(JTypeMirror type, TypeSystem types, int depth) {
      if (type == null || depth >= MAX_RECEIVER_STEPS) {
        return null;
      }
      if (type == type.getTypeSystem().NULL_TYPE) {
        return types.NULL_TYPE;
      }
      if (type instanceof JPrimitiveType primitive) {
        return types.getPrimitive(primitive.getKind());
      }
      if (type instanceof JArrayType array) {
        var component = project(array.getComponentType(), types, depth + 1);
        return component == null ? null : types.arrayType(component);
      }
      if (!(type instanceof JClassType original) || original.getSymbol().isUnresolved()) {
        return null;
      }
      var owner = types.getClassSymbol(original.getSymbol().getBinaryName());
      if (owner == null || owner.isUnresolved()) {
        return null;
      }
      List<JTypeMirror> arguments = new ArrayList<>();
      for (var argument : original.getTypeArgs()) {
        var projected = project(argument, types, depth + 1);
        if (projected == null) {
          return null;
        }
        arguments.add(projected);
      }
      return arguments.isEmpty() ? types.rawType(owner) : types.parameterise(owner, arguments);
    }

    private static boolean conversion(JTypeMirror actual, JTypeMirror formal, boolean boxing) {
      if (actual.isConvertibleTo(formal).bySubtyping()) {
        return true;
      }
      if (!boxing || actual == actual.getTypeSystem().NULL_TYPE) {
        return false;
      }
      return actual.isPrimitive()
          ? actual.box().isConvertibleTo(formal).bySubtyping()
          : formal.isPrimitive()
              && actual.unbox().isPrimitive()
              && actual.unbox().isConvertibleTo(formal).bySubtyping();
    }

    private JMethodSig resolve(InvocationNode call, int depth) {
      if (!call.getOverloadSelectionInfo().isFailed()) {
        var original = call.getMethodType();
        return known(original) ? original : null;
      }
      if (depth >= MAX_RECEIVER_STEPS) {
        return null;
      }
      var previous = this.resolved.get(call);
      if (previous != null) {
        return previous.orElse(null);
      }
      call.getRoot()
          .descendants(ASTTypeDeclaration.class)
          .crossFindBoundaries()
          .forEach(source -> sources.putIfAbsent(source.getBinaryName(), source));
      var candidate = this.recovered.apply(call).orElse(null);
      var result =
          candidate != null && this.matches(call, candidate, depth)
              ? this.current(candidate)
              : null;
      this.resolved.put(call, Optional.ofNullable(result));
      return result;
    }

    private boolean matches(InvocationNode call, JMethodSig signature, int depth) {
      if (!known(signature)
          || signature.isVarargs()
          || signature.isGeneric()
          || call.getExplicitTypeArguments() != null
          || signature.getArity() != call.getArguments().size()) {
        return false;
      }
      var receiver = this.receiver(call, depth + 1);
      if (receiver == null
          || !sameOwner(
              signature.getSymbol().getEnclosingClass(), receiver.getErasure().getSymbol())) {
        return false;
      }
      if (call instanceof ASTMethodCall method) {
        if (!(signature.getSymbol() instanceof JMethodSymbol)
            || !method.getMethodName().equals(signature.getName())
            || !signature.isStatic()
                && (method.getQualifier() instanceof ASTTypeExpression
                    || method.getQualifier() == null && staticContext(method))) {
          return false;
        }
      } else if (!(call instanceof ASTConstructorCall allocation)
          || allocation.isAnonymousClass()
          || allocation.getQualifier() != null
          || !(signature.getSymbol() instanceof JConstructorSymbol)) {
        return false;
      }
      return this.accessible(signature, call) && this.unique(call, signature, depth + 1);
    }

    private boolean accessible(JMethodSig method, InvocationNode call) {
      var owner = method.getSymbol().getEnclosingClass();
      var caller = call.getEnclosingType().getSymbol();
      for (var enclosing = owner; enclosing != null; enclosing = enclosing.getEnclosingClass()) {
        var source = this.sources.get(enclosing.getBinaryName());
        var declaration = source == null ? enclosing : source.getSymbol();
        if (!accessible(declaration.getModifiers(), declaration, caller)) {
          return false;
        }
      }
      return accessible(method.getModifiers(), owner, caller);
    }

    private JTypeMirror receiver(InvocationNode call, int depth) {
      if (call instanceof ASTConstructorCall allocation) {
        return allocation.getTypeNode().getTypeMirror();
      }
      if (call instanceof ASTMethodCall method) {
        return method.getQualifier() == null
            ? method.getEnclosingType().getTypeMirror()
            : this.expression(method.getQualifier(), depth);
      }
      return null;
    }

    private JTypeMirror expression(ASTExpression expression, int depth) {
      if (expression instanceof InvocationNode invocation
          && invocation.getOverloadSelectionInfo().isFailed()) {
        var signature = this.resolve(invocation, depth);
        return signature == null ? null : signature.getReturnType();
      }
      return expression.getTypeMirror();
    }

    private boolean unique(InvocationNode call, JMethodSig selected, int depth) {
      var owner =
          this.types.getClassSymbol(selected.getSymbol().getEnclosingClass().getBinaryName());
      if (owner == null || owner.isUnresolved()) {
        return false;
      }
      List<JMethodSig> candidates = new ArrayList<>();
      if (selected.isConstructor()) {
        owner.getConstructors().forEach(member -> candidates.add(member.getGenericSignature()));
      } else {
        owner.getDeclaredMethods().stream()
            .filter(member -> member.getSimpleName().equals(selected.getName()))
            .forEach(member -> candidates.add(member.getGenericSignature()));
      }
      if (candidates.size() > MAX_MEMBERS) {
        return false;
      }
      for (boolean boxing : List.of(false, true)) {
        var applicable =
            candidates.stream()
                .map(this::current)
                .filter(candidate -> candidate != null)
                .filter(candidate -> accepts(call, candidate, depth, boxing))
                .toList();
        if (!applicable.isEmpty()) {
          return applicable.size() == 1 && sameSignature(applicable.getFirst(), selected);
        }
      }
      return false;
    }

    private boolean accepts(InvocationNode call, JMethodSig method, int depth, boolean boxing) {
      if (method.isVarargs()
          || method.isGeneric()
          || method.getArity() != call.getArguments().size()) {
        return false;
      }
      for (var index = 0; index < method.getArity(); index++) {
        var argument = call.getArguments().get(index);
        var formal = method.getFormalParameters().get(index);
        var actual = project(this.expression(argument, depth), formal.getTypeSystem(), 0);
        if (actual == null
            || !(conversion(actual, formal, boxing) || this.emptyFactory(argument, formal))) {
          return false;
        }
      }
      return true;
    }

    private boolean emptyFactory(ASTExpression expression, JTypeMirror formal) {
      if (!(expression instanceof ASTMethodCall call)
          || call.getOverloadSelectionInfo().isFailed()
          || !call.getArguments().isEmpty()
          || call.getExplicitTypeArguments() != null
          || !(formal instanceof JClassType target)
          || !target.isParameterizedType()) {
        return false;
      }
      var method = call.getMethodType();
      var owner = method.getSymbol().getEnclosingClass();
      var name =
          switch (owner.getBinaryName()) {
            case "java.util.Optional" -> "empty";
            case "java.util.List", "java.util.Set", "java.util.Map" -> "of";
            default -> "";
          };
      return known(method)
          && owner.tryGetNode() == null
          && !this.sources.containsKey(owner.getBinaryName())
          && method.getSymbol().tryGetNode() == null
          && method.isStatic()
          && !method.isVarargs()
          && method.getArity() == 0
          && method.getName().equals(name)
          && ReceiverProof.staticQualifier(call)
          && sameOwner(owner, target.getSymbol())
          && sameOwner(owner, method.getReturnType().getErasure().getSymbol());
    }

    private JMethodSig current(JMethodSig signature) {
      var owner = this.sources.get(signature.getSymbol().getEnclosingClass().getBinaryName());
      if (owner == null) {
        return signature;
      }
      var written =
          owner.getOperations().toList().stream()
              .map(operation -> operation.getGenericSignature())
              .filter(
                  method ->
                      method.isConstructor() == signature.isConstructor()
                          && (signature.isConstructor()
                              || method.getName().equals(signature.getName()))
                          && (method.isVarargs() || method.getArity() == signature.getArity()))
              .toList();
      return written.isEmpty()
          ? signature
          : written.stream()
              .filter(
                  method ->
                      MethodKey.of(method.getSymbol()).equals(MethodKey.of(signature.getSymbol())))
              .findFirst()
              .orElse(null);
    }

    private JClassSymbol sourceOwner(String internalName) {
      var source = this.sources.get(internalName.replace('/', '.'));
      return source == null ? null : source.getSymbol();
    }
  }

  /** Both evidence adapters use exactly the same field-linking rule. */
  private static final class Protocol {
    private final Map<MethodKey, FieldKey> setters = new HashMap<>();
    private final Map<MethodKey, List<FieldKey>> terminals = new HashMap<>();

    private Set<MethodKey> steps() {
      Set<MethodKey> result = new HashSet<>();
      this.terminals.forEach(
          (method, fields) -> {
            if (!fields.isEmpty() && this.setters.values().containsAll(fields)) {
              result.add(method);
              this.setters.forEach(
                  (setter, field) -> {
                    if (fields.contains(field)) {
                      result.add(setter);
                    }
                  });
            }
          });
      return result;
    }
  }

  private static final class Proof {
    private final ClassLoader loader;
    private final Bindings bindings;
    private final Map<String, Optional<ClassModel>> classes = new HashMap<>();

    private Proof(ClassLoader loader) {
      this(loader, invocation -> Optional.empty());
    }

    private Proof(
        ClassLoader loader, Function<InvocationNode, Optional<JMethodSig>> recoveredBindings) {
      this.loader = loader;
      this.bindings = new Bindings(loader, recoveredBindings);
    }

    private boolean method(JMethodSig signature) {
      if (!(signature.getSymbol() instanceof JMethodSymbol method)
          || method.isUnresolved()
          || method.getEnclosingClass().isUnresolved()
          || !eligible(method.getModifiers())
          || !(signature.getReturnType().getErasure().getSymbol() instanceof JClassSymbol result)
          || result.isUnresolved()
          || result.isArray()
          || result.isPrimitive()) {
        return false;
      }
      if (method.getEnclosingClass().tryGetNode() != null) {
        return method.isStatic()
            ? new SourceProof(this).factory(method)
            : this.protocol(method.getEnclosingClass()).contains(MethodKey.of(method));
      }
      return new BytecodeProof(this).method(MethodKey.of(method));
    }

    private Set<MethodKey> protocol(JClassSymbol owner) {
      if (owner.isUnresolved()) {
        return Set.of();
      }
      return owner.tryGetNode() == null
          ? new BytecodeProof(this).protocol(internalName(owner))
          : new SourceProof(this).protocol(owner);
    }

    private boolean allocation(ASTConstructorCall allocation, JClassSymbol expected) {
      var signature = this.bindings.resolve(allocation, 0);
      return !allocation.isAnonymousClass()
          && allocation.getQualifier() == null
          && signature != null
          && signature.getSymbol() instanceof JConstructorSymbol constructor
          && sameOwner(expected, constructor.getEnclosingClass())
          && this.constructor(constructor);
    }

    private boolean constructor(JConstructorSymbol constructor) {
      if (constructor.isUnresolved() || constructor.isVarargs()) {
        return false;
      }
      return constructor.getEnclosingClass().tryGetNode() == null
          ? new BytecodeProof(this).constructor(MethodKey.of(constructor))
          : SourceProof.constructor(constructor);
    }

    private Optional<ClassModel> classModel(String owner) {
      return this.classes.computeIfAbsent(owner, this::readClass);
    }

    private Optional<ClassModel> readClass(String owner) {
      var resource = owner + ".class";
      try (var input = this.loader.getResourceAsStream(resource)) {
        if (input == null) {
          return Optional.empty();
        }
        var bytes = input.readNBytes(MAX_CLASS_BYTES + 1);
        if (bytes.length > MAX_CLASS_BYTES) {
          throw new IllegalArgumentException("Class resource exceeds proof limit: " + resource);
        }
        var hierarchy = ClassHierarchyResolver.ofResourceParsing(this.loader);
        var parser = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(hierarchy));
        var model = parser.parse(bytes);
        if (!model.thisClass().asInternalName().equals(owner)) {
          throw new IllegalArgumentException("Class resource has wrong owner: " + resource);
        }
        var errors = parser.verify(model);
        if (!errors.isEmpty()) {
          throw new IllegalArgumentException(
              "Invalid class resource: " + resource, errors.getFirst());
        }
        return Optional.of(model);
      } catch (IOException exception) {
        throw new UncheckedIOException("Cannot read construction evidence: " + resource, exception);
      }
    }
  }

  private static final class SourceProof {
    private final Proof proof;

    private SourceProof(Proof proof) {
      this.proof = proof;
    }

    private static FieldKey setter(ASTMethodDeclaration method) {
      var body = method.getBody();
      var owner = method.getSymbol().getEnclosingClass();
      if (method.getArity() != 1
          || body == null
          || body.size() != 2
          || !owner.equals(method.getGenericSignature().getReturnType().getSymbol())
          || !(body.get(1) instanceof ASTReturnStatement returned)
          || !self(returned.getExpr())) {
        return null;
      }
      return assignment(
          body.get(0), owner, method.getFormalParameters().get(0).getVarId().getSymbol());
    }

    private static ASTConstructorCall allocation(ASTMethodDeclaration method) {
      if (method == null || method.getArity() != 0 || method.getBody() == null) {
        return null;
      }
      var body = method.getBody();
      if (body.size() != 1
          || !(body.get(0) instanceof ASTReturnStatement returned)
          || !(returned.getExpr() instanceof ASTConstructorCall call)
          || call.isAnonymousClass()
          || call.getQualifier() != null
          || call.getOverloadSelectionInfo().isFailed()
          || !(call.getMethodType().getSymbol() instanceof JConstructorSymbol constructor)
          || constructor.isUnresolved()
          || constructor.isVarargs()) {
        return null;
      }
      return descriptor(call.getTypeMirror())
              .equals(descriptor(method.getGenericSignature().getReturnType()))
          ? call
          : null;
    }

    private static FieldKey assignment(
        ASTStatement statement, JClassSymbol owner, JVariableSymbol parameter) {
      if (!(statement instanceof ASTExpressionStatement expression)
          || !(expression.getExpr() instanceof ASTAssignmentExpression assignment)
          || assignment.isCompound()
          || !(assignment.getRightOperand() instanceof ASTVariableAccess value)
          || !parameter.equals(value.getReferencedSym())) {
        return null;
      }
      var field = ownField(assignment.getLeftOperand(), owner);
      return field != null && field.descriptor().equals(descriptor(value.getTypeMirror()))
          ? field
          : null;
    }

    private static FieldKey ownField(ASTExpression expression, JClassSymbol owner) {
      JVariableSymbol symbol =
          switch (expression) {
            case ASTVariableAccess variable -> variable.getReferencedSym();
            case ASTFieldAccess field when self(field.getQualifier()) -> field.getReferencedSym();
            default -> null;
          };
      if (!(symbol instanceof JFieldSymbol field)
          || field.isUnresolved()
          || Modifier.isStatic(field.getModifiers())
          || !field.getEnclosingClass().equals(owner)) {
        return null;
      }
      String type = descriptor(expression.getTypeMirror());
      return type.contains("?") ? null : new FieldKey(field.getSimpleName(), type);
    }

    private static boolean self(ASTExpression expression) {
      return expression instanceof ASTThisExpression self && self.getQualifier() == null;
    }

    private static boolean plainOwner(JClassSymbol owner) {
      var node = owner.tryGetNode();
      if (node == null
          || !node.isRegularClass()
          || owner.isAbstract()
          || (node.isNested() && !node.isStatic())
          || !objectSuperclass(owner)) {
        return false;
      }
      return node.getDeclarations(ASTInitializer.class).none(initializer -> !initializer.isStatic())
          && owner.getDeclaredFields().stream()
              .filter(field -> !Modifier.isStatic(field.getModifiers()))
              .allMatch(
                  field ->
                      field.tryGetNode() != null && field.tryGetNode().getInitializer() == null);
    }

    private static boolean constructor(JConstructorSymbol constructor) {
      var owner = constructor.getEnclosingClass();
      if (!plainOwner(owner)
          || !initializationFree(owner)
          || constructor.getArity() > MAX_MEMBERS) {
        return false;
      }
      var node = constructor.tryGetNode();
      if (node == null) {
        return constructor.getArity() == 0
            && owner.tryGetNode().getDeclarations(ASTConstructorDeclaration.class).isEmpty();
      }
      var body = node.getBody();
      var offset = 0;
      if (!body.isEmpty() && body.get(0) instanceof ASTExplicitConstructorInvocation invocation) {
        if (!invocation.isSuper()
            || invocation.isQualified()
            || !invocation.getArguments().isEmpty()) {
          return false;
        }
        offset = 1;
      }
      if (body.size() != constructor.getArity() + offset) {
        return false;
      }
      Set<FieldKey> assigned = new HashSet<>();
      for (var index = 0; index < constructor.getArity(); index++) {
        var field =
            assignment(
                body.get(index + offset),
                owner,
                node.getFormalParameters().get(index).getVarId().getSymbol());
        if (field == null || !assigned.add(field)) {
          return false;
        }
      }
      return true;
    }

    private static boolean objectSuperclass(JClassSymbol owner) {
      var parent = owner.getSuperclass();
      return parent != null
          && !parent.isUnresolved()
          && parent.getBinaryName().equals("java.lang.Object");
    }

    private static boolean initializationFree(JClassSymbol owner) {
      var node = owner.tryGetNode();
      if (node == null
          || !node.isRegularClass()
          || !objectSuperclass(owner)
          || !owner.getSuperInterfaces().isEmpty()) {
        return false;
      }
      return node.getDeclarations(ASTInitializer.class)
              .none(initializer -> initializer.isStatic() && !initializer.getBody().isEmpty())
          && owner.getDeclaredFields().stream()
              .filter(field -> Modifier.isStatic(field.getModifiers()))
              .allMatch(SourceProof::constantOrUninitialized);
    }

    private static boolean constantOrUninitialized(JFieldSymbol field) {
      var node = field.tryGetNode();
      return node != null
          && (node.getInitializer() == null || (field.isFinal() && field.getConstValue() != null));
    }

    private Set<MethodKey> protocol(JClassSymbol owner) {
      var methods = owner.getDeclaredMethods();
      if (methods.size() > MAX_MEMBERS) {
        return Set.of();
      }
      var protocol = new Protocol();
      for (var method : methods) {
        var node = method.tryGetNode();
        if (node == null || method.isStatic() || !eligible(method.getModifiers())) {
          continue;
        }
        var field = setter(node);
        if (field != null) {
          protocol.setters.put(MethodKey.of(method), field);
        }
        var fields = this.terminal(node);
        if (!fields.isEmpty()) {
          protocol.terminals.put(MethodKey.of(method), fields);
        }
      }
      return protocol.steps();
    }

    private boolean factory(JMethodSymbol method) {
      var node = method.tryGetNode();
      var call = allocation(node);
      return call != null
          && call.getArguments().isEmpty()
          && initializationFree(method.getEnclosingClass())
          && this.proof.constructor((JConstructorSymbol) call.getMethodType().getSymbol())
          && !this.proof.protocol(call.getMethodType().getSymbol().getEnclosingClass()).isEmpty();
    }

    private List<FieldKey> terminal(ASTMethodDeclaration method) {
      var call = allocation(method);
      if (call == null
          || call.getArguments().isEmpty()
          || call.getArguments().size() > MAX_MEMBERS
          || method
              .getSymbol()
              .getEnclosingClass()
              .equals(call.getMethodType().getSymbol().getEnclosingClass())) {
        return List.of();
      }
      List<FieldKey> fields = new ArrayList<>();
      for (var index = 0; index < call.getArguments().size(); index++) {
        var field =
            ownField(call.getArguments().get(index), method.getSymbol().getEnclosingClass());
        if (field == null
            || !field
                .descriptor()
                .equals(descriptor(call.getMethodType().getFormalParameters().get(index)))) {
          return List.of();
        }
        fields.add(field);
      }
      return this.proof.constructor((JConstructorSymbol) call.getMethodType().getSymbol())
          ? fields
          : List.of();
    }
  }

  /** Matches three finite instruction templates; this is intentionally not a JVM interpreter. */
  private static final class BytecodeProof {
    private final Proof proof;

    private BytecodeProof(Proof proof) {
      this.proof = proof;
    }

    private static FieldKey setter(ClassModel owner, MethodModel method) {
      var type = method.methodTypeSymbol();
      var code = instructions(method);
      if (type.parameterCount() != 1
          || !type.returnType().equals(owner.thisClass().asSymbol())
          || code.size() != 5
          || !load(code.get(0), 0, owner.thisClass().asSymbol())
          || !load(code.get(1), 1, type.parameterType(0))
          || !load(code.get(3), 0, owner.thisClass().asSymbol())
          || code.get(4).opcode() != Opcode.ARETURN) {
        return null;
      }
      return field(owner, code.get(2), Opcode.PUTFIELD, type.parameterType(0));
    }

    private static boolean plainOwner(ClassModel owner) {
      return !owner.flags().has(AccessFlag.ABSTRACT)
          && owner
              .superclass()
              .map(parent -> parent.asInternalName().equals("java/lang/Object"))
              .orElse(false)
          && owner.fields().stream().noneMatch(field -> field.flags().has(AccessFlag.SYNTHETIC));
    }

    private static boolean initializationFree(ClassModel owner) {
      if (owner.flags().has(AccessFlag.INTERFACE)
          || !owner.interfaces().isEmpty()
          || !owner
              .superclass()
              .map(parent -> parent.asInternalName().equals("java/lang/Object"))
              .orElse(false)) {
        return false;
      }
      var initializer =
          find(owner, new MethodKey(owner.thisClass().asInternalName(), "<clinit>", "()V"));
      if (initializer == null) {
        return true;
      }
      var code = instructions(initializer);
      return code.size() == 1 && code.getFirst().opcode() == Opcode.RETURN;
    }

    private static MethodModel find(ClassModel owner, MethodKey key) {
      return owner == null
          ? null
          : owner.methods().stream()
              .filter(method -> method.methodName().equalsString(key.name()))
              .filter(method -> method.methodType().equalsString(key.descriptor()))
              .findFirst()
              .orElse(null);
    }

    private static List<Instruction> instructions(MethodModel method) {
      var body = method.code().orElse(null);
      if (body == null || !body.exceptionHandlers().isEmpty()) {
        return List.of();
      }
      List<Instruction> result = new ArrayList<>();
      for (var element : body) {
        if (element instanceof Instruction instruction) {
          if (result.size() == MAX_INSTRUCTIONS) {
            return List.of();
          }
          result.add(instruction);
        }
      }
      return result;
    }

    private static boolean load(Instruction instruction, int slot, ClassDesc type) {
      return instruction instanceof LoadInstruction load
          && load.slot() == slot
          && load.typeKind() == TypeKind.from(type).asLoadable();
    }

    private static boolean invokesConstructor(InvokeInstruction instruction, String owner) {
      return instruction.opcode() == Opcode.INVOKESPECIAL
          && !instruction.isInterface()
          && instruction.owner().asInternalName().equals(owner)
          && instruction.name().equalsString("<init>")
          && instruction.type().stringValue().endsWith(")V");
    }

    private static FieldKey field(
        ClassModel owner, Instruction instruction, Opcode opcode, ClassDesc type) {
      var ownerName = owner.thisClass().asInternalName();
      if (!(instruction instanceof FieldInstruction field)
          || field.opcode() != opcode
          || !field.owner().asInternalName().equals(ownerName)
          || !field.typeSymbol().equals(type)) {
        return null;
      }
      return owner.fields().stream()
          .filter(declaration -> !declaration.flags().has(AccessFlag.STATIC))
          .filter(declaration -> declaration.fieldName().equalsString(field.name().stringValue()))
          .filter(declaration -> declaration.fieldTypeSymbol().equals(type))
          .findFirst()
          .map(declaration -> new FieldKey(field.name().stringValue(), type.descriptorString()))
          .orElse(null);
    }

    private boolean method(MethodKey key) {
      var owner = this.proof.classModel(key.owner()).orElse(null);
      var method = find(owner, key);
      if (method == null || !eligible(method.flags().flagsMask())) {
        return false;
      }
      if (!method.flags().has(AccessFlag.STATIC)) {
        return this.protocol(key.owner()).contains(key);
      }
      var source = this.proof.bindings.sourceOwner(key.owner());
      if (!initializationFree(owner) || source != null && !SourceProof.initializationFree(source)) {
        return false;
      }
      var fields = this.allocation(owner, method);
      if (fields == null || !fields.isEmpty()) {
        return false;
      }
      var result = method.methodTypeSymbol().returnType().descriptorString();
      return !this.protocol(result.substring(1, result.length() - 1)).isEmpty();
    }

    private Set<MethodKey> protocol(String name) {
      var source = this.proof.bindings.sourceOwner(name);
      if (source != null) {
        return new SourceProof(this.proof).protocol(source);
      }
      var owner = this.proof.classModel(name).orElse(null);
      if (owner == null || owner.methods().size() > MAX_MEMBERS) {
        return Set.of();
      }
      var protocol = new Protocol();
      for (var method : owner.methods()) {
        if (method.flags().has(AccessFlag.STATIC) || !eligible(method.flags().flagsMask())) {
          continue;
        }
        var field = setter(owner, method);
        if (field != null) {
          protocol.setters.put(MethodKey.of(owner, method), field);
        }
        var fields = this.allocation(owner, method);
        if (fields != null
            && !fields.isEmpty()
            && !method.methodTypeSymbol().returnType().equals(owner.thisClass().asSymbol())) {
          protocol.terminals.put(MethodKey.of(owner, method), fields);
        }
      }
      return protocol.steps();
    }

    private List<FieldKey> allocation(ClassModel owner, MethodModel method) {
      var code = instructions(method);
      var returnType = method.methodTypeSymbol().returnType();
      if (method.methodTypeSymbol().parameterCount() != 0
          || code.size() < 4
          || !(code.get(0) instanceof NewObjectInstruction created)
          || !created.className().asSymbol().equals(returnType)
          || code.get(1).opcode() != Opcode.DUP
          || code.getLast().opcode() != Opcode.ARETURN
          || !(code.get(code.size() - 2) instanceof InvokeInstruction invoked)
          || !invokesConstructor(invoked, created.className().asInternalName())) {
        return null;
      }
      var type = invoked.typeSymbol();
      if (code.size() != 4 + 2 * type.parameterCount()) {
        return null;
      }
      List<FieldKey> fields = new ArrayList<>();
      for (var index = 0; index < type.parameterCount(); index++) {
        if (!load(code.get(2 + 2 * index), 0, owner.thisClass().asSymbol())) {
          return null;
        }
        var field =
            field(owner, code.get(3 + 2 * index), Opcode.GETFIELD, type.parameterType(index));
        if (field == null) {
          return null;
        }
        fields.add(field);
      }
      var key =
          new MethodKey(invoked.owner().asInternalName(), "<init>", invoked.type().stringValue());
      return this.constructor(key) ? fields : null;
    }

    private boolean constructor(MethodKey key) {
      var source = this.proof.bindings.sourceOwner(key.owner());
      if (source != null) {
        if (!SourceProof.plainOwner(source) || !SourceProof.initializationFree(source)) {
          return false;
        }
        var written =
            source
                .tryGetNode()
                .getDeclarations(ASTConstructorDeclaration.class)
                .first(constructor -> MethodKey.of(constructor.getSymbol()).equals(key));
        if (written != null) {
          return SourceProof.constructor(written.getSymbol());
        }
      }
      var owner = this.proof.classModel(key.owner()).orElse(null);
      var method = find(owner, key);
      if (method == null
          || !plainOwner(owner)
          || !initializationFree(owner)
          || method.flags().has(AccessFlag.VARARGS)) {
        return false;
      }
      var type = method.methodTypeSymbol();
      var code = instructions(method);
      if (code.size() != 3 + 3 * type.parameterCount()
          || !load(code.get(0), 0, owner.thisClass().asSymbol())
          || !(code.get(1) instanceof InvokeInstruction parent)
          || !invokesConstructor(parent, "java/lang/Object")
          || parent.typeSymbol().parameterCount() != 0
          || code.getLast().opcode() != Opcode.RETURN) {
        return false;
      }
      Set<FieldKey> assigned = new HashSet<>();
      var slot = 1;
      for (var index = 0; index < type.parameterCount(); index++) {
        var parameter = type.parameterType(index);
        var field = field(owner, code.get(4 + 3 * index), Opcode.PUTFIELD, parameter);
        if (!load(code.get(2 + 3 * index), 0, owner.thisClass().asSymbol())
            || !load(code.get(3 + 3 * index), slot, parameter)
            || field == null
            || !assigned.add(field)) {
          return false;
        }
        slot += TypeKind.from(parameter).slotSize();
      }
      return true;
    }
  }
}
