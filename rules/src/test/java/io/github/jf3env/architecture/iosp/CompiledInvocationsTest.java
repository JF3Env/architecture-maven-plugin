package io.github.jf3env.architecture.iosp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassSignature;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import net.sourceforge.pmd.lang.LanguageProcessorRegistry;
import net.sourceforge.pmd.lang.ast.Parser.ParserTask;
import net.sourceforge.pmd.lang.ast.SemanticErrorReporter;
import net.sourceforge.pmd.lang.document.TextDocument;
import net.sourceforge.pmd.lang.java.ast.ASTCompilationUnit;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorCall;
import net.sourceforge.pmd.lang.java.ast.ASTExplicitConstructorInvocation;
import net.sourceforge.pmd.lang.java.ast.ASTMethodCall;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.ast.InvocationNode;
import net.sourceforge.pmd.lang.java.internal.JavaLanguageProcessor;
import net.sourceforge.pmd.lang.java.internal.JavaLanguageProperties;
import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.types.JClassType;
import net.sourceforge.pmd.lang.java.types.JMethodSig;
import net.sourceforge.pmd.lang.java.types.TypeSystem;
import org.junit.jupiter.api.Test;

/**
 * Real javac/Lombok class resources and independent, unprocessed PMD source trees; fixtures stay
 * entirely in memory.
 */
class CompiledInvocationsTest {
  private static final String PREFIX = "package fixtures; ";
  private static final String GENERATED = "@lombok.Generated ";

  private static List<String> parameterNames(JMethodSig method) {
    return method.getFormalParameters().stream()
        .map(type -> ((JClassSymbol) type.getSymbol()).getBinaryName())
        .toList();
  }

  private static JMethodSig resolved(ClassLoader loader, InvocationNode invocation) {
    assertNotNull(invocation);
    assertTrue(
        invocation.getOverloadSelectionInfo().isFailed(),
        "fixture must reproduce PMD overload failure");
    return new CompiledInvocations(loader)
        .resolve(invocation)
        .orElseThrow(
            () ->
                new AssertionError(
                    "No compiled binding: " + invocation + " at " + invocation.getBeginLine()));
  }

  private static ASTMethodCall methodCall(String members, ClassLoader loader) throws Exception {
    return parse(PREFIX + "public class Data { " + members + " }", loader)
        .descendants(ASTMethodCall.class)
        .first();
  }

  private static ASTMethodCall namedCall(ASTCompilationUnit root, String name) {
    return root.descendants(ASTMethodCall.class)
        .crossFindBoundaries(true)
        .first(call -> call.getMethodName().equals(name));
  }

  private static ASTCompilationUnit parse(String source, ClassLoader loader) throws Exception {
    var properties = new JavaLanguageProperties();
    properties.setLanguageVersion("24");
    properties.setClassLoader(loader);
    var processor = new JavaLanguageProcessor(properties);
    try (var registry = LanguageProcessorRegistry.singleton(processor);
        var document = TextDocument.readOnlyString(source, processor.getLanguageVersion())) {
      return (ASTCompilationUnit)
          processor
              .getParser()
              .parse(new ParserTask(document, SemanticErrorReporter.noop(), registry));
    }
  }

  private static ResourceLoader binary(String members) throws IOException {
    return compile(PREFIX + "public class Data { " + members + " }", false);
  }

  private static String enumFixture(String members) {
    return PREFIX
        + "enum Feature { VIEWER, TILE } enum Other { EXPERIMENT } "
        + "@lombok.AllArgsConstructor(access = lombok.AccessLevel.PRIVATE) public final class Data { "
        + "private final java.util.Set<Feature> enabled; "
        + members
        + " }";
  }

  private static ResourceLoader compile(String source, boolean lombok) throws IOException {
    return compile(Map.of("Data", source), lombok);
  }

