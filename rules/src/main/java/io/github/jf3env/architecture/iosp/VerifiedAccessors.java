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
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.sourceforge.pmd.lang.java.ast.ASTBlock;
import net.sourceforge.pmd.lang.java.ast.ASTExpression;
import net.sourceforge.pmd.lang.java.ast.ASTFieldAccess;
import net.sourceforge.pmd.lang.java.ast.ASTMethodCall;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTReturnStatement;
import net.sourceforge.pmd.lang.java.ast.ASTThisExpression;
import net.sourceforge.pmd.lang.java.ast.ASTTypeExpression;
import net.sourceforge.pmd.lang.java.ast.ASTVariableAccess;
import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.symbols.JFieldSymbol;
import net.sourceforge.pmd.lang.java.symbols.JMethodSymbol;
import net.sourceforge.pmd.lang.java.types.JArrayType;
import net.sourceforge.pmd.lang.java.types.JMethodSig;
import net.sourceforge.pmd.lang.java.types.JPrimitiveType;
import net.sourceforge.pmd.lang.java.types.JTypeMirror;

/**
 * Conservative, name-independent proof of instance field accessors for the typed IOSP gate.
 *
 * <p>Only non-overridable, zero-argument methods are eligible. Source takes precedence over class
 * resources. A proof consists of one field-read chain, optionally delegated through other proven
 * accessors or the exact mechanical wrappers below. Static fields/methods, implicit primitive
 * conversions, arbitrary library calls and unproven dispatch are deliberately not exempted.
 * Intrinsic array cloning is accepted only on a proven array read; a bytecode cast of its result
 * must match that exact array type. Casting an arbitrary reference does not establish array
 * evidence.
 *
 * <p>Each request has its own bounded traversal and resource cache: no source AST, negative result
 * or classpath evidence survives a request. Resources are parsed, never loaded or initialized.
 * Missing evidence returns false; unreadable or malformed evidence throws an unchecked exception.
 */
public final class VerifiedAccessors {
  private static final int MAX_DEPTH = 32;
  private static final int MAX_STEPS = 256;
  private static final int MAX_CLASS_BYTES = 4 * 1024 * 1024;
  private static final Set<MethodKey> WRAPPERS =
      Set.of(
          new MethodKey(
              "java/util/Optional", "ofNullable", "(Ljava/lang/Object;)Ljava/util/Optional;"),
          new MethodKey("java/util/Optional", "of", "(Ljava/lang/Object;)Ljava/util/Optional;"),
          new MethodKey("java/util/List", "copyOf", "(Ljava/util/Collection;)Ljava/util/List;"),
          new MethodKey("java/util/Set", "copyOf", "(Ljava/util/Collection;)Ljava/util/Set;"),
          new MethodKey("java/util/Map", "copyOf", "(Ljava/util/Map;)Ljava/util/Map;"),
          new MethodKey(
              "java/util/Collections", "unmodifiableList", "(Ljava/util/List;)Ljava/util/List;"),
          new MethodKey(
              "java/util/Collections", "unmodifiableSet", "(Ljava/util/Set;)Ljava/util/Set;"),
          new MethodKey(
              "java/util/Collections", "unmodifiableMap", "(Ljava/util/Map;)Ljava/util/Map;"));

  private final ClassLoader loader;

  public VerifiedAccessors(ClassLoader loader) {
    this.loader = loader;
  }

  private static boolean eligible(int modifiers, boolean finalOwner) {
    var forbidden = Modifier.STATIC | Modifier.ABSTRACT | Modifier.NATIVE | Modifier.SYNCHRONIZED;
    return (modifiers & forbidden) == 0
        && (finalOwner || Modifier.isFinal(modifiers) || Modifier.isPrivate(modifiers));
  }

  /** Returns true only when the resolved declaration and every delegated call have a proof. */
  public boolean isAccessor(JMethodSig signature) {
    return new Proof(this.loader).method(signature);
  }

