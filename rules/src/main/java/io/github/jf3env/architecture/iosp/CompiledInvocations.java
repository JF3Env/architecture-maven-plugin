package io.github.jf3env.architecture.iosp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.sourceforge.pmd.lang.java.ast.ASTCompilationUnit;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorCall;
import net.sourceforge.pmd.lang.java.ast.ASTExplicitConstructorInvocation;
import net.sourceforge.pmd.lang.java.ast.ASTExpression;
import net.sourceforge.pmd.lang.java.ast.ASTFieldDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTInitializer;
import net.sourceforge.pmd.lang.java.ast.ASTLambdaExpression;
import net.sourceforge.pmd.lang.java.ast.ASTMethodCall;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTMethodReference;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTTypeExpression;
import net.sourceforge.pmd.lang.java.ast.InvocationNode;
import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.types.JArrayType;
import net.sourceforge.pmd.lang.java.types.JClassType;
import net.sourceforge.pmd.lang.java.types.JMethodSig;
import net.sourceforge.pmd.lang.java.types.JPrimitiveType;
import net.sourceforge.pmd.lang.java.types.JTypeMirror;
import net.sourceforge.pmd.lang.java.types.JTypeVar;
import net.sourceforge.pmd.lang.java.types.JWildcardType;
import net.sourceforge.pmd.lang.java.types.Substitution;
import net.sourceforge.pmd.lang.java.types.TypeSystem;

/**
 * Class-resource binding fallback, only after PMD overload selection failed. This is not an IOSP
 * exemption: a resolved member supplies no evidence about its behavior, initialization or dispatch
 * purity.
 *
 * <p>A fresh, resource-only type system avoids PMD's incomplete source symbols for Lombok members.
 * Source declarations still take precedence: a source owner requires method-level {@code
 * lombok.Generated} classfile metadata, and any written same-name/same-arity declaration blocks
 * binary fallback (including annotations on handwritten declarations). All nested/local source
 * types are registered across traversal boundaries. A previously untyped qualifier repaired through
 * verified generated bindings may enable selection of a directly declared current AST method. That
 * path returns the AST signature, never the old classfile method; additional compiled overloads
 * remain competitors. Different-arity generated overloads remain eligible. The surrounding gate
 * must establish source/class freshness.
 *
 * <p>Only a unique fixed-arity candidate in the first applicable strict/boxing phase is accepted.
 * Generic methods, unresolved types, unchecked conversions, competing applicable overloads,
 * varargs, captured constructor parameters and poly-expression inference that cannot be established
 * are deliberately unsupported. Concrete parameterized types are preserved; erasure is never used
 * to make incompatible generic arguments applicable. The only target-typing exception is a
 * type-qualified or statically imported, implicit-type-argument, zero-argument JDK
 * Optional.empty/List.of/Set.of/Map.of with an already resolved exact declaration and verified
 * resource, targeting that same parameterized container class. This proves a closed empty-factory
 * contract, not arbitrary generic inference. Repaired-source binding excludes inherited
 * declarations, generic source owners/methods and chains whose repair required a nongenerated
 * binding.
 *
 * <p>The two exact JDK EnumSet.copyOf descriptors have a separate closed substitution proof: a
 * concrete Collection<E> supertype supplies E, E must be an enum satisfying the declaration's
 * Enum<E> bound, and the returned signature is that declaration with E substituted. Both overloads
 * remain candidates; an EnumSet argument for which both apply is deliberately unsupported rather
 * than resolved by an ad-hoc most-specific rule. Other generic methods retain the normal PMD
 * binding or remain unsupported. Raw, wildcard and type-variable elements are not inferred or
 * erased. This path requires an exact type-qualified JDK receiver; unresolved static-import
 * receivers remain unsupported.
 *
 * <p>Resources are snapshotted and verified per request, never loaded as application classes.
 * Missing, unreadable, malformed or oversized evidence fails closed. No positive or negative result
 * survives a request.
 */
