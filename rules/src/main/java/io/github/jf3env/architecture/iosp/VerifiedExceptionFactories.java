package io.github.jf3env.architecture.iosp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.stream.Collectors;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorCall;
import net.sourceforge.pmd.lang.java.ast.ASTLiteral;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTReturnStatement;
import net.sourceforge.pmd.lang.java.ast.ASTVariableAccess;
import net.sourceforge.pmd.lang.java.symbols.JVariableSymbol;
import net.sourceforge.pmd.lang.java.types.JArrayType;
import net.sourceforge.pmd.lang.java.types.JClassType;
import net.sourceforge.pmd.lang.java.types.JMethodSig;
import net.sourceforge.pmd.lang.java.types.JPrimitiveType;
import net.sourceforge.pmd.lang.java.types.JTypeMirror;
import net.sourceforge.pmd.lang.java.types.TypeTestUtil;

/**
 * Only a static method that directly returns a new instance of its own Exception subtype is
 * error-construction plumbing.
 */
public final class VerifiedExceptionFactories {
  private final ClassLoader loader;

  public VerifiedExceptionFactories(ClassLoader loader) {
    this.loader = loader;
  }

  public static boolean isFactory(ASTMethodDeclaration method) {
    if (!signature(method.getGenericSignature())
        || method.getBody() == null
        || method.getBody().getNumChildren() != 1
        || !(method.getBody().getChild(0) instanceof ASTReturnStatement statement)
        || !(statement.getExpr() instanceof ASTConstructorCall creation)
        || creation.isAnonymousClass()
        || !creation.getTypeMirror().equals(method.getGenericSignature().getDeclaringType())) {
      return false;
    }
    var parameters = new HashSet<JVariableSymbol>();
    method
        .getFormalParameters()
        .forEach(parameter -> parameters.add(parameter.getVarId().getSymbol()));
    return creation
        .getArguments()
        .children()
        .all(
            argument ->
                argument instanceof ASTLiteral
                    || argument instanceof ASTVariableAccess variable
                        && parameters.contains(variable.getReferencedSym()));
  }

  public boolean isFactory(JMethodSig method) {
    if (!signature(method)) {
      return false;
    }
    var source = method.getSymbol().tryGetNode();
    if (source instanceof ASTMethodDeclaration declaration) {
      return isFactory(declaration);
    }
    var owner = method.getSymbol().getEnclosingClass().getBinaryName().replace('.', '/');
    var descriptor =
        method.getFormalParameters().stream()
                .map(VerifiedExceptionFactories::descriptor)
                .collect(Collectors.joining("", "(", ")"))
            + descriptor(method.getReturnType());
    try (var input = this.loader.getResourceAsStream(owner + ".class")) {
      if (input == null) {
        return false;
      }
      var model = ClassFile.of().parse(input.readAllBytes());
      if (!model.thisClass().asInternalName().equals(owner)) {
        return false;
      }
      return model.methods().stream()
          .filter(
              candidate ->
                  candidate.methodName().stringValue().equals(method.getName())
                      && candidate.methodType().stringValue().equals(descriptor))
          .anyMatch(
              candidate ->
                  candidate
                      .code()
                      .map(
                          code -> {
                            if (!code.exceptionHandlers().isEmpty()) {
                              return false;
                            }
                            var instructions =
                                code.elementStream()
                                    .filter(Instruction.class::isInstance)
                                    .map(Instruction.class::cast)
                                    .toList();
                            var count = instructions.size();
                            return count >= 4
                                && instructions.getFirst()
                                    instanceof NewObjectInstruction allocation
                                && allocation.className().asInternalName().equals(owner)
                                && instructions.get(1).opcode() == Opcode.DUP
                                && instructions.subList(2, count - 2).stream()
                                    .allMatch(
                                        instruction ->
                                            instruction instanceof LoadInstruction
                                                || instruction instanceof ConstantInstruction)
                                && instructions.get(count - 2)
                                    instanceof InvokeInstruction invocation
                                && invocation.opcode() == Opcode.INVOKESPECIAL
                                && invocation.owner().asInternalName().equals(owner)
                                && invocation.name().stringValue().equals("<init>")
                                && instructions.getLast().opcode() == Opcode.ARETURN;
                          })
                      .orElse(false));
    } catch (IOException exception) {
      throw new UncheckedIOException("Cannot inspect exception factory " + owner, exception);
    }
  }

  private static boolean signature(JMethodSig method) {
    var modifiers = method.getSymbol().getModifiers();
    return Modifier.isStatic(modifiers)
        && (modifiers & (Modifier.SYNCHRONIZED | Modifier.NATIVE | Modifier.ABSTRACT)) == 0
        && TypeTestUtil.isA(Exception.class, method.getDeclaringType())
        && method.getReturnType().equals(method.getDeclaringType());
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
    return type instanceof JClassType clazz
        ? "L" + clazz.getSymbol().getBinaryName().replace('.', '/') + ";"
        : "?";
  }
}