  private record MethodKey(String owner, String name, String descriptor) {
    static MethodKey of(JMethodSymbol symbol) {
      JMethodSig erased = symbol.getGenericSignature().getErasure();
      var descriptor = new StringBuilder("(");
      erased.getFormalParameters().forEach(type -> descriptor.append(descriptor(type)));
      descriptor.append(')').append(descriptor(erased.getReturnType()));
      return new MethodKey(
          symbol.getEnclosingClass().getBinaryName().replace('.', '/'),
          symbol.getSimpleName(),
          descriptor.toString());
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
          default -> throw new IllegalArgumentException("Unknown primitive: " + primitive);
        };
      }
      if (type.isVoid()) {
        return "V";
      }
      if (type.getSymbol() instanceof JClassSymbol owner && !owner.isUnresolved()) {
        return "L" + owner.getBinaryName().replace('.', '/') + ";";
      }
      return "?"; // An unresolved signature can never match classfile evidence or a wrapper.
    }
  }

  private static final class Proof {
    private final ClassLoader loader;
    private final Set<Object> active = new HashSet<>();
    private final Map<String, Optional<ClassModel>> classes = new HashMap<>();
    private int remaining = MAX_STEPS;

    private Proof(ClassLoader loader) {
      this.loader = loader;
    }

    private boolean step() {
      return this.remaining-- > 0;
    }

    private boolean method(JMethodSig signature) {
      if (!(signature.getSymbol() instanceof JMethodSymbol symbol)
          || symbol.isUnresolved()
          || symbol.getEnclosingClass().isArray()
          || symbol.getArity() != 0
          || signature.getReturnType().isVoid()) {
        return false;
      }
      ASTMethodDeclaration source = symbol.tryGetNode();
      if (source == null) {
        return this.bytecode(MethodKey.of(symbol));
      }
      if (!eligible(symbol.getModifiers(), symbol.getEnclosingClass().isFinal())
          || !this.enter(symbol)) {
        return false;
      }
      try {
        return new SourceProof(this).body(source);
      } finally {
        this.active.remove(symbol);
      }
    }

    private boolean enter(Object key) {
      return this.step() && this.active.size() < MAX_DEPTH && this.active.add(key);
    }

    private boolean bytecode(MethodKey key) {
      if (!this.enter(key)) {
        return false;
      }
      try {
        return this.classModel(key.owner()).map(owner -> this.bytecode(owner, key)).orElse(false);
      } finally {
        this.active.remove(key);
      }
    }

    private boolean bytecode(ClassModel owner, MethodKey key) {
      return owner.methods().stream()
          .filter(method -> method.methodName().equalsString(key.name()))
          .filter(method -> method.methodType().equalsString(key.descriptor()))
          .filter(
              method -> eligible(method.flags().flagsMask(), owner.flags().has(AccessFlag.FINAL)))
          .filter(method -> method.methodTypeSymbol().parameterCount() == 0)
          .anyMatch(method -> new BytecodeProof(this, owner, method).body());
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
        throw new UncheckedIOException("Cannot read accessor evidence: " + resource, exception);
      }
    }
  }

  private static final class SourceProof {
    private final Proof proof;

    private SourceProof(Proof proof) {
      this.proof = proof;
    }

    private static boolean sameStorage(JTypeMirror expression, JTypeMirror result) {
      if (expression.isPrimitive() || result.isPrimitive()) {
        return expression.equals(result);
      }
      return !expression.equals(expression.getTypeSystem().UNKNOWN)
          && !expression.equals(expression.getTypeSystem().ERROR);
    }

    private static boolean field(Object symbol) {
      return symbol instanceof JFieldSymbol field
          && !field.isUnresolved()
          && !field.getEnclosingClass().isArray()
          && !Modifier.isStatic(field.getModifiers());
    }

    private boolean body(ASTMethodDeclaration method) {
      ASTBlock body = method.getBody();
      if (body == null
          || body.size() != 1
          || !(body.get(0) instanceof ASTReturnStatement returned)) {
        return false;
      }
      ASTExpression expression = returned.getExpr();
      return expression != null
          && sameStorage(expression.getTypeMirror(), method.getGenericSignature().getReturnType())
          && this.value(expression);
    }

    private boolean value(ASTExpression expression) {
      if (!this.proof.step()) {
        return false;
      }
      return switch (expression) {
        case ASTVariableAccess access -> field(access.getReferencedSym());
        case ASTFieldAccess access ->
            field(access.getReferencedSym()) && this.receiver(access.getQualifier());
        case ASTMethodCall call -> this.call(call);
        default -> false;
      };
    }

    private boolean receiver(ASTExpression expression) {
      if (expression == null) {
        return true; // Implicit this on an instance invocation.
      }
      if (expression instanceof ASTThisExpression self) {
        return self.getQualifier() == null;
      }
      return this.value(expression);
    }

    private boolean call(ASTMethodCall call) {
      if (call.getOverloadSelectionInfo().isFailed()
          || !(call.getMethodType().getSymbol() instanceof JMethodSymbol symbol)) {
        return false;
      }
      if (symbol.getEnclosingClass().isArray()) {
        return this.arrayClone(call);
      }
      if (symbol.isStatic()) {
        return this.wrapper(call, symbol);
      }
      return call.getArguments().isEmpty()
          && this.receiver(call.getQualifier())
          && this.proof.method(call.getMethodType());
    }

    private boolean arrayClone(ASTMethodCall call) {
      ASTExpression qualifier = call.getQualifier();
      JMethodSig signature = call.getMethodType();
      return qualifier != null
          && qualifier.getTypeMirror() instanceof JArrayType array
          && signature.getName().equals("clone")
          && !signature.isStatic()
          && signature.getArity() == 0
          && call.getArguments().isEmpty()
          && array.equals(signature.getDeclaringType())
          && array.equals(signature.getReturnType())
          && this.value(qualifier);
    }

    private boolean wrapper(ASTMethodCall call, JMethodSymbol symbol) {
      ASTExpression qualifier = call.getQualifier();
      if (qualifier != null && !(qualifier instanceof ASTTypeExpression)) {
        return false; // Even a static invocation can evaluate a side-effecting receiver.
      }
      return WRAPPERS.contains(MethodKey.of(symbol))
          && call.getArguments().size() == 1
          && !call.getArguments().get(0).getTypeMirror().isPrimitive()
          && this.value(call.getArguments().get(0));
    }
  }

  /** A single live operand suffices: every accepted instruction extends one unary read chain. */
  private static final class BytecodeProof {
    private final Proof proof;
    private final ClassModel owner;
    private final MethodModel method;
    private ClassDesc operand;
    private ClassDesc clonedArray;
    private boolean provenArray;
    private boolean fieldRead;
    private boolean returned;

    private BytecodeProof(Proof proof, ClassModel owner, MethodModel method) {
      this.proof = proof;
      this.owner = owner;
      this.method = method;
    }

    private boolean body() {
      var code = this.method.code();
      if (code.isEmpty() || !code.orElseThrow().exceptionHandlers().isEmpty()) {
        return false;
      }
      for (var element : code.orElseThrow()) {
        if (element instanceof Instruction instruction && !this.instruction(instruction)) {
          return false;
        }
      }
      return this.returned;
    }

    private boolean instruction(Instruction instruction) {
      if (this.returned || !this.proof.step()) {
        return false;
      }
      return switch (instruction) {
        case LoadInstruction load -> this.load(load);
        case FieldInstruction field -> this.field(field);
        case InvokeInstruction invoke -> this.invoke(invoke);
        case TypeCheckInstruction check -> this.cast(check);
        case ReturnInstruction exit -> this.exit(exit);
        default -> false;
      };
    }

    private boolean load(LoadInstruction load) {
      if (this.operand != null || load.slot() != 0 || load.typeKind() != TypeKind.REFERENCE) {
        return false;
      }
      this.operand = this.owner.thisClass().asSymbol();
      return true;
    }

    private boolean reference() {
      return this.operand != null && !this.operand.isPrimitive();
    }

    private boolean field(FieldInstruction field) {
      if (field.opcode() != Opcode.GETFIELD || !this.reference()) {
        return false;
      }
      this.operand = field.typeSymbol();
      this.provenArray = this.operand.isArray();
      this.clonedArray = null;
      this.fieldRead = true;
      return true;
    }

    private boolean invoke(InvokeInstruction invoke) {
      var key =
          new MethodKey(
              invoke.owner().asInternalName(),
              invoke.name().stringValue(),
              invoke.type().stringValue());
      if (!this.reference()) {
        return false;
      }
      if (invoke.owner().asSymbol().isArray()) {
        return this.arrayClone(invoke);
      }
      if (invoke.opcode() == Opcode.INVOKESTATIC) {
        if (!this.fieldRead || !WRAPPERS.contains(key)) {
          return false;
        }
      } else if (invoke.typeSymbol().parameterCount() != 0 || !this.proof.bytecode(key)) {
        return false;
      }
      this.operand = invoke.typeSymbol().returnType();
      this.provenArray = this.operand.isArray();
      this.clonedArray = null;
      this.fieldRead = true;
      return true;
    }

    private boolean arrayClone(InvokeInstruction invoke) {
      if (!this.fieldRead
          || !this.provenArray
          || invoke.opcode() != Opcode.INVOKEVIRTUAL
          || invoke.isInterface()
          || !invoke.owner().asSymbol().equals(this.operand)
          || !invoke.name().equalsString("clone")
          || !invoke.type().equalsString("()Ljava/lang/Object;")) {
        return false;
      }
      this.clonedArray = this.operand;
      this.operand = invoke.typeSymbol().returnType();
      this.provenArray = false;
      return true;
    }

    private boolean cast(TypeCheckInstruction check) {
      if (check.opcode() != Opcode.CHECKCAST || !this.reference() || !this.fieldRead) {
        return false;
      }
      var target = check.type().asSymbol();
      if (this.clonedArray != null && !this.clonedArray.equals(target)) {
        return false;
      }
      this.provenArray =
          this.clonedArray != null || (this.provenArray && this.operand.equals(target));
      this.operand = target;
      return true;
    }

    private boolean exit(ReturnInstruction exit) {
      if (!this.fieldRead || this.operand == null || exit.typeKind() == TypeKind.VOID) {
        return false;
      }
      var result = this.method.methodTypeSymbol().returnType();
      // IRETURN shares one stack kind for byte/short/char/boolean/int, not one Java storage type.
      if ((this.operand.isPrimitive() || result.isPrimitive()) && !this.operand.equals(result)) {
        return false;
      }
      this.returned = exit.typeKind() == TypeKind.from(this.operand).asLoadable();
      return this.returned;
    }
  }
}