  private static ResourceLoader compile(Map<String, String> sources, boolean lombok)
      throws IOException {
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "Java 24 JDK required");
    var diagnostics = new DiagnosticCollector<JavaFileObject>();
    Map<String, ByteArrayOutputStream> outputs = new HashMap<>();
    try (var standard = compiler.getStandardFileManager(diagnostics, null, null);
        var files =
            new ForwardingJavaFileManager<>(standard) {
              @Override
              public JavaFileObject getJavaFileForOutput(
                  JavaFileManager.Location location,
                  String name,
                  JavaFileObject.Kind kind,
                  FileObject sibling) {
                var output = new ByteArrayOutputStream();
                outputs.put(name.replace('.', '/') + kind.extension, output);
                return new SimpleJavaFileObject(
                    URI.create("mem:///" + name.replace('.', '/') + kind.extension), kind) {
                  @Override
                  public OutputStream openOutputStream() {
                    return output;
                  }
                };
              }
            }) {
      var options =
          new ArrayList<>(
              List.of("--release", "24", "-classpath", System.getProperty("java.class.path")));
      options.addAll(
          lombok
              ? List.of("-processor", "lombok.launch.AnnotationProcessorHider$AnnotationProcessor")
              : List.of("-proc:none"));
      var inputs =
          sources.entrySet().stream()
              .map(
                  entry ->
                      new SimpleJavaFileObject(
                          URI.create("string:///" + entry.getKey() + ".java"),
                          JavaFileObject.Kind.SOURCE) {
                        @Override
                        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                          return entry.getValue();
                        }
                      })
              .toList();
      assertTrue(
          compiler.getTask(null, files, diagnostics, options, null, inputs).call(),
          diagnostics.getDiagnostics().toString());
    }
    Map<String, byte[]> classes = new HashMap<>();
    outputs.forEach((name, bytes) -> classes.put(name, bytes.toByteArray()));
    return new ResourceLoader(classes);
  }

  @Test
  void resolvesOwnValueConstructorWithTypedClassReturn() throws Exception {
    var source =
        PREFIX
            + """
        @lombok.Value public class Data {
          int count;
          String label;
          static Data copy(int count, String label) { return new Data(count, label); }
        }
        """;
    var loader = compile(source, true);
    var call = parse(source, loader).descendants(ASTConstructorCall.class).first();
    var result = resolved(loader, call);
    assertEquals(List.of("int", "java.lang.String"), parameterNames(result));
    assertEquals(
        "fixtures.Data", ((JClassSymbol) result.getReturnType().getSymbol()).getBinaryName());
    assertEquals(result.getDeclaringType(), result.getReturnType());
    assertNull(result.getSymbol().tryGetNode());
    assertEquals(0, loader.applicationLoads);
  }

  @Test
  void resolvesGeneratedZeroArityAlongsideWrittenOneArity() throws Exception {
    var source =
        PREFIX
            + """
        @lombok.NoArgsConstructor public class Data {
          Data(int count) { }
          static Data copy() { return new Data(); }
        }
        """;
    var loader = compile(source, true);
    var result =
        resolved(loader, parse(source, loader).descendants(ASTConstructorCall.class).first());
    assertEquals(0, result.getArity());
  }

  @Test
  void resolvesExplicitThisToDifferentGeneratedOverload() throws Exception {
    var source =
        PREFIX
            + """
        @lombok.AllArgsConstructor public class Data {
          final int count;
          final String label;
          Data(int count) { this(count, "default"); }
        }
        """;
    var loader = compile(source, true);
    var call = parse(source, loader).descendants(ASTExplicitConstructorInvocation.class).first();
    var result = resolved(loader, call);
    assertTrue(result.isConstructor());
    assertEquals(List.of("int", "java.lang.String"), parameterNames(result));
  }

  @Test
  void derivesExplicitSuperFromTheCurrentSourceSuperclass() throws Exception {
    var source =
        PREFIX
            + """
        @lombok.RequiredArgsConstructor class Parent { final int number; }
        public class Data extends Parent { Data(int number) { super(number); } }
        """;
    var loader = compile(source, true);
    var call = parse(source, loader).descendants(ASTExplicitConstructorInvocation.class).first();
    assertEquals(
        "fixtures.Parent", resolved(loader, call).getSymbol().getEnclosingClass().getBinaryName());
  }

  @Test
  void resolvesArraysNullBoxingWideningAndBoundedTypeParameter() throws Exception {
    var source =
        PREFIX
            + """
        @lombok.AllArgsConstructor(access = lombok.AccessLevel.PRIVATE) public class Data {
          final boolean enabled;
          final int[] samples;
          final Float scale;
          final java.util.List<String> names;
          final java.util.Optional<String> label;
          final Number index;
          final Character initial;
          final double measure;
          static <N extends Number> Data copy(int[] samples, java.util.List<String> names,
                                             java.util.Optional<String> label, N number) {
            return new Data(true, samples.clone(), null, names, label, number, 'x', 3);
          }
        }
        """;
    var loader = compile(source, true);
    var result =
        resolved(loader, parse(source, loader).descendants(ASTConstructorCall.class).first());
    assertEquals(8, result.getArity());
    assertTrue(result.getFormalParameters().get(1).isArray());
    assertEquals(
        "java.lang.Double",
        ((JClassSymbol) result.getFormalParameters().get(7).box().getSymbol()).getBinaryName());
  }

  @Test
  void resolvesTheFeatureFlagsPatternWithoutChangingTheConcreteSetCopyBinding() throws Exception {
    var source =
        enumFixture(
            """
        static Data of(java.util.Collection<Feature> features) {
          return features.isEmpty() ? allDisabled()
              : new Data(java.util.EnumSet.copyOf(java.util.Set.copyOf(features)));
        }
        static Data allDisabled() { return new Data(java.util.EnumSet.noneOf(Feature.class)); }
        """);
    var loader = compile(source, true);
    var root = parse(source, loader);
    var method =
        root.descendants(ASTMethodDeclaration.class)
            .crossFindBoundaries(true)
            .first(declaration -> declaration.getName().equals("of"));
    var copy = namedCall(root, "copyOf");
    var setCopy = (ASTMethodCall) copy.getArguments().get(0);
    assertFalse(setCopy.getOverloadSelectionInfo().isFailed());
    var original = setCopy.getMethodType();
    assertEquals("java.util.Set<fixtures.Feature>", setCopy.getTypeMirror().toString());
    var binding = resolved(loader, copy);
    assertEquals(
        "java.util.Collection<fixtures.Feature>",
        binding.getFormalParameters().getFirst().toString());
    assertEquals("java.util.EnumSet<fixtures.Feature>", binding.getReturnType().toString());
    assertEquals("java.util.EnumSet", binding.getSymbol().getEnclosingClass().getBinaryName());
    assertEquals(1, binding.getSymbol().getGenericSignature().getTypeParameters().size());
    assertSame(
        original, setCopy.getMethodType(), "the successful inner PMD binding is authoritative");
    var failures =
        method
            .descendants(InvocationNode.class)
            .crossFindBoundaries(true)
            .filter(call -> call.getOverloadSelectionInfo().isFailed())
            .toList();
    assertEquals(
        2,
        failures.size(),
        "constructor plus EnumSet.copyOf must reproduce the production failure");
    assertTrue(
        failures.stream()
            .allMatch(call -> new CompiledInvocations(loader).resolve(call).isPresent()));
    assertEquals(0, loader.applicationLoads);
  }

  @Test
  void enumCopySubstitutesConcreteCollectionSubtypes() throws Exception {
    for (String expression :
        List.of(
            "java.util.Set.copyOf(features)",
            "java.util.List.copyOf(features)",
            "new java.util.ArrayList<>(features)",
            "new java.util.HashSet<>(features)")) {
      var source =
          enumFixture(
              "Data copy(java.util.Collection<Feature> features) { return new Data(java.util.EnumSet.copyOf("
                  + expression
                  + ")); }");
      var loader = compile(source, true);
      var copy = namedCall(parse(source, loader), "copyOf");
      var binding = resolved(loader, copy);
      assertEquals(
          "java.util.Collection<fixtures.Feature>",
          binding.getFormalParameters().getFirst().toString(),
          expression);
      assertEquals(
          "java.util.EnumSet<fixtures.Feature>", binding.getReturnType().toString(), expression);
      assertNull(binding.getSymbol().tryGetNode());
    }
  }

  @Test
  void enumCopyKeepsTheTypeOfAConcreteSourceField() throws Exception {
    var source =
        enumFixture(
            """
        static final class Frame {
          final java.util.Collection<Feature> features;
          Frame(java.util.Collection<Feature> features) { this.features = features; }
        }
        static Data of(Frame frame) { return new Data(java.util.EnumSet.copyOf(java.util.Set.copyOf(frame.features))); }
        """);
    var loader = compile(source, true);
    var copy = namedCall(parse(source, loader), "copyOf");
    var result = resolved(loader, copy);
    assertEquals("java.util.EnumSet<fixtures.Feature>", result.getReturnType().toString());
  }

  @Test
  void enumCopyRejectsRawWildcardTypeVariableAndNonEnumElements() throws Exception {
    for (String type :
        List.of(
            "java.util.Collection",
            "java.util.Collection<?>",
            "java.util.Collection<? extends Feature>",
            "java.util.Collection<? super Feature>",
            "java.util.Collection<String>",
            "java.util.Collection<java.lang.Enum>",
            "Object")) {
      var loader = compile(enumFixture(""), true);
      var source =
          enumFixture(
              "Data copy("
                  + type
                  + " features) { return new Data(java.util.EnumSet.copyOf(features)); }");
      var copy = namedCall(parse(source, loader), "copyOf");
      assertTrue(new CompiledInvocations(loader).resolve(copy).isEmpty(), type);
    }
    var loader = compile(enumFixture(""), true);
    var source =
        enumFixture(
            "<E extends Enum<E>> Object copy(java.util.Collection<E> values) { "
                + "return java.util.EnumSet.copyOf(values); }");
    var copy = namedCall(parse(source, loader), "copyOf");
    assertTrue(new CompiledInvocations(loader).resolve(copy).isEmpty());
  }

  @Test
  void aDifferentEnumRemainsDifferentWhenTheOuterConstructorIsChecked() throws Exception {
    var loader = compile(enumFixture(""), true);
    var source =
        enumFixture(
            "Data copy(java.util.Collection<Other> features) { "
                + "return new Data(java.util.EnumSet.copyOf(java.util.Set.copyOf(features))); }");
    var root = parse(source, loader);
    var copy = namedCall(root, "copyOf");
    assertEquals(
        "java.util.EnumSet<fixtures.Other>", resolved(loader, copy).getReturnType().toString());
    var constructor = root.descendants(ASTConstructorCall.class).crossFindBoundaries(true).first();
    assertTrue(constructor.getOverloadSelectionInfo().isFailed());
    assertTrue(
        new CompiledInvocations(loader).resolve(constructor).isEmpty(),
        "EnumSet<Other> is not Set<Feature>");
  }

  @Test
  void anEnumSetArgumentDoesNotTriggerAnInventedMostSpecificChoice() throws Exception {
    var source =
        enumFixture(
            "Data copy(java.util.EnumSet<Feature> features) { "
                + "return new Data(java.util.EnumSet.copyOf(java.util.EnumSet.copyOf(features))); }");
    var loader = compile(source, true);
    var copy = namedCall(parse(source, loader), "copyOf");
    var inner = (ASTMethodCall) copy.getArguments().get(0);
    assertFalse(inner.getOverloadSelectionInfo().isFailed());
    assertEquals("java.util.EnumSet<fixtures.Feature>", inner.getTypeMirror().toString());
    assertTrue(copy.getOverloadSelectionInfo().isFailed());
    assertTrue(
        new CompiledInvocations(loader).resolve(copy).isEmpty(),
        "both closed descriptors are applicable");
  }

  @Test
  void enumCopyDoesNotInferLookalikeOwnersOrOtherGenericMethods() throws Exception {
    var before =
        enumFixture(GENERATED + "public java.util.Collection<Feature> values() { return null; }")
            + "class EnumSet { "
            + GENERATED
            + "public static <E extends Enum<E>> java.util.EnumSet<E> copyOf(java.util.Collection<E> values) { "
            + "return java.util.EnumSet.copyOf(values); } }";
    var loader = compile(before, true);
    var current =
        enumFixture("Object copy() { return EnumSet.copyOf(values()); }") + "class EnumSet {}";
    var lookalike = namedCall(parse(current, loader), "copyOf");
    assertTrue(lookalike.getOverloadSelectionInfo().isFailed());
    assertTrue(new CompiledInvocations(loader).resolve(lookalike).isEmpty());
    var otherSource =
        enumFixture(
            "Object copy(java.util.Collection<Feature> features) { "
                + "return java.util.List.copyOf(features); }");
    var other = namedCall(parse(otherSource, loader), "copyOf");
    assertFalse(other.getOverloadSelectionInfo().isFailed());
    assertTrue(
        new CompiledInvocations(loader).resolve(other).isEmpty(),
        "no generic copyOf name-based exemption");
  }

  @Test
  void enumCopyRejectsAnExplicitIncompatibleTypeArgument() throws Exception {
    var loader = compile(enumFixture(""), true);
    var source =
        enumFixture(
            "Data copy(java.util.Collection<Feature> features) { "
                + "return new Data(java.util.EnumSet.<Other>copyOf(features)); }");
    var copy = namedCall(parse(source, loader), "copyOf");
    assertTrue(copy.getOverloadSelectionInfo().isFailed());
    assertTrue(new CompiledInvocations(loader).resolve(copy).isEmpty());
  }

  @Test
  void enumCopyRefreshesJdkAndElementResourceEvidence() throws Exception {
    var source =
        enumFixture(
            "Data copy(java.util.Collection<Feature> features) { "
                + "return new Data(java.util.EnumSet.copyOf(java.util.Set.copyOf(features))); }");
    var loader = compile(source, true);
    var copy = namedCall(parse(source, loader), "copyOf");
    Map<String, Optional<byte[]>> overrides = new HashMap<>();
    ClassLoader mutable =
        new ClassLoader(loader) {
          @Override
          public InputStream getResourceAsStream(String name) {
            return overrides.containsKey(name)
                ? overrides.get(name).map(ByteArrayInputStream::new).orElse(null)
                : loader.getResourceAsStream(name);
          }
        };
    var proof = new CompiledInvocations(mutable);
    assertTrue(proof.resolve(copy).isPresent());
    for (String resource :
        List.of("java/util/EnumSet.class", "java/lang/Enum.class", "fixtures/Feature.class")) {
      overrides.put(resource, Optional.empty());
      assertTrue(proof.resolve(copy).isEmpty(), resource);
      overrides.put(resource, Optional.of(new byte[] {0, 1, 2}));
      assertTrue(proof.resolve(copy).isEmpty(), resource);
      overrides.clear();
      assertTrue(proof.resolve(copy).isPresent(), resource);
    }
  }

  @Test
  void theEnumFlagAloneCannotProveTheSelfReferentialEnumBound() throws Exception {
    var source =
        PREFIX
            + """
        @lombok.RequiredArgsConstructor public class Data {
          final java.util.Set<Feature> enabled;
          static Data copy(java.util.Collection<Feature> features) {
            return new Data(java.util.EnumSet.copyOf(java.util.Set.copyOf(features)));
          }
        }
        """;
    var loader =
        compile(
            Map.of(
                "Data",
                source,
                "Feature",
                PREFIX + "public enum Feature { VIEWER }",
                "Other",
                PREFIX + "public enum Other { EXPERIMENT }"),
            true);
    var copy = namedCall(parse(source, loader), "copyOf");
    assertEquals(
        "java.util.EnumSet<fixtures.Feature>", resolved(loader, copy).getReturnType().toString());
    var parser =
        ClassFile.of(
            ClassFile.ClassHierarchyResolverOption.of(
                ClassHierarchyResolver.ofResourceParsing(loader)));
    var model = parser.parse(loader.classes.get("fixtures/Feature.class"));
    var otherEnumSignature =
        SignatureAttribute.of(ClassSignature.parseFrom("Ljava/lang/Enum<Lfixtures/Other;>;"));
    var differentBound =
        parser.transformClass(
            model,
            (builder, element) -> {
              if (element instanceof SignatureAttribute) {
                builder.with(otherEnumSignature);
              } else {
                builder.with(element);
              }
            });
    assertTrue(
        parser.verify(differentBound).isEmpty(),
        "generic metadata does not change JVM bytecode validity");
    loader.classes.put("fixtures/Feature.class", differentBound);
    var types = TypeSystem.usingClassLoaderClasspath(loader);
    var feature = (JClassType) types.rawType(types.getClassSymbol("fixtures.Feature"));
    assertTrue(feature.getSymbol().isEnum());
    assertEquals("java.lang.Enum<fixtures.Other>", feature.getSuperClass().toString());
    assertTrue(
        new CompiledInvocations(loader).resolve(copy).isEmpty(),
        "Feature does not satisfy Enum<Feature>");
  }

  @Test
  void matchingJdkOwnerAndDescriptorsCannotReplaceTheGenericDeclarationContract() throws Exception {
    var source =
        enumFixture(
            "Data copy(java.util.Collection<Feature> features) { "
                + "return new Data(java.util.EnumSet.copyOf(java.util.Set.copyOf(features))); }");
    var loader = compile(source, true);
    var copy = namedCall(parse(source, loader), "copyOf");
    var parser = ClassFile.of();
    byte[] original;
    try (var input = loader.getResourceAsStream("java/util/EnumSet.class")) {
      assertNotNull(input);
      original = input.readAllBytes();
    }
    var collectionCopyDescriptor = "(Ljava/util/Collection;)Ljava/util/EnumSet;";
    var objectBoundContract =
        "<E:Ljava/lang/Object;>(Ljava/util/Collection<TE;>;)Ljava/util/EnumSet<TE;>;";
    var objectBoundSignature =
        SignatureAttribute.of(MethodSignature.parseFrom(objectBoundContract));
    var weakened =
        parser.transformClass(
            parser.parse(original),
            (builder, element) -> {
              if (element instanceof java.lang.classfile.MethodModel method
                  && method.methodName().equalsString("copyOf")
                  && method.methodType().equalsString(collectionCopyDescriptor)) {
                builder.transformMethod(
                    method,
                    (target, attribute) -> {
                      if (attribute instanceof SignatureAttribute) {
                        target.with(objectBoundSignature);
                      } else {
                        target.with(attribute);
                      }
                    });
              } else {
                builder.with(element);
              }
            });
    assertTrue(parser.verify(weakened).isEmpty());
    loader.classes.put("java/util/EnumSet.class", weakened);
    assertTrue(
        new CompiledInvocations(loader).resolve(copy).isEmpty(),
        "the Enum<E> bound is required, not presumed");
    loader.classes.remove("java/util/EnumSet.class");
    assertEquals(
        "java.util.EnumSet<fixtures.Feature>", resolved(loader, copy).getReturnType().toString());
  }

  @Test
  void resolvesNestedPropertiesAllocationsWithoutAccessorOrFactoryExemptions() throws Exception {
    var source =
        PREFIX
            + """
        public class Data {
          java.util.Optional<String> user;
          java.util.Optional<String> category;
          Properties getProperties() { return new Properties(new Attribution(user), new Classification(category)); }
          @lombok.RequiredArgsConstructor static final class Properties {
            final Attribution attribution;
            final Classification classification;
          }
          @lombok.RequiredArgsConstructor static final class Attribution { final java.util.Optional<String> user; }
          @lombok.RequiredArgsConstructor static final class Classification {
            final java.util.Optional<String> category;
          }
        }
        """;
    var loader = compile(source, true);
    var calls = parse(source, loader).descendants(ASTConstructorCall.class).toList();
    assertEquals(3, calls.size());
    for (var call : calls) {
      assertTrue(resolved(loader, call).isConstructor());
    }
  }

  @Test
  void leavesSuccessfulPmdBindingAlone() throws Exception {
    var source =
        PREFIX + "public class Data { Data(int n) {} static Data copy() { return new Data(1); } }";
    var loader = compile(source, false);
    var call = parse(source, loader).descendants(ASTConstructorCall.class).first();
    assertFalse(call.getOverloadSelectionInfo().isFailed());
    assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
    assertEquals(0, loader.applicationLoads);
  }

  @Test
  void generatedNameIndependentMethodUsesTheCurrentReceiver() throws Exception {
    var loader = binary(GENERATED + "public String arbitrary(String text) { return text; }");
    var call =
        methodCall("public String copy(Data data) { return data.arbitrary(\"yes\"); }", loader);
    assertEquals("arbitrary", resolved(loader, call).getName());
    assertEquals(0, loader.applicationLoads);
  }

  @Test
  void classAnnotationAndConventionalNamesAreNotMemberEvidence() throws Exception {
    var loader =
        compile(
            PREFIX + GENERATED + "public class Data { public String getValue() { return \"x\"; } }",
            false);
    var call = methodCall("public String copy() { return getValue(); }", loader);
    assertTrue(call.getOverloadSelectionInfo().isFailed());
    assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
  }

  @Test
  void sourcePrecedenceRejectsStaleGeneratedAndHandwrittenSignatures() throws Exception {
    for (String annotation : List.of("", GENERATED)) {
      var loader =
          binary(annotation + "public static String work(Object value) { return \"old\"; }");
      var call =
          methodCall(
              GENERATED
                  + "public static String work(int value) { return \"new\"; }"
                  + "public String copy() { return work(\"invalid source\"); }",
              loader);
      assertTrue(call.getOverloadSelectionInfo().isFailed());
      assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
    }
  }

  @Test
  void sourcePrecedenceRejectsStaleConstructorEvenWithGeneratedAnnotation() throws Exception {
    var loader = binary(GENERATED + "public Data(String text) { }");
    var source =
        PREFIX
            + "public class Data { "
            + GENERATED
            + "public Data(int count) {} public static Data copy() { return new Data(\"invalid\"); } }";
    var call = parse(source, loader).descendants(ASTConstructorCall.class).first();
    assertTrue(call.getOverloadSelectionInfo().isFailed());
    assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
  }

  @Test
  void strictInvocationPhasePrecedesBoxing() throws Exception {
    var loader =
        binary(
            GENERATED
                + "public void work(long value) {} "
                + GENERATED
                + "public void work(Integer value) {} ");
    var call = methodCall("public void copy() { work(1); }", loader);
    assertEquals(List.of("long"), parameterNames(resolved(loader, call)));
  }

  @Test
  void supportsBoxingReferenceWideningAndUnboxingPrimitiveWidening() throws Exception {
    var reference = binary(GENERATED + "public void work(Number value) {} ");
    assertEquals(
        List.of("java.lang.Number"),
        parameterNames(
            resolved(reference, methodCall("public void copy() { work(1); }", reference))));
    var primitive = binary(GENERATED + "public void work(long value) {} ");
    assertEquals(
        List.of("long"),
        parameterNames(
            resolved(
                primitive,
                methodCall("public void copy(Integer number) { work(number); }", primitive))));
  }

  @Test
  void rejectsNarrowingWidenThenBoxAndNullToPrimitive() throws Exception {
    for (var specimen :
        List.of(
            new String[] {"byte", "1"},
            new String[] {"Long", "1"},
            new String[] {"int", "null"},
            new String[] {"String", "1"})) {
      var loader = binary(GENERATED + "public void work(" + specimen[0] + " value) {}");
      assertTrue(
          new CompiledInvocations(loader)
              .resolve(methodCall("public void copy() { work(" + specimen[1] + "); }", loader))
              .isEmpty());
    }
  }

  @Test
  void rejectsAmbiguousReferenceOverloads() throws Exception {
    var loader =
        binary(
            GENERATED
                + "public void work(java.io.Serializable value) {} "
                + GENERATED
                + "public void work(CharSequence value) {} ");
    for (String argument : List.of("\"text\"", "null")) {
      var call = methodCall("public void copy() { work(" + argument + "); }", loader);
      assertTrue(call.getOverloadSelectionInfo().isFailed());
      assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
    }
  }

  @Test
  void rejectsVarargsGenericInferenceAndIncompatibleParameterizedTypes() throws Exception {
    for (String declaration :
        List.of(
            "public void work(String... values) {}",
            "public <T> void work(T value) {}",
            "public void work(java.util.List<Integer> values) {}")) {
      var loader = binary(GENERATED + declaration);
      var call =
          methodCall("public void copy(java.util.List<String> values) { work(values); }", loader);
      assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
    }
  }

  @Test
  void preservesConcreteParameterizedApplicability() throws Exception {
    var loader = binary(GENERATED + "public void work(java.util.Collection<String> values) {}");
    var call =
        methodCall("public void copy(java.util.List<String> values) { work(values); }", loader);
    assertEquals("work", resolved(loader, call).getName());
  }

  @Test
  void targetTypesAnExactlyBoundJdkEmptyOptional() throws Exception {
    var source =
        PREFIX
            + """
        @lombok.RequiredArgsConstructor public class Data {
          final java.util.Optional<String> text;
          static Data copy() { return new Data(java.util.Optional.empty()); }
        }
        """;
    var loader = compile(source, true);
    var call = parse(source, loader).descendants(ASTConstructorCall.class).first();
    assertEquals(
        "java.util.Optional<java.lang.String>",
        resolved(loader, call).getFormalParameters().getFirst().toString());
  }

  @Test
  void recoveredGeneratedReceiverReturnsTheCurrentHandwrittenTerminalSignature() throws Exception {
    var loader =
        binary(
            GENERATED
                + "public Data stage(String value) { return this; } public Data done() { return this; }");
    var root =
        parse(
            PREFIX
                + "public class Data { public String done() { return \"current body\"; }"
                + "public String copy() { return stage(\"x\").done(); } }",
            loader);
    var call = namedCall(root, "done");
    assertEquals("done", call.getMethodName());
    var result = resolved(loader, call);
    var current =
        root.descendants(ASTMethodDeclaration.class)
            .crossFindBoundaries(true)
            .first(method -> method.getName().equals("done"));
    assertSame(
        current,
        result.getSymbol().tryGetNode(),
        "the old compiled Data return/body must never replace current source");
    assertSame(current.getGenericSignature(), result);
    assertEquals(
        "java.lang.String", ((JClassSymbol) result.getReturnType().getSymbol()).getBinaryName());
    assertEquals("stage", resolved(loader, (ASTMethodCall) call.getQualifier()).getName());
  }

  @Test
  void targetTypesOnlyTheFourEmptyJdkFactoriesInExplicitThis() throws Exception {
    var source =
        PREFIX
            + """
        @lombok.RequiredArgsConstructor public class Data {
          final java.util.Optional<String> text;
          final java.util.List<Integer> numbers;
          final java.util.Set<Long> identifiers;
          final java.util.Map<String, Integer> attributes;
          Data(int marker) {
            this(java.util.Optional.empty(), java.util.List.of(), java.util.Set.of(), java.util.Map.of());
          }
        }
        """;
    var loader = compile(source, true);
    var call =
        parse(source, loader)
            .descendants(ASTExplicitConstructorInvocation.class)
            .crossFindBoundaries(true)
            .first();
    var result = resolved(loader, call);
    assertEquals(
        List.of(
            "java.util.Optional<java.lang.String>",
            "java.util.List<java.lang.Integer>",
            "java.util.Set<java.lang.Long>",
            "java.util.Map<java.lang.String, java.lang.Integer>"),
        result.getFormalParameters().stream().map(Object::toString).toList());
    assertEquals(0, loader.applicationLoads);
  }

  @Test
  void targetTypesEmptyListsThroughoutAGeneratedBuilderChain() throws Exception {
    var source =
        PREFIX
            + """
        @lombok.Builder @lombok.Value public class Data {
          java.util.List<String> names;
          java.util.List<Integer> numbers;
          java.util.List<Double> weights;
          String creator;
          static Data copy() {
            return Data.builder().names(java.util.List.of()).numbers(java.util.List.of())
                .weights(java.util.List.of()).creator("owner").build();
          }
        }
        """;
    var loader = compile(source, true);
    var result = resolved(loader, namedCall(parse(source, loader), "build"));
    assertEquals(
        "fixtures.Data", ((JClassSymbol) result.getReturnType().getSymbol()).getBinaryName());
    assertNull(result.getSymbol().tryGetNode());
  }

  @Test
  void nonemptyContainersAndExplicitTypeArgumentsKeepTheirGenericContracts() throws Exception {
    for (var specimen :
        List.of(
            new String[] {"java.util.Optional", "java.util.Optional.of(1)"},
            new String[] {"java.util.List", "java.util.List.of(1)"},
            new String[] {"java.util.Set", "java.util.Set.of(1)"},
            new String[] {"java.util.Optional", "java.util.Optional.<Integer>empty()"},
            new String[] {"java.util.List", "java.util.List.<Integer>of()"})) {
      var loader = binary(GENERATED + "public void work(" + specimen[0] + "<String> value) {}");
      var call = methodCall("void copy() { work(" + specimen[1] + "); }", loader);
      assertTrue(call.getOverloadSelectionInfo().isFailed());
      assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty(), specimen[1]);
    }
    var maps = binary(GENERATED + "public void work(java.util.Map<String, String> value) {}");
    assertTrue(
        new CompiledInvocations(maps)
            .resolve(methodCall("void copy() { work(java.util.Map.of(\"key\", 1)); }", maps))
            .isEmpty());
  }

  @Test
  void emptyVariablesAndDifferentRawContainerTargetsAreNotRetargeted() throws Exception {
    var loader = binary(GENERATED + "public void work(java.util.List<String> value) {}");
    var variableCall =
        methodCall("void copy(java.util.List<Integer> value) { work(value); }", loader);
    assertTrue(new CompiledInvocations(loader).resolve(variableCall).isEmpty());
    var collection = binary(GENERATED + "public void work(java.util.Collection<String> value) {}");
    assertTrue(
        new CompiledInvocations(collection)
            .resolve(methodCall("void copy() { work(java.util.List.of()); }", collection))
            .isEmpty());
    var emptyListCall =
        methodCall("void copy() { work(java.util.Collections.emptyList()); }", loader);
    assertTrue(new CompiledInvocations(loader).resolve(emptyListCall).isEmpty());
  }

  @Test
  void aStaticallyImportedEmptyFactoryKeepsItsExactJdkBinding() throws Exception {
    var loader = binary(GENERATED + "public void work(java.util.List<String> value) {}");
    var root =
        parse(
            PREFIX
                + "import static java.util.List.of; public class Data { void copy() { work(of()); } }",
            loader);
    assertEquals(
        "java.util.List<java.lang.String>",
        resolved(loader, namedCall(root, "work")).getFormalParameters().getFirst().toString());
  }

  @Test
  void aSourceFactoryWithTheSameNameIsNotAnEmptyJdkFactory() throws Exception {
    var loader = binary(GENERATED + "public void work(java.util.Optional<String> value) {}");
    var root =
        parse(
            PREFIX
                + "class Optional { static java.util.Optional<Integer> empty() { return java.util.Optional.of(1); } }"
                + "public class Data { void copy() { work(Optional.empty()); } }",
            loader);
    var call = namedCall(root, "work");
    assertTrue(call.getOverloadSelectionInfo().isFailed());
    assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
  }

  @Test
  void candidateSpecificEmptyTargetTypingDoesNotChooseAnAmbiguousOverload() throws Exception {
    var loader =
        binary(
            GENERATED
                + "public void work(java.util.List<String> value, Integer discriminator) {} "
                + GENERATED
                + "public void work(java.util.List<Integer> value, String discriminator) {}");
    var call = methodCall("void copy() { work(java.util.List.of(), null); }", loader);
    assertTrue(call.getOverloadSelectionInfo().isFailed());
    assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
  }

  @Test
  void sourcePrecedenceCrossesNestedTypeBoundariesAfterABytecodeQualifierRepair() throws Exception {
    var before =
        PREFIX
            + """
        public class Data {
          public static class Nest {
            @lombok.Generated public Member view() { return new Member(); }
            public static class Member {
              @lombok.Generated public String work(String value) { return "old"; }
            }
          }
        }
        """;
    var loader = compile(before, false);
    var current =
        PREFIX
            + """
        public class Data {
          public static class Nest {
            public static class Member {
              @lombok.Generated public String work(int value) { return "current"; }
            }
          }
        }
        class Caller { String copy(Data.Nest nest) { return nest.view().work("invalid source"); } }
        """;
    var call = namedCall(parse(current, loader), "work");
    assertTrue(call.getOverloadSelectionInfo().isFailed());
    var qualifier = (ASTMethodCall) call.getQualifier();
    assertEquals(
        "fixtures.Data$Nest$Member",
        ((JClassSymbol) resolved(loader, qualifier).getReturnType().getSymbol()).getBinaryName());
    assertTrue(
        new CompiledInvocations(loader).resolve(call).isEmpty(),
        "nested handwritten @Generated is still source");
  }

  @Test
  void anApplicableNestedHandwrittenGeneratedAnnotationStillReturnsTheActualAst() throws Exception {
    var before =
        PREFIX
            + """
        public class Data {
          @lombok.Generated public Nest.Member view() { return new Nest.Member(); }
          public static class Nest {
            public static class Member { @lombok.Generated public String value() { return "old"; } }
          }
        }
        """;
    var loader = compile(before, false);
    var current =
        PREFIX
            + """
        public class Data {
          public static class Nest {
            public static class Member { @lombok.Generated public String value() { return "current"; } }
          }
        }
        class Caller { String copy(Data data) { return data.view().value(); } }
        """;
    var root = parse(current, loader);
    var declaration =
        root.descendants(ASTMethodDeclaration.class)
            .crossFindBoundaries(true)
            .first(method -> method.getName().equals("value"));
    assertSame(declaration, resolved(loader, namedCall(root, "value")).getSymbol().tryGetNode());
  }

  @Test
  void localSourceCallbacksDoNotHideInvalidWrittenMembers() throws Exception {
    var before =
        PREFIX
            + """
        public class Data {
          Runnable action() {
            class Local {
              @lombok.Generated public Local stage() { return this; }
              @lombok.Generated public void work(String value) { }
            }
            return () -> new Local().stage().work("old");
          }
        }
        """;
    var loader = compile(before, false);
    var current =
        PREFIX
            + """
        public class Data {
          Runnable action() {
            class Local { @lombok.Generated public void work(int value) { } }
            return () -> new Local().stage().work("invalid");
          }
        }
        """;
    var call = namedCall(parse(current, loader), "work");
    assertTrue(call.getOverloadSelectionInfo().isFailed());
    assertTrue(
        new CompiledInvocations(loader).resolve(call).isEmpty(),
        "local-class repair stays unsupported");
  }

  @Test
  void aTypedReceiverDoesNotEnableWrittenSourceFallback() throws Exception {
    var loader = binary(GENERATED + "public void work(String value) {}");
    var call =
        methodCall(
            GENERATED
                + "public void work(int value) {} void copy(Data data) { data.work(\"invalid\"); }",
            loader);
    assertEquals(
        "fixtures.Data",
        ((JClassSymbol) call.getQualifier().getTypeMirror().getSymbol()).getBinaryName());
    assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
  }

  @Test
  void anAmbiguousCurrentSourceOrInvalidWrittenCallCannotUseAnOldSignature() throws Exception {
    var loader =
        binary(
            GENERATED
                + "public Data stage() { return this; } "
                + GENERATED
                + "public void work(String value) {}");
    for (String members :
        List.of(
            "public void work(int value) {} void copy() { stage().work(\"old\"); }",
            "public void work(String value) {} public void work(Integer value) {}"
                + "void copy() { stage().work(null); }")) {
      var call = methodCall(members, loader);
      assertEquals("work", call.getMethodName());
      assertTrue(call.getOverloadSelectionInfo().isFailed());
      assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
    }
  }

  @Test
  void aNongeneratedQualifierBindingDoesNotEnableSourceRepair() throws Exception {
    var loader =
        binary("public Data stage() { return this; } public String done() { return \"old\"; }");
    var call =
        methodCall(
            "public String done() { return \"current\"; } String copy() { return stage().done(); }",
            loader);
    assertTrue(call.getOverloadSelectionInfo().isFailed());
    assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
  }

  @Test
  void sourceRepairStopsWhenARepairedChainContainsANongeneratedBinaryMethod() throws Exception {
    var dataSource =
        PREFIX
            + "public class Data { "
            + GENERATED
            + "public Bridge stage() { return new Bridge(); } "
            + "public String done() { return \"old\"; } }";
    var loader =
        compile(
            Map.of(
                "Data",
                dataSource,
                "Bridge",
                PREFIX + "public class Bridge { public Data back() { return new Data(); } }"),
            false);
    var call =
        methodCall(
            "public String done() { return \"current\"; } String copy() { return stage().back().done(); }",
            loader);
    assertTrue(call.getOverloadSelectionInfo().isFailed());
    assertEquals(
        "back",
        resolved(loader, (ASTMethodCall) call.getQualifier()).getName(),
        "binary binding alone is insufficient");
    assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
  }

  @Test
  void emptyFactoryResourceEvidenceIsRequiredAndRefreshed() throws Exception {
    var loader = binary(GENERATED + "public void work(java.util.Optional<String> value) {}");
    var call = methodCall("void copy() { work(java.util.Optional.empty()); }", loader);
    Map<String, Optional<byte[]>> overrides = new HashMap<>();
    ClassLoader mutable =
        new ClassLoader(loader) {
          @Override
          public InputStream getResourceAsStream(String name) {
            return overrides.containsKey(name)
                ? overrides.get(name).map(ByteArrayInputStream::new).orElse(null)
                : loader.getResourceAsStream(name);
          }
        };
    var proof = new CompiledInvocations(mutable);
    assertTrue(proof.resolve(call).isPresent());
    overrides.put("java/util/Optional.class", Optional.empty());
    assertTrue(
        proof.resolve(call).isEmpty(),
        "a previously resolved PMD symbol cannot replace missing JDK evidence");
    overrides.put("java/util/Optional.class", Optional.of(new byte[] {0, 1, 2}));
    assertTrue(proof.resolve(call).isEmpty());
    overrides.clear();
    assertTrue(proof.resolve(call).isPresent());
  }

  @Test
  void sourceBindingRequiresFreshGeneratedQualifierAndTargetEvidence() throws Exception {
    var loader =
        binary(
            GENERATED
                + "public Data stage() { return this; } public String done() { return \"old\"; }");
    var root =
        parse(
            PREFIX
                + "public class Data { public String done() { return \"current\"; }"
                + "String copy() { return stage().done(); } }",
            loader);
    var call = namedCall(root, "done");
    var current =
        root.descendants(ASTMethodDeclaration.class)
            .crossFindBoundaries(true)
            .first(method -> method.getName().equals("done"));
    var proof = new CompiledInvocations(loader);
    assertSame(current, proof.resolve(call).orElseThrow().getSymbol().tryGetNode());
    var original = loader.classes.remove("fixtures/Data.class");
    assertTrue(proof.resolve(call).isEmpty());
    loader.classes.put(
        "fixtures/Data.class",
        binary("public Data stage() { return this; }" + "public String done() { return \"old\"; }")
            .classes
            .get("fixtures/Data.class"));
    assertTrue(
        proof.resolve(call).isEmpty(),
        "source binding cannot reuse a prior generated qualifier proof");
    loader.classes.put("fixtures/Data.class", original);
    assertSame(current, proof.resolve(call).orElseThrow().getSymbol().tryGetNode());
  }

  @Test
  void repairedSourceAccessUsesCurrentVisibilityRatherThanOldBytecode() throws Exception {
    var loader =
        binary(
            GENERATED
                + "public Data stage() { return this; } public String done() { return \"old\"; }");
    var root =
        parse(
            PREFIX
                + "public class Data { private String done() { return \"current\"; } }"
                + "class Caller { String copy(Data data) { return data.stage().done(); } }",
            loader);
    var call = namedCall(root, "done");
    assertTrue(call.getOverloadSelectionInfo().isFailed());
    assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
  }

  @Test
  void aCurrentAstDoesNotExcuseCorruptResourceEvidenceForTheRepairedTarget() throws Exception {
    var before =
        PREFIX
            + """
        public class Data {
          @lombok.Generated public Leaf view() { return new Leaf(); }
          public static class Leaf { public int value() { return 1; } }
        }
        """;
    var loader = compile(before, false);
    var root =
        parse(
            PREFIX
                + "public class Data { public static class Leaf { public String value() { return \"current\"; } }"
                + "String copy() { return view().value(); } }",
            loader);
    var call = namedCall(root, "value");
    var proof = new CompiledInvocations(loader);
    assertNotNull(proof.resolve(call).orElseThrow().getSymbol().tryGetNode());
    var parser = ClassFile.of();
    var invalid =
        parser.build(
            ClassDesc.of("fixtures.Data$Leaf"),
            builder ->
                builder
                    .withFlags(ClassFile.ACC_PUBLIC)
                    .withMethodBody(
                        "value",
                        MethodTypeDesc.ofDescriptor("()I"),
                        ClassFile.ACC_PUBLIC,
                        code -> code.return_()));
    assertFalse(parser.verify(parser.parse(invalid)).isEmpty());
    loader.classes.put("fixtures/Data$Leaf.class", invalid);
    assertTrue(proof.resolve(call).isEmpty());
  }

  @Test
  void rejectsUnknownArgumentsEvenWhenObjectWouldFitAnErasedGuess() throws Exception {
    var loader = binary(GENERATED + "public void work(Object value) {}");
    var call = methodCall("public void copy() { work(missing); }", loader);
    assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
  }

  @Test
  void rejectsStaticReceiverAndStaticContextForInstanceOverloads() throws Exception {
    var loader =
        binary(
            GENERATED
                + "public void work(String value) {} "
                + GENERATED
                + "public static void work(Object value) {} ");
    for (String body :
        List.of(
            "public void copy() { Data.work(\"x\"); }",
            "public static void copy() { work(\"x\"); }")) {
      assertTrue(new CompiledInvocations(loader).resolve(methodCall(body, loader)).isEmpty());
    }
    var single = binary(GENERATED + "public void work(String value) {}");
    assertTrue(
        new CompiledInvocations(single)
            .resolve(methodCall("public static void copy() { work(\"x\"); }", single))
            .isEmpty());
  }

  @Test
  void permitsTypeQualifiedGeneratedStaticMember() throws Exception {
    var loader = binary(GENERATED + "public static void work(String value) {}");
    assertTrue(
        resolved(loader, methodCall("public void copy() { Data.work(\"x\"); }", loader))
            .isStatic());
  }

  @Test
  void rejectsPrivateAccessFromAnotherSourceOwner() throws Exception {
    var loader = binary(GENERATED + "private void work(String value) {}");
    var source =
        PREFIX + "class Data {} class Other { void copy(Data data) { data.work(\"x\"); } }";
    var call = parse(source, loader).descendants(ASTMethodCall.class).first();
    assertTrue(call.getOverloadSelectionInfo().isFailed());
    assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
  }

  @Test
  void rejectsProtectedAccessAcrossPackagesWithoutSubclassProof() throws Exception {
    var loader = binary(GENERATED + "protected void work(String value) {}");
    var call =
        parse(
                "package other; class Caller { void copy(fixtures.Data data) { data.work(\"x\"); } }",
                loader)
            .descendants(ASTMethodCall.class)
            .first();
    assertTrue(call.getOverloadSelectionInfo().isFailed());
    assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
  }

  @Test
  void rejectsRemovedSuperclassInsteadOfFindingItsOldMethod() throws Exception {
    var loader =
        compile(
            PREFIX
                + "class Parent { public void work(String value) {} } public class Data extends Parent {}",
            false);
    var call = methodCall("public void copy() { work(\"x\"); }", loader);
    assertTrue(call.getOverloadSelectionInfo().isFailed());
    assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
  }

  @Test
  void rejectsRemovedArgumentInterfaceInsteadOfWideningThroughOldBytecode() throws Exception {
    var loader =
        compile(
            PREFIX
                + "class Argument implements java.io.Serializable {} public class Data { "
                + GENERATED
                + "public void work(java.io.Serializable value) {} }",
            false);
    var source =
        PREFIX
            + "class Argument {} public class Data { public void copy(Argument argument) { work(argument); } }";
    var call = parse(source, loader).descendants(ASTMethodCall.class).first();
    assertTrue(call.getOverloadSelectionInfo().isFailed());
    assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
  }

  @Test
  void rejectsChangedGenericSupertypeInsteadOfComparingOnlyErasure() throws Exception {
    var loader =
        compile(
            PREFIX
                + "class Argument extends java.util.ArrayList<String> {} public class Data { "
                + GENERATED
                + "public void work(java.util.List<String> value) {} }",
            false);
    var source =
        PREFIX
            + "class Argument extends java.util.ArrayList<Integer> {}"
            + "public class Data { public void copy(Argument argument) { work(argument); } }";
    var call = parse(source, loader).descendants(ASTMethodCall.class).first();
    assertTrue(call.getOverloadSelectionInfo().isFailed());
    assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
  }

  @Test
  void writtenIntermediateOverrideWinsOverInheritedBinaryMethod() throws Exception {
    var loader =
        compile(
            PREFIX
                + "class Parent { "
                + GENERATED
                + "public void work(String value) {} }"
                + "class Middle extends Parent {} public class Data extends Middle {}",
            false);
    var source =
        PREFIX
            + "class Parent {} class Middle extends Parent { "
            + GENERATED
            + "public void work(int value) {} } public class Data extends Middle { void copy() { work(\"invalid\"); } }";
    var call = parse(source, loader).descendants(ASTMethodCall.class).first();
    assertTrue(call.getOverloadSelectionInfo().isFailed());
    assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
  }

  @Test
  void rejectsUnannotatedConstructorAndWrongArity() throws Exception {
    for (String member :
        List.of(
            "public Data(int number) {}", GENERATED + "public Data(int first, int second) {}")) {
      var loader = binary(member);
      var source = PREFIX + "public class Data { static Data copy() { return new Data(1); } }";
      var call = parse(source, loader).descendants(ASTConstructorCall.class).first();
      assertTrue(call.getOverloadSelectionInfo().isFailed());
      assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
    }
  }

  @Test
  void rejectsAnnotatedButUnverifiableConstructorBytecode() throws Exception {
    var parser = ClassFile.of();
    var generatedMarker =
        RuntimeInvisibleAnnotationsAttribute.of(Annotation.of(ClassDesc.of("lombok.Generated")));
    var invalid =
        parser.build(
            ClassDesc.of("fixtures.Data"),
            builder ->
                builder
                    .withFlags(ClassFile.ACC_PUBLIC)
                    .withMethod(
                        "<init>",
                        MethodTypeDesc.ofDescriptor("(I)V"),
                        ClassFile.ACC_PUBLIC,
                        method -> method.with(generatedMarker).withCode(code -> code.return_())));
    assertFalse(
        parser.verify(parser.parse(invalid)).isEmpty(),
        "missing superclass constructor invocation");
    var loader = new ResourceLoader(new HashMap<>(Map.of("fixtures/Data.class", invalid)));
    var call =
        parse(PREFIX + "public class Data { static Data copy() { return new Data(1); } }", loader)
            .descendants(ASTConstructorCall.class)
            .first();
    assertTrue(call.getOverloadSelectionInfo().isFailed());
    assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
  }

  @Test
  void rejectsIncompatibleArraysAndMissingArgumentClassEvidence() throws Exception {
    var loader = binary(GENERATED + "public void work(long[] values) {}");
    var call = methodCall("void copy(int[] values) { work(values); }", loader);
    assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
    var missing = binary(GENERATED + "public void work(Object value) {}");
    var unknown =
        parse(
                PREFIX
                    + "class Argument {} public class Data { void copy(Argument value) { work(value); } }",
                missing)
            .descendants(ASTMethodCall.class)
            .first();
    assertTrue(new CompiledInvocations(missing).resolve(unknown).isEmpty());
  }

  @Test
  void rereadsEvidenceForEveryRequestAndNeverLoadsTheApplication() throws Exception {
    var loader =
        binary(
            GENERATED
                + "public void work(String value) {}"
                + "static { if (System.nanoTime() != 0) throw new AssertionError(\"initialized\"); }");
    var call = methodCall("public void copy() { work(\"x\"); }", loader);
    var proof = new CompiledInvocations(loader);
    assertTrue(proof.resolve(call).isPresent());
    var original = loader.classes.remove("fixtures/Data.class");
    assertTrue(proof.resolve(call).isEmpty());
    loader.classes.put("fixtures/Data.class", new byte[] {0, 1, 2});
    assertTrue(proof.resolve(call).isEmpty());
    loader.classes.put(
        "fixtures/Data.class",
        binary(GENERATED + "public void work(Integer value) {}")
            .classes
            .get("fixtures/Data.class"));
    assertTrue(
        proof.resolve(call).isEmpty(), "a changed descriptor must invalidate a positive binding");
    loader.classes.put("fixtures/Data.class", original);
    assertTrue(proof.resolve(call).isPresent());
    assertEquals(0, loader.applicationLoads);
  }

  @Test
  void rejectsWrongOwnerOversizedAndUnreadableEvidence() throws Exception {
    var loader = binary(GENERATED + "public void work(String value) {}");
    var call = methodCall("public void copy() { work(\"x\"); }", loader);
    var other = compile(PREFIX + "class Other {}", false);
    loader.classes.put("fixtures/Data.class", other.classes.get("fixtures/Other.class"));
    assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
    loader.classes.put("fixtures/Data.class", new byte[4 * 1024 * 1024 + 1]);
    assertTrue(new CompiledInvocations(loader).resolve(call).isEmpty());
    ClassLoader broken =
        new ClassLoader(loader) {
          @Override
          public InputStream getResourceAsStream(String name) {
            if (name.equals("fixtures/Data.class")) {
              return new InputStream() {
                @Override
                public int read() throws IOException {
                  throw new IOException("unreadable evidence");
                }
              };
            }
            return super.getResourceAsStream(name);
          }
        };
    assertTrue(new CompiledInvocations(broken).resolve(call).isEmpty());
  }

  @Test
  void twoSourceUnitsKeepAdapterSelfConstructionSeparateFromMissingBindings() throws Exception {
    var value = "package com.ai.label.domain.probe; public class Value {}";
    var adapter =
        """
        package com.ai.label.persistence.probe;
        @lombok.AllArgsConstructor public class AdapterFactory {
          final int number;
          final String text;
          static AdapterFactory copy(int number, String text) { return new AdapterFactory(number, text); }
        }
        """;
    var loader = compile(Map.of("Value", value, "AdapterFactory", adapter), true);
    var roots = List.of(parse(value, loader), parse(adapter, loader));
    var failures =
        roots.stream()
            .flatMap(root -> root.descendants(InvocationNode.class).toList().stream())
            .filter(call -> call.getOverloadSelectionInfo().isFailed())
            .toList();
    assertEquals(2, roots.size());
    assertEquals(1, failures.size(), "baseline: adapter self-constructor is IOSP_UNRESOLVED");
    var proof = new CompiledInvocations(loader);
    assertEquals(
        0,
        failures.stream().filter(call -> proof.resolve(call).isEmpty()).count(),
        "after helper: the same two units have no unsupported binding");
    var report = IospAnalysis.analyzeSource(adapter, loader, "com.ai.label");
    assertTrue(report.getProcessingErrors().isEmpty(), report.getProcessingErrors().toString());
    assertTrue(report.getViolations().isEmpty(), report.getViolations().toString());
  }

  @Test
  void recoveredConstructorsStillRejectCalculationInsideAFactory() throws Exception {
    var source =
        """
        package com.ai.label.domain.probe;
        @lombok.Value public class DataFactory {
          int count;
          static DataFactory scale(int count) { return new DataFactory(count * 2); }
        }
        """;
    var loader = compile(Map.of("DataFactory", source), true);
    var report = IospAnalysis.analyzeSource(source, loader, "com.ai.label");
    assertTrue(report.getProcessingErrors().isEmpty(), report.getProcessingErrors().toString());
    assertEquals(1, report.getViolations().size(), report.getViolations().toString());
    assertTrue(report.getViolations().getFirst().getDescription().contains("IOSP_MIXED"));
    assertEquals(0, loader.applicationLoads);
  }

  private static final class ResourceLoader extends ClassLoader {
    private final Map<String, byte[]> classes;
    private int applicationLoads;

    private ResourceLoader(Map<String, byte[]> classes) {
      super(CompiledInvocationsTest.class.getClassLoader());
      this.classes = classes;
    }

    @Override
    public InputStream getResourceAsStream(String name) {
      var bytes = this.classes.get(name);
      if (bytes != null) {
        return new ByteArrayInputStream(bytes);
      }
      return name.startsWith("fixtures/") ? null : super.getResourceAsStream(name);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (name.startsWith("fixtures.")
          || name.startsWith("com.ai.label.persistence.probe.")
          || name.startsWith("com.ai.label.domain.probe.")) {
        this.applicationLoads++;
        throw new AssertionError("Application class loading is forbidden: " + name);
      }
      return super.loadClass(name, resolve);
    }
  }
}