final class CompiledInvocations {
  private static final int MAX_DEPTH = 32;
  private static final int MAX_MEMBERS = 256;
  private static final int MAX_CLASS_BYTES = 4 * 1024 * 1024;
  private static final String ENUM_SET = "java.util.EnumSet";
  private static final Set<String> ENUM_COPY_DESCRIPTORS =
      Set.of(
          "(Ljava/util/Collection;)Ljava/util/EnumSet;",
          "(Ljava/util/EnumSet;)Ljava/util/EnumSet;");
  private final ClassLoader loader;

  CompiledInvocations(ClassLoader loader) {
    this.loader = loader;
  }

  Optional<JMethodSig> resolve(InvocationNode invocation) {
    if (!invocation.getOverloadSelectionInfo().isFailed()) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(new Proof(this.loader, invocation).resolve(invocation, 0));
    } catch (IllegalArgumentException | IllegalStateException | UncheckedIOException exception) {
      return Optional.empty();
    }
  }

  private static final class Proof {
    private final Resources resources;
    private final TypeSystem types;
    private final Map<String, ASTTypeDeclaration> sources = new HashMap<>();
    private final Set<InvocationNode> generatedRepairs = new HashSet<>();

    private Proof(ClassLoader loader, InvocationNode invocation) {
      this.resources = new Resources(loader);
      this.types = TypeSystem.usingClassLoaderClasspath(this.resources);
      this.registerSources(invocation.getRoot());
    }

    private static boolean unaryType(JTypeMirror type, String owner, JTypeMirror parameter) {
      return type instanceof JClassType container
          && !container.getSymbol().isUnresolved()
          && container.getSymbol().getBinaryName().equals(owner)
          && container.getTypeArgs().equals(List.of(parameter));
    }

    private static boolean untypedQualifier(InvocationNode invocation) {
      if (!(invocation instanceof ASTMethodCall call) || call.getQualifier() == null) {
        return false;
      }
      var type = call.getQualifier().getTypeMirror();
      return type == type.getTypeSystem().UNKNOWN || type == type.getTypeSystem().ERROR;
    }

    private static boolean sameParameters(JMethodSig first, JMethodSig second) {
      return first.getErasure().getFormalParameters().stream()
          .map(Resources::descriptor)
          .toList()
          .equals(
              second.getErasure().getFormalParameters().stream()
                  .map(Resources::descriptor)
                  .toList());
    }

    private static boolean ordinaryOwner(JClassType type, InvocationNode invocation) {
      var symbol = type.getSymbol();
      if (symbol.isUnresolved()
          || symbol.isLocalClass()
          || symbol.isAnonymousClass()
          || type.isRaw()
          || type.isGenericTypeDeclaration()) {
        return false;
      }
      if (invocation instanceof ASTMethodCall) {
        return true;
      }
      return !symbol.isEnum()
          && !symbol.isInterface()
          && (symbol.getEnclosingClass() == null || symbol.isStatic())
          && (!(invocation instanceof ASTConstructorCall) || !symbol.isAbstract());
    }

    private static List<JMethodSig> candidates(JClassType receiver, InvocationNode invocation) {
      var members =
          invocation instanceof ASTMethodCall method
              ? receiver.streamMethods(
                  symbol -> symbol.getSimpleName().equals(method.getMethodName()))
              : receiver.getConstructors().stream();
      int arity = invocation.getArguments().size();
      return members
          .filter(method -> method.isVarargs() || method.getArity() == arity)
          .limit(MAX_MEMBERS + 1L)
          .toList();
    }

    private static boolean exactFactoryQualifier(ASTMethodCall call, JClassSymbol owner) {
      return call.getQualifier() == null
          || call.getQualifier() instanceof ASTTypeExpression qualifier
              && qualifier.getTypeMirror().getSymbol() instanceof JClassSymbol type
              && !type.isUnresolved()
              && type.tryGetNode() == null
              && type.getBinaryName().equals(owner.getBinaryName());
    }

    private static boolean conversion(JTypeMirror actual, JTypeMirror formal, boolean boxing) {
      if (actual.isConvertibleTo(formal).bySubtyping()) {
        return true;
      }
      if (!boxing || actual == actual.getTypeSystem().NULL_TYPE) {
        return false;
      }
      if (actual.isPrimitive() && !formal.isPrimitive()) {
        return actual.box().isConvertibleTo(formal).bySubtyping();
      }
      return !actual.isPrimitive()
          && formal.isPrimitive()
          && actual.unbox().isPrimitive()
          && actual.unbox().isConvertibleTo(formal).bySubtyping();
    }

    private static String typeShape(JTypeMirror type, int depth) {
      if (type == null) {
        return "";
      }
      if (depth >= MAX_DEPTH) {
        throw new IllegalArgumentException("Type evidence exceeds binding limit");
      }
      if (type instanceof JArrayType array) {
        return "[" + typeShape(array.getComponentType(), depth + 1);
      }
      if (type instanceof JClassType owner && !owner.getSymbol().isUnresolved()) {
        return owner.getSymbol().getBinaryName()
            + owner.getTypeArgs().stream().map(argument -> typeShape(argument, depth + 1)).toList();
      }
      throw new IllegalArgumentException("Unproven source hierarchy type: " + type);
    }

    private static boolean noWrittenCompetitor(
        ASTTypeDeclaration owner, JMethodSig selected, InvocationNode invocation) {
      if (owner == null) {
        return true;
      }
      // A changed handwritten signature must not be replaced by an old generated binary overload.
      return owner
          .getOperations()
          .none(
              written -> {
                var signature = written.getGenericSignature();
                boolean sameName =
                    signature.isConstructor() == selected.isConstructor()
                        && (selected.isConstructor()
                            || signature.getName().equals(selected.getName()));
                return sameName
                    && (signature.isVarargs()
                        || signature.getArity() == invocation.getArguments().size());
              });
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

    private void registerSources(ASTCompilationUnit root) {
      root.descendants(ASTTypeDeclaration.class)
          .crossFindBoundaries(true)
          .forEach(source -> sources.put(source.getBinaryName(), source));
    }

    private JMethodSig resolve(InvocationNode invocation, int depth) {
      if (depth >= MAX_DEPTH || invocation.getExplicitTypeArguments() != null) {
        return null;
      }
      boolean untypedQualifier = untypedQualifier(invocation);
      JTypeMirror receiver = this.receiver(invocation, depth);
      if (!(this.project(receiver, false, 0) instanceof JClassType compiled)) {
        return null;
      }
      boolean enumCopy = this.enumCopyCall(invocation, compiled);
      if (!enumCopy && !ordinaryOwner(compiled, invocation)) {
        return null;
      }
      this.rememberSource(receiver);
      List<Argument> arguments = new ArrayList<>();
      for (var argument : invocation.getArguments()) {
        var type = this.expression(argument, depth + 1);
        if (type == null) {
          return null;
        }
        arguments.add(new Argument(argument, type));
      }
      if (enumCopy) {
        return this.enumCopyBinding(compiled, arguments);
      }
      var candidates = candidates(compiled, invocation);
      if (untypedQualifier
          && this.generatedQualifier(invocation)
          && this.hasSourceMethods(compiled, invocation)) {
        return this.sourceBinding(compiled, invocation, candidates, arguments);
      }
      var selected = this.select(candidates, arguments);
      if (selected == null
          || !this.accessible(selected, invocation)
          || !this.sourceAllows(selected, invocation, compiled)
          || !this.resources.proves(
              selected, this.source(selected.getSymbol().getEnclosingClass()) != null)) {
        return null;
      }
      if (this.generatedPrefix(invocation) && this.resources.proves(selected, true)) {
        this.generatedRepairs.add(invocation);
      }
      return selected;
    }

    private boolean enumCopyCall(InvocationNode invocation, JClassType receiver) {
      return invocation instanceof ASTMethodCall call
          && call.getMethodName().equals("copyOf")
          && this.realJdkOwner(receiver.getSymbol(), ENUM_SET)
          && exactFactoryQualifier(call, receiver.getSymbol());
    }

    private boolean realJdkOwner(JClassSymbol owner, String name) {
      return !owner.isUnresolved()
          && owner.getBinaryName().equals(name)
          && owner.tryGetNode() == null
          && this.source(owner) == null;
    }

    private JMethodSig enumCopyBinding(JClassType receiver, List<Argument> arguments) {
      if (arguments.size() != 1) {
        return null;
      }
      var actual = arguments.getFirst().type();
      var element = this.concreteEnumElement(actual);
      if (element == null) {
        return null;
      }
      var declarations =
          receiver.getSymbol().getDeclaredMethods().stream()
              .filter(method -> method.getSimpleName().equals("copyOf"))
              .map(method -> method.getGenericSignature())
              .toList();
      if (declarations.size() != ENUM_COPY_DESCRIPTORS.size()) {
        return null;
      }
      var descriptors = declarations.stream().map(Resources::descriptor).toList();
      if (!new HashSet<>(descriptors).equals(ENUM_COPY_DESCRIPTORS)) {
        return null;
      }
      List<JMethodSig> applicable = new ArrayList<>();
      for (var declaration : declarations) {
        if (!this.enumCopyDeclaration(declaration)) {
          return null;
        }
        var bound = this.specializeEnumCopy(declaration, element);
        if (bound == null) {
          return null;
        }
        if (actual.isConvertibleTo(bound.getFormalParameters().getFirst()).bySubtyping()) {
          applicable.add(bound);
        }
      }
      return applicable.size() == 1 ? applicable.getFirst() : null;
    }

    private JClassType concreteEnumElement(JTypeMirror actual) {
      var collection = this.types.getClassSymbol("java.util.Collection");
      if (!(actual instanceof JClassType container)
          || container.isRaw()
          || collection == null
          || !this.realJdkOwner(collection, "java.util.Collection")
          || this.resources.verified(collection).isEmpty()) {
        return null;
      }
      var supertype = container.getAsSuper(collection);
      if (supertype == null
          || supertype.isRaw()
          || supertype.getTypeArgs().size() != 1
          || !(supertype.getTypeArgs().getFirst() instanceof JClassType element)) {
        return null;
      }
      return element.getSymbol().isEnum()
              && !element.getSymbol().isUnresolved()
              && !element.isGeneric()
              && this.resources.verified(element.getSymbol()).isPresent()
          ? element
          : null;
    }

    private boolean enumCopyDeclaration(JMethodSig method) {
      if (!this.realJdkOwner(method.getSymbol().getEnclosingClass(), ENUM_SET)
          || method.getSymbol().isUnresolved()
          || method.getSymbol().tryGetNode() != null
          || !Modifier.isPublic(method.getModifiers())
          || !method.isStatic()
          || method.isVarargs()
          || method.getArity() != 1
          || method.getTypeParameters().size() != 1) {
        return false;
      }
      var parameter = method.getTypeParameters().getFirst();
      var formal = method.getFormalParameters().getFirst();
      return !parameter.isCaptured()
          && (unaryType(formal, "java.util.Collection", parameter)
              || unaryType(formal, ENUM_SET, parameter))
          && unaryType(method.getReturnType(), ENUM_SET, parameter)
          && unaryType(parameter.getUpperBound(), "java.lang.Enum", parameter)
          && this.resources.proves(method, false);
    }

    private JMethodSig specializeEnumCopy(JMethodSig declaration, JClassType element) {
      var parameter = declaration.getTypeParameters().getFirst();
      var substitution = Substitution.EMPTY.plus(parameter, element);
      var bound = parameter.getUpperBound().subst(substitution);
      if (!(bound instanceof JClassType enumBound)
          || !this.realJdkOwner(enumBound.getSymbol(), "java.lang.Enum")
          || this.resources.verified(enumBound.getSymbol()).isEmpty()
          || !element.isConvertibleTo(bound).bySubtyping()
          || !bound.equals(element.getAsSuper(enumBound.getSymbol()))) {
        return null;
      }
      var specialized = declaration.subst(substitution);
      return unaryType(specialized.getReturnType(), ENUM_SET, element)
              && (unaryType(
                      specialized.getFormalParameters().getFirst(), "java.util.Collection", element)
                  || unaryType(specialized.getFormalParameters().getFirst(), ENUM_SET, element))
          ? specialized
          : null;
    }

    private JMethodSig select(List<JMethodSig> candidates, List<Argument> arguments) {
      if (candidates.size() > MAX_MEMBERS || candidates.stream().anyMatch(this::unsupported)) {
        return null;
      }
      var applicable = this.applicable(candidates, arguments, false);
      if (applicable.isEmpty()) {
        applicable = this.applicable(candidates, arguments, true);
      }
      return applicable.size() == 1 ? applicable.getFirst() : null;
    }

    private boolean generatedQualifier(InvocationNode invocation) {
      return invocation instanceof ASTMethodCall call
          && call.getQualifier() instanceof InvocationNode qualifier
          && this.generatedRepairs.contains(qualifier);
    }

    private boolean generatedPrefix(InvocationNode invocation) {
      return !(invocation instanceof ASTMethodCall call)
          || !(call.getQualifier() instanceof InvocationNode qualifier)
          || !qualifier.getOverloadSelectionInfo().isFailed()
          || this.generatedRepairs.contains(qualifier);
    }

    private boolean hasSourceMethods(JClassType receiver, InvocationNode invocation) {
      var owner = this.source(receiver.getSymbol());
      return owner != null
          && owner
              .getDeclarations(ASTMethodDeclaration.class)
              .any(method -> method.getName().equals(invocation.getMethodName()));
    }

    private JMethodSig sourceBinding(
        JClassType receiver,
        InvocationNode invocation,
        List<JMethodSig> compiled,
        List<Argument> arguments) {
      var owner = this.source(receiver.getSymbol());
      var written =
          owner
              .getDeclarations(ASTMethodDeclaration.class)
              .filter(method -> method.getName().equals(invocation.getMethodName()))
              .toList(ASTMethodDeclaration::getGenericSignature);
      List<JMethodSig> candidates = new ArrayList<>();
      written.stream()
          .filter(method -> method.isVarargs() || method.getArity() == arguments.size())
          .forEach(candidates::add);
      // Matching binary declarations are replaced, not used to supply the body, visibility, or
      // generic contract.
      compiled.stream()
          .filter(binary -> written.stream().noneMatch(current -> sameParameters(current, binary)))
          .forEach(candidates::add);
      var selected = this.select(candidates, arguments);
      return selected != null
              && written.contains(selected)
              && this.accessible(selected, invocation)
              && (selected.getReturnType().isVoid()
                  || this.project(selected.getReturnType(), true, 0) != null)
              && this.resources.verified(receiver.getSymbol()).isPresent()
          ? selected
          : null;
    }

    private JTypeMirror receiver(InvocationNode invocation, int depth) {
      return switch (invocation) {
        case ASTConstructorCall call
            when !call.isAnonymousClass() && call.getQualifier() == null -> {
          yield call.getTypeNode().getTypeMirror();
        }
        case ASTExplicitConstructorInvocation call when !call.isQualified() ->
            call.isSuper()
                ? call.getEnclosingType().getTypeMirror().getSuperClass()
                : call.getEnclosingType().getTypeMirror();
        case ASTMethodCall call ->
            call.getQualifier() == null
                ? call.getEnclosingType().getTypeMirror()
                : this.expression(call.getQualifier(), depth + 1);
        default -> null;
      };
    }

    private JTypeMirror expression(ASTExpression expression, int depth) {
      if (depth >= MAX_DEPTH
          || expression instanceof ASTLambdaExpression
          || expression instanceof ASTMethodReference) {
        return null;
      }
      if (expression instanceof InvocationNode call && call.getOverloadSelectionInfo().isFailed()) {
        var resolved = this.resolve(call, depth + 1);
        return resolved == null ? null : resolved.getReturnType();
      }
      return this.project(expression.getTypeMirror(), false, 0);
    }

    private JTypeMirror project(JTypeMirror type, boolean typeArgument, int depth) {
      if (type == null || depth >= MAX_DEPTH) {
        return null;
      }
      if (type == type.getTypeSystem().NULL_TYPE) {
        return this.types.NULL_TYPE;
      }
      if (type instanceof JPrimitiveType primitive) {
        return this.types.getPrimitive(primitive.getKind());
      }
      if (type instanceof JArrayType array) {
        var component = this.project(array.getComponentType(), typeArgument, depth + 1);
        return component == null ? null : this.types.arrayType(component);
      }
      if (type instanceof JTypeVar variable && !typeArgument) {
        return this.project(variable.getErasure(), false, depth + 1);
      }
      if (type instanceof JWildcardType wildcard && wildcard.isUnbounded()) {
        return this.types.UNBOUNDED_WILD;
      }
      if (!(type instanceof JClassType original) || original.getSymbol().isUnresolved()) {
        return null;
      }
      var symbol = this.types.getClassSymbol(original.getSymbol().getBinaryName());
      this.rememberSource(original);
      if (symbol == null || symbol.isUnresolved() || !this.sourceHierarchyMatches(symbol, depth)) {
        return null;
      }
      List<JTypeMirror> arguments = new ArrayList<>();
      for (var argument : original.getTypeArgs()) {
        var projected = this.project(argument, true, depth + 1);
        if (projected == null) {
          return null;
        }
        arguments.add(projected);
      }
      return arguments.isEmpty()
          ? this.types.rawType(symbol)
          : this.types.parameterise(symbol, arguments);
    }

    private List<JMethodSig> applicable(
        List<JMethodSig> candidates, List<Argument> arguments, boolean boxing) {
      return candidates.stream().filter(method -> accepts(method, arguments, boxing)).toList();
    }

    private boolean unsupported(JMethodSig method) {
      return method.isVarargs()
          || method.isGeneric()
          || method.getFormalParameters().stream().anyMatch(type -> project(type, true, 0) == null);
    }

    private boolean accepts(JMethodSig method, List<Argument> arguments, boolean boxing) {
      for (var index = 0; index < arguments.size(); index++) {
        var formal = this.project(method.getFormalParameters().get(index), true, 0);
        var argument = arguments.get(index);
        if (formal == null
            || !(conversion(argument.type(), formal, boxing)
                || this.emptyFactory(argument.expression(), formal))) {
          return false;
        }
      }
      return true;
    }

    private boolean emptyFactory(ASTExpression expression, JTypeMirror formal) {
      if (!(expression instanceof ASTMethodCall call)
          || !call.getArguments().isEmpty()
          || call.getExplicitTypeArguments() != null
          || call.getOverloadSelectionInfo().isFailed()
          || !(formal instanceof JClassType target)
          || !target.isParameterizedType()) {
        return false;
      }
      var method = call.getMethodType();
      var owner = method.getSymbol().getEnclosingClass();
      var expectedName =
          switch (owner.getBinaryName()) {
            case "java.util.Optional" -> "empty";
            case "java.util.List", "java.util.Set", "java.util.Map" -> "of";
            default -> "";
          };
      return !owner.isUnresolved()
          && owner.tryGetNode() == null
          && this.source(owner) == null
          && !method.getSymbol().isUnresolved()
          && method.getSymbol().tryGetNode() == null
          && exactFactoryQualifier(call, owner)
          && method.isStatic()
          && !method.isVarargs()
          && method.getArity() == 0
          && method.getName().equals(expectedName)
          && owner.getBinaryName().equals(target.getSymbol().getBinaryName())
          && method.getReturnType() instanceof JClassType result
          && owner.getBinaryName().equals(result.getSymbol().getBinaryName())
          && this.resources.proves(method, false);
    }

    private void rememberSource(JTypeMirror type) {
      if (type instanceof JClassType owner && owner.getSymbol().tryGetNode() != null) {
        var node = owner.getSymbol().tryGetNode();
        if (!this.sources.containsKey(node.getBinaryName())) {
          this.registerSources(node.getRoot());
        }
      }
    }

    private ASTTypeDeclaration source(JClassSymbol symbol) {
      return this.sources.get(symbol.getBinaryName());
    }

    private boolean sourceHierarchyMatches(JClassSymbol compiled, int depth) {
      var live = this.source(compiled);
      if (live == null) {
        return true;
      }
      var original = live.getSymbol();
      var compiledType = (JClassType) this.types.rawType(compiled);
      if (depth >= MAX_DEPTH || original.isGeneric() || compiled.isGeneric()) {
        return false;
      }
      var sourceSuperclass = typeShape(live.getTypeMirror().getSuperClass(), 0);
      var compiledSuperclass = typeShape(compiledType.getSuperClass(), 0);
      if (!sourceSuperclass.equals(compiledSuperclass)
          || !live.getTypeMirror().getSuperInterfaces().stream()
              .map(type -> typeShape(type, 0))
              .sorted()
              .toList()
              .equals(
                  compiledType.getSuperInterfaces().stream()
                      .map(type -> typeShape(type, 0))
                      .sorted()
                      .toList())) {
        return false;
      }
      return (compiled.getSuperclass() == null
              || this.sourceHierarchyMatches(compiled.getSuperclass(), depth + 1))
          && compiled.getSuperInterfaces().stream()
              .allMatch(parent -> sourceHierarchyMatches(parent, depth + 1));
    }

    private boolean sourceAllows(
        JMethodSig selected, InvocationNode invocation, JClassType receiver) {
      // Check current receiver declarations too: a newly written override may be absent from old
      // class resources.
      return this.noWrittenCompetitor(receiver.getSymbol(), selected, invocation, 0);
    }

    private boolean noWrittenCompetitor(
        JClassSymbol owner, JMethodSig selected, InvocationNode invocation, int depth) {
      if (owner == null) {
        return true;
      }
      if (depth >= MAX_DEPTH || !noWrittenCompetitor(this.source(owner), selected, invocation)) {
        return false;
      }
      return selected.isConstructor()
          || this.noWrittenCompetitor(owner.getSuperclass(), selected, invocation, depth + 1)
              && owner.getSuperInterfaces().stream()
                  .allMatch(parent -> noWrittenCompetitor(parent, selected, invocation, depth + 1));
    }

    private boolean accessible(JMethodSig method, InvocationNode invocation) {
      if (invocation instanceof ASTMethodCall call
          && !method.isStatic()
          && (call.getQualifier() instanceof ASTTypeExpression
              || call.getQualifier() == null && staticContext(call))) {
        return false;
      }
      var owner = method.getSymbol().getEnclosingClass();
      var caller = invocation.getEnclosingType().getSymbol();
      if (!this.classAccessible(owner, caller)) {
        return false;
      }
      if (Modifier.isPublic(method.getModifiers())) {
        return true;
      }
      if (Modifier.isPrivate(method.getModifiers())) {
        return owner.getNestRoot().getBinaryName().equals(caller.getNestRoot().getBinaryName());
      }
      // Protected access across packages needs a receiver/subclass proof, deliberately not inferred
      // here.
      return owner.getPackageName().equals(caller.getPackageName());
    }

    private boolean classAccessible(JClassSymbol owner, JClassSymbol caller) {
      for (var depth = 0;
          owner != null && depth < MAX_DEPTH;
          depth++, owner = owner.getEnclosingClass()) {
        var live = this.source(owner);
        var declaration = live == null ? owner : live.getSymbol();
        if (!Modifier.isPublic(declaration.getModifiers())
            && !declaration
                .getNestRoot()
                .getBinaryName()
                .equals(caller.getNestRoot().getBinaryName())
            && (Modifier.isPrivate(declaration.getModifiers())
                || !declaration.getPackageName().equals(caller.getPackageName()))) {
          return false;
        }
      }
      return owner == null;
    }

    private record Argument(ASTExpression expression, JTypeMirror type) {}
  }

  /**
   * Both PMD and metadata verification read the same bytes, even from a mutable resource loader.
   */
  private static final class Resources extends ClassLoader {
    private final Map<String, Optional<byte[]>> bytes = new HashMap<>();
    private final Map<String, Optional<ClassModel>> models = new HashMap<>();

    private Resources(ClassLoader loader) {
      super(loader);
    }

    private static boolean generated(MethodModel method) {
      return method
              .findAttribute(Attributes.runtimeInvisibleAnnotations())
              .map(
                  attribute ->
                      attribute.annotations().stream()
                          .anyMatch(
                              annotation ->
                                  annotation.className().equalsString("Llombok/Generated;")))
              .orElse(false)
          || method
              .findAttribute(Attributes.runtimeVisibleAnnotations())
              .map(
                  attribute ->
                      attribute.annotations().stream()
                          .anyMatch(
                              annotation ->
                                  annotation.className().equalsString("Llombok/Generated;")))
              .orElse(false);
    }

    private static String descriptor(JMethodSig method) {
      var erased = method.getSymbol().getGenericSignature().getErasure();
      var result = new StringBuilder("(");
      erased.getFormalParameters().forEach(parameter -> result.append(descriptor(parameter)));
      return result
          .append(')')
          .append(method.isConstructor() ? "V" : descriptor(erased.getReturnType()))
          .toString();
    }

    private static String descriptor(JTypeMirror type) {
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
      return type.getSymbol() instanceof JClassSymbol symbol && !symbol.isUnresolved()
          ? "L" + symbol.getBinaryName().replace('.', '/') + ";"
          : "?";
    }

    @Override
    public InputStream getResourceAsStream(String name) {
      return this.bytes
          .computeIfAbsent(name, this::read)
          .map(ByteArrayInputStream::new)
          .orElse(null);
    }

    private Optional<byte[]> read(String name) {
      try (var input = this.getParent().getResourceAsStream(name)) {
        if (input == null) {
          return Optional.empty();
        }
        var result = input.readNBytes(MAX_CLASS_BYTES + 1);
        if (result.length > MAX_CLASS_BYTES) {
          throw new IllegalArgumentException("Class resource exceeds binding limit: " + name);
        }
        return Optional.of(result);
      } catch (IOException exception) {
        throw new UncheckedIOException(exception);
      }
    }

    private boolean proves(JMethodSig signature, boolean generatedRequired) {
      var evidence = this.verified(signature.getSymbol().getEnclosingClass());
      if (evidence.isEmpty()) {
        return false;
      }
      var matches =
          evidence.orElseThrow().methods().stream()
              .filter(
                  method ->
                      method
                          .methodName()
                          .equalsString(signature.isConstructor() ? "<init>" : signature.getName()))
              .filter(method -> method.methodType().equalsString(descriptor(signature)))
              .toList();
      return matches.size() == 1
          && matches.getFirst().flags().flagsMask() == signature.getModifiers()
          && (!generatedRequired || generated(matches.getFirst()));
    }

    private Optional<ClassModel> verified(JClassSymbol symbol) {
      return this.models.computeIfAbsent(symbol.getBinaryName().replace('.', '/'), this::verify);
    }

    private Optional<ClassModel> verify(String owner) {
      var data = this.bytes.computeIfAbsent(owner + ".class", this::read);
      if (data.isEmpty()) {
        return Optional.empty();
      }
      var parser =
          ClassFile.of(
              ClassFile.ClassHierarchyResolverOption.of(
                  ClassHierarchyResolver.ofResourceParsing(this)));
      var model = parser.parse(data.orElseThrow());
      if (!model.thisClass().asInternalName().equals(owner) || !parser.verify(model).isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(model);
    }
  }
}
