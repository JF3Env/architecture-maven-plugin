package io.github.jf3env.architecture.iosp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
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
import net.sourceforge.pmd.lang.java.ast.ASTMethodCall;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTReturnStatement;
import net.sourceforge.pmd.lang.java.ast.ASTVariableAccess;
import net.sourceforge.pmd.lang.java.ast.InvocationNode;
import net.sourceforge.pmd.lang.java.internal.JavaLanguageProcessor;
import net.sourceforge.pmd.lang.java.internal.JavaLanguageProperties;
import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.types.JMethodSig;
import net.sourceforge.pmd.lang.java.types.TypeSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifiedConstructionTest {
  private static final String FIXTURE =
      """
      package fixtures;
      public final class Constructions {
        public static ProductBuilder start() { return new ProductBuilder(); }
        public static ProductBuilder impureStart() { Repository.save(); return new ProductBuilder(); }
        public static ProductBuilder delegatedStart() { return start(); }
        public static ProductBuilder cachedStart() { return cached; }
        static ProductBuilder cached;
        public static Value valueFactory(String text) { return new Value(text); }
        public static PlainValue plainFactory() { return new PlainValue(); }
        public static SetterOnly setterFactory() { return new SetterOnly(); }
        public static TerminalOnly terminalFactory() { return new TerminalOnly(); }
        public static Disconnected disconnectedFactory() { return new Disconnected(); }
        public static SelfCopy selfFactory() { return new SelfCopy(); }
        public static Seeded seededFactory() { return new Seeded(); }
        public static Cyclic cyclicFactory() { return new Cyclic(); }

        // Non-final class and methods, as emitted by ordinary Lombok @Builder.
        public static class ProductBuilder {
          private String text;
          private long sequence;
          private double precise;
          private String unused;
          private Repository repository;
          private static String global;
          ProductBuilder() {}
          public ProductBuilder text(String value) { this.text = value; return this; }
          public ProductBuilder sequence(long value) { sequence = value; return this; }
          public ProductBuilder precise(double value) { this.precise = value; return this; }
          public final ProductBuilder fixedText(String value) { text = value; return this; }
          private ProductBuilder privateText(String value) { text = value; return this; }
          public ProductBuilder casePrivate() { return privateText("value"); }
          public Product build() { return new Product(this.text, sequence, precise); }
          public ProductBuilder text(CharSequence value) { this.text = value.toString(); return this; }
          public ProductBuilder sequence(int value) { sequence = value; return this; }
          public ProductBuilder unused(String value) { unused = value; return this; }
          public ProductBuilder load(Repository value) { text = value.load(); return this; }
          public ProductBuilder unknown(String value) { text = Repository.unknown(value); return this; }
          public ProductBuilder modified(String value) { text = value.trim(); return this; }
          public ProductBuilder increment(long value) { sequence += value; return this; }
          public ProductBuilder global(String value) { global = value; return this; }
          public ProductBuilder wrongReturn(String value) { text = value; return cached; }
          public ProductBuilder argument(ProductBuilder value) { return value; }
          public ProductBuilder other(ProductBuilder value) { value.text = text; return this; }
          public ProductBuilder foreign(String value) { cached.text = value; return this; }
          public ProductBuilder chained(String value) { return text(value); }
          public synchronized ProductBuilder locked(String value) { text = value; return this; }
          public native ProductBuilder unavailable(String value);
          public Product computed() { return new Product(text.trim(), sequence + 1, precise); }
          public Product constant() { return new Product("literal", sequence, precise); }
          public Product globalProduct() { return new Product(global, sequence, precise); }
          public Product nested() { return new Product(new Value(text).text, sequence, precise); }
          public Product business() { repository.save(); return new Product(text, sequence, precise); }
          public Product build(String value) { return new Product(value, sequence, precise); }
          public Product delegated() { return build(); }
          public Product cycleA() { return cycleB(); }
          public Product cycleB() { return cycleA(); }
          @javax.annotation.processing.Generated("lombok")
          public ProductBuilder generated(String value) { Repository.save(); text = value; return this; }
        }
        public static final class Product {
          final String text;
          final long sequence;
          final double precise;
          Product(String text, long sequence, double precise) {
            this.text = text; this.sequence = sequence; this.precise = precise;
          }
        }
        public static final class Value {
          final String text;
          Value(String text) { this.text = text; }
        }
        public static final class PlainValue { int number; }
        public static final class FinalComposer {
          String text;
          public FinalComposer text(String value) { text = value; return this; }
          public Value finish() { return new Value(text); }
        }
        public static class Malicious extends ProductBuilder {
          @Override public ProductBuilder text(String value) {
            Repository.save(); return this;
          }
          @Override public Product build() { Repository.save(); return super.build(); }
        }
        public static final class SetterOnly {
          String text;
          public SetterOnly text(String value) { text = value; return this; }
        }
        public static final class TerminalOnly {
          String text;
          public Value build() { return new Value(text); }
        }
        public static final class Disconnected {
          String first;
          String second;
          public Disconnected text(String value) { first = value; return this; }
          public Value build() { return new Value(second); }
        }
        public static final class SelfCopy {
          String text;
          SelfCopy() {}
          SelfCopy(String text) { this.text = text; }
          public SelfCopy text(String value) { text = value; return this; }
          public SelfCopy build() { return new SelfCopy(text); }
        }
        public static final class Seeded {
          String text = Repository.unknown("seed");
          public Seeded text(String value) { text = value; return this; }
          public Value build() { return new Value(text); }
        }
        public static final class Cyclic {
          String text;
          Cyclic() { this(1); }
          Cyclic(int ignored) { Repository.save(); }
          public Cyclic text(String value) { text = value; return this; }
          public Value build() { return new Value(text); }
        }
        public static class WorkerBuilder {
          Repository repository;
          public static WorkerBuilder start() { return new WorkerBuilder(); }
          public String load() { return repository.load(); }
          public Value build() { return repository.loadValue(); }
        }
        public static class Repository {
          public native String load();
          public native Value loadValue();
          public static native String unknown(String value);
          public static native void save();
        }
      }
      """;

  private static final List<String> ACCEPTED =
      List.of(
          "Constructions.start()",
          "ProductBuilder.text(String)",
          "ProductBuilder.sequence(long)",
          "ProductBuilder.precise(double)",
          "ProductBuilder.fixedText(String)",
          "ProductBuilder.privateText(String)",
          "ProductBuilder.build()");

  private static final List<String> REJECTED =
      List.of(
          "Constructions.impureStart()",
          "Constructions.delegatedStart()",
          "Constructions.cachedStart()",
          "Constructions.valueFactory(String)",
          "Constructions.plainFactory()",
          "Constructions.setterFactory()",
          "Constructions.terminalFactory()",
          "Constructions.disconnectedFactory()",
          "Constructions.selfFactory()",
          "Constructions.seededFactory()",
          "Constructions.cyclicFactory()",
          "ProductBuilder.text(CharSequence)",
          "ProductBuilder.sequence(int)",
          "ProductBuilder.unused(String)",
          "ProductBuilder.load(Repository)",
          "ProductBuilder.unknown(String)",
          "ProductBuilder.modified(String)",
          "ProductBuilder.increment(long)",
          "ProductBuilder.global(String)",
          "ProductBuilder.wrongReturn(String)",
          "ProductBuilder.argument(ProductBuilder)",
          "ProductBuilder.other(ProductBuilder)",
          "ProductBuilder.foreign(String)",
          "ProductBuilder.chained(String)",
          "ProductBuilder.locked(String)",
          "ProductBuilder.unavailable(String)",
          "ProductBuilder.computed()",
          "ProductBuilder.constant()",
          "ProductBuilder.globalProduct()",
          "ProductBuilder.nested()",
          "ProductBuilder.business()",
          "ProductBuilder.build(String)",
          "ProductBuilder.delegated()",
          "ProductBuilder.cycleA()",
          "ProductBuilder.cycleB()",
          "ProductBuilder.generated(String)",
          "SetterOnly.text(String)",
          "TerminalOnly.build()",
          "Disconnected.text(String)",
          "Disconnected.build()",
          "SelfCopy.text(String)",
          "SelfCopy.build()",
          "WorkerBuilder.start()",
          "WorkerBuilder.load()",
          "WorkerBuilder.build()");

  private static final String CALLS =
      """
      class CallSites {
        static Constructions.ProductBuilder field;
        static native Constructions.ProductBuilder unknown();
        static native Constructions evaluated();
        static Constructions.ProductBuilder origin() { return new Constructions.ProductBuilder(); }
        static Constructions.ProductBuilder alias() { return Constructions.start(); }
        static Constructions.ProductBuilder fake() { return new Constructions.Malicious(); }
        static Constructions.ProductBuilder caseFactory() { return Constructions.start(); }
        static Constructions.ProductBuilder caseImplicitFactory() { return origin(); }
        static Constructions.ProductBuilder caseNew() {
          return new Constructions.ProductBuilder().text("value");
        }
        static Constructions.Product caseFactoryChain() {
          return Constructions.start().text("value").sequence(1L).precise(2d).build();
        }
        static Constructions.Product caseNewChain() {
          return new Constructions.ProductBuilder().text("value").sequence(1L).build();
        }
        static Constructions.Product caseFreshFinalChain() {
          return Constructions.start().fixedText("value").build();
        }
        static Constructions.FinalComposer caseFinalParameter(Constructions.FinalComposer value) {
          return value.text("value");
        }
        static Constructions.ProductBuilder caseFinalMethod(Constructions.ProductBuilder value) {
          return value.fixedText("value");
        }
        static Constructions.ProductBuilder caseBaseParameter(Constructions.ProductBuilder value) {
          return value.text("value");
        }
        static Constructions.Product caseFinalVariable(final Constructions.ProductBuilder value) {
          return value.build();
        }
        static Constructions.Product caseBaseChain(Constructions.ProductBuilder value) {
          return value.text("value").build();
        }
        static Constructions.Product caseFinalSetterTrap(Constructions.ProductBuilder value) {
          return value.fixedText("value").build();
        }
        static Constructions.Product caseField() { return field.build(); }
        static Constructions.Product caseLocal() {
          Constructions.ProductBuilder local = new Constructions.ProductBuilder();
          return local.build();
        }
        static Constructions.Product caseAnonymous() {
          return new Constructions.ProductBuilder() {}.build();
        }
        static Constructions.Product caseCast() {
          return ((Constructions.ProductBuilder) new Constructions.Malicious()).build();
        }
        static Constructions.Product caseUnknown() { return unknown().build(); }
        static Constructions.Product caseAlias() { return alias().build(); }
        static Constructions.Product caseFake() { return fake().build(); }
        static Constructions.Product caseImpureFactory() { return Constructions.impureStart().build(); }
        static Constructions.Product caseImpureSetter() {
          return Constructions.start().text((CharSequence) "value").build();
        }
        static Constructions.ProductBuilder caseEvaluatedStaticQualifier() {
          return evaluated().start();
        }
        static Constructions.Product caseBusiness() {
          return new Constructions.ProductBuilder().business();
        }
      }
      """;

  private static final List<String> EXACT_CALLS =
      List.of(
          "caseFactory",
          "caseImplicitFactory",
          "caseNew",
          "caseFactoryChain",
          "caseNewChain",
          "caseFreshFinalChain",
          "caseFinalParameter",
          "caseFinalMethod");

  private static final List<String> INEXACT_CALLS =
      List.of(
          "caseBaseParameter",
          "caseFinalVariable",
          "caseBaseChain",
          "caseFinalSetterTrap",
          "caseField",
          "caseLocal",
          "caseAnonymous",
          "caseCast",
          "caseUnknown",
          "caseAlias",
          "caseFake",
          "caseImpureFactory",
          "caseImpureSetter",
          "caseEvaluatedStaticQualifier");

  private static final String ALLOCATION_TYPES =
      """
      package fixtures;
      final class B {
        private String field;
        B() {}
        B setter(String value) { field = value; return this; }
        P terminal() { return new P(field); }
      }
      final class P {
        private final String field;
        P(String value) { field = value; }
      }
      """;

  private static final String ALLOCATION_CALLS =
      """
      class AllocationService {
        P caseNew() { return new B().setter("value").terminal(); }
        P caseParameter(B input) { return input.setter("value").terminal(); }
      }
      """;

  private static final String GENERATED_SNAPSHOT =
      """
      package fixtures;
      public class Constructions {
        @lombok.Value @lombok.Builder
        public static class Snapshot {
          String field;
          java.util.List<String> names;
          int count;
        }
        static Snapshot caseFresh() {
          return Snapshot.builder().field("value").names(java.util.List.of()).count(1).build();
        }
        static Snapshot caseVariable(Snapshot.SnapshotBuilder input) { return input.field("value").build(); }
        static Snapshot caseCast() {
          return ((Snapshot.SnapshotBuilder) Snapshot.builder()).field("value").build();
        }
        static Snapshot.SnapshotBuilder alias() { return Snapshot.builder(); }
        static Snapshot caseAlias() { return alias().field("value").build(); }
      }
      """;

  @TempDir Path temporary;

  private static ASTMethodCall chainCall(ASTMethodCall call, String name) {
    for (var link = call;
        link != null;
        link = link.getQualifier() instanceof ASTMethodCall parent ? parent : null) {
      if (link.getMethodName().equals(name)) {
        return link;
      }
    }
    throw new AssertionError("Missing chain member " + name);
  }

  private static void assertDecisions(
      Map<String, JMethodSig> methods,
      VerifiedConstruction proof,
      List<String> keys,
      boolean expected) {
    for (String key : keys) {
      assertNotNull(methods.get(key), key);
      assertEquals(expected, proof.isConstructionStep(methods.get(key)), key);
    }
  }

  private static Map<String, JMethodSig> sourceMethods(String source) throws Exception {
    return sourceMethods(source, VerifiedConstructionTest.class.getClassLoader());
  }

  private static Map<String, JMethodSig> sourceMethods(String source, ClassLoader loader)
      throws Exception {
    var root = parse(source, loader);
    Map<String, JMethodSig> result = new HashMap<>();
    root.descendants(ASTMethodDeclaration.class)
        .crossFindBoundaries()
        .forEach(
            method -> {
              var signature = method.getGenericSignature();
              result.put(key(signature), signature);
            });
    return result;
  }

  private static ASTCompilationUnit parse(String source, ClassLoader loader) throws Exception {
    var properties = new JavaLanguageProperties();
    properties.setLanguageVersion("24");
    properties.setClassLoader(loader);
    var processor = new JavaLanguageProcessor(properties);
    try (var registry = LanguageProcessorRegistry.singleton(processor);
        var document = TextDocument.readOnlyString(source, processor.getLanguageVersion())) {
      return assertInstanceOf(
          ASTCompilationUnit.class,
          processor
              .getParser()
              .parse(new ParserTask(document, SemanticErrorReporter.noop(), registry)));
    }
  }

  private static Map<String, ASTMethodCall> calls(String source, ClassLoader loader)
      throws Exception {
    Map<String, ASTMethodCall> result = new HashMap<>();
    parse(source, loader)
        .descendants(ASTMethodDeclaration.class)
        .crossFindBoundaries()
        .filter(method -> method.getName().startsWith("case"))
        .forEach(
            method -> {
              var body = method.getBody();
              assertNotNull(body, method.getName());
              var returned = assertInstanceOf(ASTReturnStatement.class, body.get(body.size() - 1));
              result.put(
                  method.getName(), assertInstanceOf(ASTMethodCall.class, returned.getExpr()));
            });
    return result;
  }

  private static void assertCalls(
      Map<String, ASTMethodCall> calls,
      VerifiedConstruction proof,
      List<String> names,
      boolean construction,
      boolean exact) {
    for (String name : names) {
      var call = calls.get(name);
      assertNotNull(call, name);
      assertFalse(call.getOverloadSelectionInfo().isFailed(), name);
      assertEquals(construction, proof.isConstructionStep(call.getMethodType()), name + " body");
      assertEquals(exact, proof.hasExactReceiver(call), name + " dispatch");
    }
  }

  private static void assertFinalAllocation(
      Map<String, ASTMethodCall> calls, VerifiedConstruction proof, boolean expected) {
    assertCalls(calls, proof, List.of("caseNew", "caseParameter"), true, true);
    for (String name : List.of("caseNew", "caseParameter")) {
      var setter = assertInstanceOf(ASTMethodCall.class, calls.get(name).getQualifier());
      assertFalse(setter.getOverloadSelectionInfo().isFailed());
      assertTrue(proof.isConstructionStep(setter.getMethodType()), name + " setter body");
      assertTrue(proof.hasExactReceiver(setter), name + " setter dispatch");
    }
    var parameterSetter =
        assertInstanceOf(ASTMethodCall.class, calls.get("caseParameter").getQualifier());
    assertInstanceOf(ASTVariableAccess.class, parameterSetter.getQualifier());
    assertTrue(
        calls.get("caseParameter").descendants(ASTConstructorCall.class).isEmpty(),
        "a final input supplies exact binding without allocating a builder");
    var setter = assertInstanceOf(ASTMethodCall.class, calls.get("caseNew").getQualifier());
    var allocation = assertInstanceOf(ASTConstructorCall.class, setter.getQualifier());
    assertFalse(allocation.getOverloadSelectionInfo().isFailed());
    assertTrue(allocation.getMethodType().getSymbol().getEnclosingClass().isFinal());
    assertEquals(
        expected,
        proof.isBuilderAllocation(allocation),
        "allocation, independently of fixed dispatch");
  }

  private static void assertFactoryBoundaries(
      Map<String, ASTMethodCall> calls, VerifiedConstruction proof) {
    var names =
        List.of(
            "caseEvaluatedStatic", "caseInstanceFactory", "caseInstanceShared", "caseSharedStatic");
    assertCalls(calls, proof, names, true, true);
    for (String name : names) {
      var setter = assertInstanceOf(ASTMethodCall.class, calls.get(name).getQualifier());
      assertTrue(proof.isConstructionStep(setter.getMethodType()), name);
      assertTrue(proof.hasExactReceiver(setter), name);
      var factory = assertInstanceOf(ASTMethodCall.class, setter.getQualifier());
      assertFalse(factory.getOverloadSelectionInfo().isFailed(), name);
      assertEquals(
          name.equals("caseEvaluatedStatic"),
          proof.isConstructionStep(factory.getMethodType()),
          name + " body");
      assertEquals(
          !name.equals("caseEvaluatedStatic"), proof.hasExactReceiver(factory), name + " dispatch");
      if (!name.equals("caseSharedStatic")) {
        var outer = assertInstanceOf(ASTConstructorCall.class, factory.getQualifier());
        assertFalse(proof.isBuilderAllocation(outer), name + " outer allocation");
      } else {
        assertTrue(
            factory.descendants(ASTConstructorCall.class).isEmpty(),
            "shared object is not an allocation");
      }
    }
  }

  private static void assertAllocations(
      Map<String, ASTConstructorCall> allocations,
      VerifiedConstruction proof,
      Map<String, Boolean> expected) {
    assertEquals(expected.keySet(), allocations.keySet());
    expected.forEach(
        (name, accepted) -> {
          var allocation = allocations.get(name);
          assertFalse(allocation.getOverloadSelectionInfo().isFailed(), name);
          assertEquals(accepted, proof.isBuilderAllocation(allocation), name);
        });
  }

  private static Map<String, ASTConstructorCall> allocations(String source, ClassLoader loader)
      throws Exception {
    return parse(source, loader)
        .descendants(ASTConstructorCall.class)
        .crossFindBoundaries()
        .toList()
        .stream()
        .filter(
            call -> {
              var method = call.ancestors(ASTMethodDeclaration.class).first();
              return method != null && method.getName().startsWith("allocation");
            })
        .collect(
            Collectors.toMap(
                call -> call.ancestors(ASTMethodDeclaration.class).first().getName(),
                Function.identity()));
  }

  private static String initialize(String owner, String initializer) {
    return FIXTURE.replace("class " + owner + " {", "class " + owner + " { " + initializer);
  }

  private static Map<String, JMethodSig> binaryMethods(ClassLoader loader) {
    var types = TypeSystem.usingClassLoaderClasspath(loader);
    var symbol = types.getClassSymbol("fixtures.Constructions");
    assertNotNull(symbol);
    Map<String, JMethodSig> result = new HashMap<>();
    collectMethods(symbol, result);
    return result;
  }

  private static void collectMethods(JClassSymbol owner, Map<String, JMethodSig> methods) {
    owner
        .getDeclaredMethods()
        .forEach(
            method -> {
              var signature = method.getGenericSignature();
              methods.put(key(signature), signature);
            });
    owner.getDeclaredClasses().forEach(nested -> collectMethods(nested, methods));
  }

  private static String key(JMethodSig method) {
    String parameters =
        method.getErasure().getFormalParameters().stream()
            .map(type -> type.getSymbol().getSimpleName())
            .collect(Collectors.joining(","));
    return method.getSymbol().getEnclosingClass().getSimpleName()
        + "."
        + method.getName()
        + "("
        + parameters
        + ")";
  }

  private static ResourceOnlyLoader compileGenerated(String source) throws IOException {
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler);
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
      var input =
          new SimpleJavaFileObject(
              URI.create("string:///Constructions.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
              return source;
            }
          };
      var options =
          List.of(
              "--release",
              "24",
              "-classpath",
              System.getProperty("java.class.path"),
              "-processor",
              "lombok.launch.AnnotationProcessorHider$AnnotationProcessor");
      assertTrue(
          compiler.getTask(null, files, diagnostics, options, null, List.of(input)).call(),
          diagnostics.getDiagnostics().toString());
    }
    Map<String, byte[]> classes = new HashMap<>();
    outputs.forEach((name, bytes) -> classes.put(name, bytes.toByteArray()));
    return new ResourceOnlyLoader(classes);
  }

  @Test
  void provesOnlyLinkedSourceStepsWithoutNamesOrAnnotations() throws Exception {
    var methods = sourceMethods(FIXTURE);
    var proof = new VerifiedConstruction(this.getClass().getClassLoader());
    assertDecisions(methods, proof, ACCEPTED, true);
    assertDecisions(methods, proof, REJECTED, false);
  }

  @Test
  void provesLombokShapedClassfilesWithoutApplicationClassLoading() throws Exception {
    try (var loader = this.compile(FIXTURE)) {
      var methods = binaryMethods(loader);
      assertEquals(methods.keySet(), sourceMethods(FIXTURE).keySet(), "all nested declarations");
      var proof = new VerifiedConstruction(loader);
      assertDecisions(methods, proof, ACCEPTED, true);
      assertDecisions(methods, proof, REJECTED, false);
      assertEquals(0, loader.applicationLoads);
      assertTrue(loader.resourceReads > 0);
    }
  }

  @Test
  void sourceStepsCanUseTypedConstructorEvidenceFromClassfiles() throws Exception {
    var source =
        """
        package fixtures;
        final class Mixed {
          String text;
          long sequence;
          double precise;
          public static Constructions.ProductBuilder start() {
            return new Constructions.ProductBuilder();
          }
          public Mixed text(String value) { text = value; return this; }
          public Mixed sequence(long value) { sequence = value; return this; }
          public Mixed precise(double value) { precise = value; return this; }
          public Constructions.Product finish() {
            return new Constructions.Product(text, sequence, precise);
          }
        }
        """;
    try (var loader = this.compile(FIXTURE)) {
      assertDecisions(
          sourceMethods(source, loader),
          new VerifiedConstruction(loader),
          List.of(
              "Mixed.start()",
              "Mixed.text(String)",
              "Mixed.sequence(long)",
              "Mixed.precise(double)",
              "Mixed.finish()"),
          true);
      assertEquals(0, loader.applicationLoads);
    }
  }

  @Test
  void renamingTheProtocolPreservesSourceAndBytecodeDecisions() throws Exception {
    Function<String, String> rename =
        text ->
            text.replace("ProductBuilder", "Composer")
                .replace("start", "open")
                .replace("build", "seal")
                .replace("text", "input");
    var source = rename.apply(FIXTURE);
    var accepted = ACCEPTED.stream().map(rename).toList();
    var rejected = REJECTED.stream().map(rename).toList();
    var proof = new VerifiedConstruction(this.getClass().getClassLoader());
    assertDecisions(sourceMethods(source), proof, accepted, true);
    assertDecisions(sourceMethods(source), proof, rejected, false);
    try (var loader = this.compile(source)) {
      assertDecisions(binaryMethods(loader), new VerifiedConstruction(loader), accepted, true);
      assertDecisions(binaryMethods(loader), new VerifiedConstruction(loader), rejected, false);
      assertEquals(0, loader.applicationLoads);
    }
  }

  @Test
  void resolvedOverloadsAreProvedIndependentlyAtActualSourceCallSites() throws Exception {
    var source =
        FIXTURE.replace(
            "static ProductBuilder cached;",
            """
        static ProductBuilder cached;
        void calls(ProductBuilder builder, String text, CharSequence other) {
          builder.text(text);
          builder.text(other);
        }
        """);
    var properties = new JavaLanguageProperties();
    properties.setLanguageVersion("24");
    var processor = new JavaLanguageProcessor(properties);
    try (var registry = LanguageProcessorRegistry.singleton(processor);
        var document = TextDocument.readOnlyString(source, processor.getLanguageVersion())) {
      var root =
          processor
              .getParser()
              .parse(new ParserTask(document, SemanticErrorReporter.noop(), registry));
      var caller =
          root.descendants(ASTMethodDeclaration.class)
              .first(method -> method.getName().equals("calls"));
      assertNotNull(caller);
      var calls = caller.descendants(ASTMethodCall.class).toList();
      assertEquals(2, calls.size());
      var proof = new VerifiedConstruction(this.getClass().getClassLoader());
      assertFalse(calls.get(0).getOverloadSelectionInfo().isFailed());
      assertFalse(calls.get(1).getOverloadSelectionInfo().isFailed());
      assertTrue(proof.isConstructionStep(calls.get(0).getMethodType()));
      assertFalse(proof.isConstructionStep(calls.get(1).getMethodType()));
    }
  }

  @Test
  void sourceDispatchRequiresAFixedTargetOrAnEntirelyFreshFluentChain() throws Exception {
    var calls = calls(FIXTURE + CALLS, this.getClass().getClassLoader());
    var proof = new VerifiedConstruction(this.getClass().getClassLoader());
    assertCalls(calls, proof, EXACT_CALLS, true, true);
    assertCalls(calls, proof, INEXACT_CALLS, true, false);
    assertCalls(calls, proof, List.of("casePrivate"), true, true);
    assertCalls(calls, proof, List.of("caseBusiness"), false, true);
    var methods = sourceMethods(FIXTURE);
    assertFalse(proof.isConstructionStep(methods.get("Malicious.text(String)")));
    assertFalse(proof.isConstructionStep(methods.get("Malicious.build()")));
  }

  @Test
  void binaryDispatchUsesTheSameFreshnessAndFinalMethodRulesWithoutClassLoading() throws Exception {
    try (var loader = this.compile(FIXTURE + CALLS)) {
      var calls = calls("package fixtures; " + CALLS, loader);
      var proof = new VerifiedConstruction(loader);
      assertCalls(calls, proof, EXACT_CALLS, true, true);
      assertCalls(calls, proof, INEXACT_CALLS, true, false);
      assertCalls(calls, proof, List.of("caseBusiness"), false, true);
      var methods = binaryMethods(loader);
      assertFalse(proof.isConstructionStep(methods.get("Malicious.text(String)")));
      assertFalse(proof.isConstructionStep(methods.get("Malicious.build()")));
      assertEquals(0, loader.applicationLoads);
    }
  }

  @Test
  void compiledDispatchFlagsAreReadFreshRatherThanTrustedFromOldPmdSymbols() throws Exception {
    try (var loader = this.compile(FIXTURE)) {
      var calls = calls("package fixtures; " + CALLS, loader);
      var proof = new VerifiedConstruction(loader);
      var finalMethod = calls.get("caseFinalMethod");
      var finalOwner = calls.get("caseFinalParameter");
      assertTrue(proof.hasExactReceiver(finalMethod));
      assertTrue(proof.hasExactReceiver(finalOwner));
      var changed =
          FIXTURE
              .replace("public final ProductBuilder fixedText", "public ProductBuilder fixedText")
              .replace(
                  "public static final class FinalComposer", "public static class FinalComposer");
      try (var ignored = this.compile(changed)) {
        assertFalse(proof.hasExactReceiver(finalMethod));
        assertFalse(proof.hasExactReceiver(finalOwner));
        assertEquals(0, loader.applicationLoads);
      }
    }
  }

  @Test
  void receiverProofRejectsUnresolvedCalls() throws Exception {
    var calls =
        calls(
            """
        class Missing {
          Object caseMissing(Unknown value) { return value.noSuchMethod(); }
        }
        """,
            this.getClass().getClassLoader());
    var call = calls.get("caseMissing");
    assertNotNull(call);
    assertFalse(new VerifiedConstruction(this.getClass().getClassLoader()).hasExactReceiver(call));
  }

  @Test
  void recoveredBindingsProveTheEntireRealLombokChainInTheSameSourceUnit() throws Exception {
    try (var loader = compileGenerated(GENERATED_SNAPSHOT)) {
      var call = calls(GENERATED_SNAPSHOT, loader).get("caseFresh");
      var proof = new VerifiedConstruction(loader);
      var invocations = new CompiledInvocations(loader);
      assertTrue(
          call.getOverloadSelectionInfo().isFailed(),
          "same-unit generated members must need recovery");
      assertTrue(proof.isConstructionStep(invocations.resolve(call).orElseThrow()));
      assertFalse(proof.hasExactReceiver(call), "the default API has no recovered bindings");
      Function<InvocationNode, Optional<JMethodSig>> recovered = invocations::resolve;
      var steps = 0;
      for (var link = call;
          link != null;
          link = link.getQualifier() instanceof ASTMethodCall parent ? parent : null) {
        var signature =
            link.getOverloadSelectionInfo().isFailed()
                ? recovered.apply(link).orElseThrow()
                : link.getMethodType();
        assertTrue(proof.isConstructionStep(signature), link.getMethodName());
        assertTrue(proof.hasExactReceiver(link, recovered), link.getMethodName());
        steps++;
      }
      assertEquals(5, steps, "factory, three linked setters and terminal");
      assertEquals(0, loader.applicationLoads);
    }
  }

  @Test
  void recoveredBindingsDoNotMakeVariablesCastsOrAliasesFresh() throws Exception {
    try (var loader = compileGenerated(GENERATED_SNAPSHOT)) {
      var sites = calls(GENERATED_SNAPSHOT, loader);
      var proof = new VerifiedConstruction(loader);
      var invocations = new CompiledInvocations(loader);
      for (String name : List.of("caseVariable", "caseCast", "caseAlias")) {
        assertFalse(proof.hasExactReceiver(sites.get(name), invocations::resolve), name);
      }
      assertEquals(0, loader.applicationLoads);
    }
  }

  @Test
  void originalSuccessfulBindingsNeverConsultTheRecoveryProvider() throws Exception {
    var loader = this.getClass().getClassLoader();
    var sites = calls(FIXTURE + CALLS, loader);
    var proof = new VerifiedConstruction(loader);
    Function<InvocationNode, Optional<JMethodSig>> forbidden =
        invocation -> {
          throw new AssertionError("PMD already resolved " + invocation.getMethodName());
        };
    for (String name : EXACT_CALLS) {
      assertTrue(proof.hasExactReceiver(sites.get(name), forbidden), name);
    }
    for (String name : INEXACT_CALLS) {
      assertFalse(proof.hasExactReceiver(sites.get(name), forbidden), name);
    }
    var allocation =
        assertInstanceOf(ASTConstructorCall.class, sites.get("caseNew").getQualifier());
    assertTrue(proof.isBuilderAllocation(allocation, forbidden));
  }

  @Test
  void recoveredGeneratedAllocatorsAndProductsStillNeedMechanicalBodiesAndInitialization()
      throws Exception {
    var variants =
        List.of(
            GENERATED_SNAPSHOT.replace(
                "public static class Snapshot {",
                "public static class Snapshot { static { System.nanoTime(); }"),
            GENERATED_SNAPSHOT.replace(
                "String field;",
                """
                               String field;
                               public static class SnapshotBuilder {
                                 SnapshotBuilder() { System.nanoTime(); }
                               }
                               """),
            GENERATED_SNAPSHOT.replace(
                "String field;",
                """
                               String field;
                               public static class SnapshotBuilder {
                                 static { System.nanoTime(); }
                               }
                               """),
            GENERATED_SNAPSHOT.replace(
                "String field;",
                """
                               String field;
                               Snapshot(String field, java.util.List<String> names, int count) {
                                 this.field = field.trim(); this.names = names; this.count = count;
                               }
                               """));
    for (String source : variants) {
      try (var loader = compileGenerated(source)) {
        var call = calls(source, loader).get("caseFresh");
        var invocations = new CompiledInvocations(loader);
        assertTrue(invocations.resolve(call).isPresent(), "typing recovery is not a purity proof");
        assertFalse(new VerifiedConstruction(loader).hasExactReceiver(call, invocations::resolve));
        assertEquals(0, loader.applicationLoads);
      }
    }
  }

  @Test
  void recoveringAConstructorDoesNotExemptItsBodyOrChangeIndependentMethodProofs()
      throws Exception {
    var source =
        """
        package fixtures;
        public class Constructions {
          @lombok.AllArgsConstructor public static class Composer {
            String field;
            Composer text(String value) { field = value; return this; }
            Product build() { return new Product(field); }
          }
          static final class Product {
            final String field;
            Product(String field) { this.field = field; }
          }
          Composer allocationGenerated() { return new Composer("value"); }
          Product caseGenerated() { return new Composer("value").text("other").build(); }
        }
        """;
    for (boolean impure : List.of(false, true)) {
      var fixture =
          impure
              ? source.replace(
                  "public static class Composer {",
                  "public static class Composer { static { System.nanoTime(); }")
              : source;
      try (var loader = compileGenerated(fixture)) {
        var allocation = allocations(fixture, loader).get("allocationGenerated");
        assertTrue(allocation.getOverloadSelectionInfo().isFailed());
        var proof = new VerifiedConstruction(loader);
        var invocations = new CompiledInvocations(loader);
        assertTrue(invocations.resolve(allocation).isPresent());
        assertFalse(proof.isBuilderAllocation(allocation));
        assertEquals(!impure, proof.isBuilderAllocation(allocation, invocations::resolve));
        var call = calls(fixture, loader).get("caseGenerated");
        assertTrue(
            proof.isConstructionStep(call.getMethodType()),
            "builder initialization is independent of the body");
        assertEquals(!impure, proof.hasExactReceiver(call, invocations::resolve));
        assertEquals(0, loader.applicationLoads);
      }
    }
  }

  @Test
  void recoveredProtocolsStillDoNotDependOnBuilderNames() throws Exception {
    var source =
        GENERATED_SNAPSHOT
            .replace(
                "@lombok.Builder",
                """
        @lombok.Builder(builderMethodName = "open", buildMethodName = "seal", builderClassName = "Composer")
        """)
            .replace("SnapshotBuilder", "Composer")
            .replace(".builder()", ".open()")
            .replace(".build()", ".seal()");
    try (var loader = compileGenerated(source)) {
      var call = calls(source, loader).get("caseFresh");
      var invocations = new CompiledInvocations(loader);
      assertTrue(new VerifiedConstruction(loader).hasExactReceiver(call, invocations::resolve));
      assertEquals(0, loader.applicationLoads);
    }
  }

  @Test
  void aProviderCannotSubstituteADifferentNameOwnerArityArgumentTypeOrStaticFlag()
      throws Exception {
    var alternatives =
        List.of(
            "public SnapshotBuilder build(String value) { return this; }",
            "public String build() { return \"wrong result\"; }",
            "public SnapshotBuilder count(String value) { return this; }",
            "public static SnapshotBuilder count(int value) { return new SnapshotBuilder(); }",
            "public SnapshotBuilder names(java.util.List<Integer> value) { return this; }",
            "public SnapshotBuilder missing() { return this; }");
    try (var loader = compileGenerated(GENERATED_SNAPSHOT)) {
      var call = calls(GENERATED_SNAPSHOT, loader).get("caseFresh");
      var proof = new VerifiedConstruction(loader);
      var good = new CompiledInvocations(loader);
      for (String alternative : alternatives) {
        var wrongSource =
            "package fixtures; public class Constructions { static class Snapshot {"
                + " static class SnapshotBuilder { "
                + alternative
                + " } } }";
        try (var wrong = compileGenerated(wrongSource)) {
          var candidate = binaryMethods(wrong).values().iterator().next();
          String targetName = candidate.getName().equals("missing") ? "build" : candidate.getName();
          var target = chainCall(call, targetName);
          assertFalse(
              proof.hasExactReceiver(
                  call, node -> node == target ? Optional.of(candidate) : good.resolve(node)),
              alternative);
          assertEquals(0, wrong.applicationLoads);
        }
      }
      try (var foreign = this.compile(FIXTURE)) {
        var candidate = binaryMethods(foreign).get("ProductBuilder.build()");
        assertFalse(
            proof.hasExactReceiver(
                call, node -> node == call ? Optional.of(candidate) : good.resolve(node)),
            "different binary owner");
      }
      assertEquals(0, loader.applicationLoads);
    }
  }

  @Test
  void matchingNamesAndArityCannotHideIncompatibleActualArguments() throws Exception {
    try (var loader = compileGenerated(GENERATED_SNAPSHOT)) {
      var valid = calls(GENERATED_SNAPSHOT, loader).get("caseFresh");
      var resolver = new CompiledInvocations(loader);
      Map<String, JMethodSig> signatures = new HashMap<>();
      for (var link = valid;
          link != null;
          link = link.getQualifier() instanceof ASTMethodCall parent ? parent : null) {
        signatures.put(link.getMethodName(), resolver.resolve(link).orElseThrow());
      }
      for (String source :
          List.of(
              GENERATED_SNAPSHOT.replace(".count(1)", ".count(\"wrong\")"),
              GENERATED_SNAPSHOT.replace("java.util.List.of()", "java.util.List.of(1)"),
              GENERATED_SNAPSHOT.replace("java.util.List.of()", "java.util.List.<Integer>of()"))) {
        var call = calls(source, loader).get("caseFresh");
        var proof = new VerifiedConstruction(loader);
        assertFalse(
            proof.hasExactReceiver(
                call, node -> Optional.ofNullable(signatures.get(node.getMethodName()))));
      }
      assertEquals(0, loader.applicationLoads);
    }
  }

  @Test
  void recoveryKeepsCurrentSourceInitializationAndConstructorEvidenceAndResetsBetweenQueries()
      throws Exception {
    try (var loader = compileGenerated(GENERATED_SNAPSHOT)) {
      var proof = new VerifiedConstruction(loader);
      var resolver = new CompiledInvocations(loader);
      var variants =
          List.of(
              GENERATED_SNAPSHOT.replace(
                  "public static class Snapshot {",
                  "public static class Snapshot { static { System.nanoTime(); }"),
              GENERATED_SNAPSHOT.replace(
                  "String field;",
                  """
                                 String field;
                                 Snapshot(String field, java.util.List<String> names, int count) {
                                   System.nanoTime(); this.field = field; this.names = names; this.count = count;
                                 }
                                 """));
      for (String source : variants) {
        var call = calls(source, loader).get("caseFresh");
        assertTrue(
            resolver.resolve(call).isPresent(),
            "recovered typing cannot override current source bodies");
        assertFalse(proof.hasExactReceiver(call, resolver::resolve));
        assertTrue(
            proof.hasExactReceiver(
                calls(GENERATED_SNAPSHOT, loader).get("caseFresh"), resolver::resolve));
      }
      assertEquals(0, loader.applicationLoads);
    }
  }

  @Test
  void aProviderCannotSelectOneOfTwoApplicableOverloadsOrAnInaccessibleMethod() throws Exception {
    var compiled =
        """
        package fixtures;
        public class Constructions {
          public static String open(String value) { return value; }
          public static String open(Integer value) { return "integer"; }
          private static String hidden() { return "private"; }
        }
        """;
    try (var loader = compileGenerated(compiled)) {
      var methods = binaryMethods(loader);
      var sites =
          calls(
              """
          package fixtures;
          public class Constructions {}
          class Caller {
            String caseAmbiguous() { return Constructions.open(null); }
            String caseInaccessible() { return Constructions.hidden(); }
          }
          """,
              loader);
      var offered =
          Map.of(
              "caseAmbiguous",
              methods.get("Constructions.open(String)"),
              "caseInaccessible",
              methods.get("Constructions.hidden()"));
      offered.forEach(
          (name, signature) -> {
            var call = sites.get(name);
            assertTrue(call.getOverloadSelectionInfo().isFailed(), name);
            assertFalse(
                new VerifiedConstruction(loader)
                    .hasExactReceiver(call, node -> Optional.of(signature)),
                name);
          });
      assertEquals(0, loader.applicationLoads);
    }
  }

  @Test
  void freshReceiversRejectImpureConstructors() throws Exception {
    var source = FIXTURE.replace("ProductBuilder() {}", "ProductBuilder() { Repository.save(); }");
    assertCalls(
        calls(source + CALLS, this.getClass().getClassLoader()),
        new VerifiedConstruction(this.getClass().getClassLoader()),
        List.of("caseNew", "caseNewChain"),
        true,
        false);
    try (var loader = this.compile(source)) {
      assertCalls(
          calls("package fixtures; " + CALLS, loader),
          new VerifiedConstruction(loader),
          List.of("caseNew", "caseNewChain"),
          true,
          false);
    }
  }

  @Test
  void finalSourceDispatchDoesNotProveBuilderAllocation() throws Exception {
    var source = ALLOCATION_TYPES.replace("B() {}", "B() { System.nanoTime(); }");
    var loader = this.getClass().getClassLoader();
    assertFinalAllocation(
        calls(source + ALLOCATION_CALLS, loader), new VerifiedConstruction(loader), false);
  }

  @Test
  void finalBinaryDispatchDoesNotProveBuilderAllocation() throws Exception {
    var source = ALLOCATION_TYPES.replace("B() {}", "B() { System.nanoTime(); }");
    try (var loader = this.compile(source)) {
      assertFinalAllocation(
          calls("package fixtures; " + ALLOCATION_CALLS, loader),
          new VerifiedConstruction(loader),
          false);
      assertEquals(0, loader.applicationLoads);
      assertTrue(loader.resourceReads > 0);
    }
  }

  @Test
  void builderAllocationsRequireTheSelectedMechanicalConstructor() throws Exception {
    var source =
        ALLOCATION_TYPES.replace(
            "B() {}",
            """
        B() {}
        B(String value) { field = value; }
        B(long value) { System.nanoTime(); }
        B(String... values) {}
        """);
    assertAllocations(
        source,
        """
                          class AllocationSites {
                            static native String prepared();
                            B allocationEmpty() { return new B(); }
                            B allocationWired(String value) { return new B(value); }
                            B allocationEvaluatedArgument() { return new B(prepared()); }
                            B allocationImpure() { return new B(1L); }
                            B allocationVarargs() { return new B("first", "second"); }
                          }
                          """,
        Map.of(
            "allocationEmpty",
            true,
            "allocationWired",
            true,
            "allocationEvaluatedArgument",
            true,
            "allocationImpure",
            false,
            "allocationVarargs",
            false));
  }

  @Test
  void pureFinalAllocationsAndImplicitConstructorsRetainIndependentMethodProofs() throws Exception {
    for (String source :
        List.of(
            ALLOCATION_TYPES,
            ALLOCATION_TYPES.replace("B() {}", ""),
            ALLOCATION_TYPES.replace("B() {}", "B() { super(); }"),
            ALLOCATION_TYPES.replace(
                "B() {}", "B() {} static final String KEY = \"value\"; static {}"))) {
      var parent = this.getClass().getClassLoader();
      assertFinalAllocation(
          calls(source + ALLOCATION_CALLS, parent), new VerifiedConstruction(parent), true);
      try (var loader = this.compile(source + ALLOCATION_CALLS)) {
        assertFinalAllocation(
            calls("package fixtures; " + ALLOCATION_CALLS, loader),
            new VerifiedConstruction(loader),
            true);
        assertEquals(0, loader.applicationLoads);
      }
    }
    assertAllocations(
        ALLOCATION_TYPES.replace("B", "Composer"),
        """
        class AllocationSites {
          Composer allocationRenamed() { return new Composer(); }
        }
        """,
        Map.of("allocationRenamed", true));
  }

  @Test
  void builderInitializationCannotUseFinalDispatchToBypassAllocationProof() throws Exception {
    for (String declaration :
        List.of(
            "static { System.nanoTime(); }",
            "static final long CLOCK = System.nanoTime();",
            "{ System.nanoTime(); }",
            "private long clock = System.nanoTime();")) {
      var source = ALLOCATION_TYPES.replace("B() {}", "B() {} " + declaration);
      var parent = this.getClass().getClassLoader();
      assertFinalAllocation(
          calls(source + ALLOCATION_CALLS, parent), new VerifiedConstruction(parent), false);
      try (var loader = this.compile(source + ALLOCATION_CALLS)) {
        assertFinalAllocation(
            calls("package fixtures; " + ALLOCATION_CALLS, loader),
            new VerifiedConstruction(loader),
            false);
        assertEquals(0, loader.applicationLoads);
      }
    }
  }

  @Test
  void mechanicalAllocationNeedsALinkedProtocol() throws Exception {
    var variants =
        List.of(
            ALLOCATION_TYPES.replace("P terminal() { return new P(field); }", ""),
            ALLOCATION_TYPES.replace("B setter(String value) { field = value; return this; }", ""),
            ALLOCATION_TYPES
                .replace("private String field;", "private String field; private String other;")
                .replace("new P(field)", "new P(other)"),
            ALLOCATION_TYPES.replace(
                "P terminal() { return new P(field); }", "B terminal() { return new B(); }"),
            ALLOCATION_TYPES.replace(
                "P(String value) { field = value; }",
                "P(String value) { field = value; System.nanoTime(); }"));
    for (String source : variants) {
      assertAllocations(
          source,
          """
          class AllocationSites {
            B allocationDisconnected() { return new B(); }
            P allocationProduct() { return new P("value"); }
          }
          """,
          Map.of("allocationDisconnected", false, "allocationProduct", false));
    }
  }

  @Test
  void anonymousQualifiedAndInheritedAllocationsCannotStandForTheProtocolOwner() throws Exception {
    var source =
        ALLOCATION_TYPES.replace("final class B", "class B")
            + """
        final class Child extends B {}
        final class Outer {
          final class Inner {
            private String field;
            Inner setter(String value) { field = value; return this; }
            P terminal() { return new P(field); }
          }
        }
        """;
    assertAllocations(
        source,
        """
                          class AllocationSites {
                            B allocationExact() { return new B(); }
                            B allocationAnonymous() { return new B() {}; }
                            Child allocationSubtype() { return new Child(); }
                            Outer.Inner allocationQualified(Outer outer) { return outer.new Inner(); }
                          }
                          """,
        Map.of(
            "allocationExact",
            true,
            "allocationAnonymous",
            false,
            "allocationSubtype",
            false,
            "allocationQualified",
            false));
    for (String declaration :
        List.of(
            "class Parent {} final class B extends Parent {",
            "interface Startup { default void hook() {} }"
                + " final class B implements Startup {")) {
      assertAllocations(
          ALLOCATION_TYPES.replace("final class B {", declaration),
          """
          class AllocationSites {
            B allocationInheritedInitialization() { return new B(); }
          }
          """,
          Map.of("allocationInheritedInitialization", false));
    }
  }

  @Test
  void exactFinalCallsDoNotTurnOuterAllocationsOrSharedFactoriesIntoBuilderAllocations()
      throws Exception {
    var source =
        ALLOCATION_TYPES
            + """
        final class Origin {
          Origin() { System.nanoTime(); }
          static B cached;
          static B open() { return new B(); }
          B create() { return new B(); }
          B existing() { return cached; }
          static B shared() { return cached; }
        }
        """;
    var sites =
        """
        class BoundarySites {
          P caseEvaluatedStatic() { return new Origin().open().setter("value").terminal(); }
          P caseInstanceFactory() { return new Origin().create().setter("value").terminal(); }
          P caseInstanceShared() { return new Origin().existing().setter("value").terminal(); }
          P caseSharedStatic() { return Origin.shared().setter("value").terminal(); }
        }
        """;
    var parent = this.getClass().getClassLoader();
    assertFactoryBoundaries(calls(source + sites, parent), new VerifiedConstruction(parent));
    try (var loader = this.compile(source + sites)) {
      assertFactoryBoundaries(
          calls("package fixtures; " + sites, loader), new VerifiedConstruction(loader));
      assertEquals(0, loader.applicationLoads);
    }
  }

  @Test
  void allocationProofDoesNotDecideWhetherACallbackReturnsFromItsContainingMethod()
      throws Exception {
    assertAllocations(
        ALLOCATION_TYPES,
        """
                          class ScopeSites {
                            java.util.function.Supplier<B> allocationExpression() { return () -> new B(); }
                            java.util.function.Supplier<B> allocationBlock() { return () -> { return new B(); }; }
                            Object allocationContainer() {
                              return new Object() {
                                B allocationMethod() { return new B(); }
                              };
                            }
                          }
                          """,
        Map.of(
            "allocationExpression",
            true,
            "allocationBlock",
            true,
            "allocationContainer",
            false,
            "allocationMethod",
            true));
  }

  @Test
  void allocationEvidenceIsReadFreshAndSourceEvidenceTakesPrecedence() throws Exception {
    var sites =
        """
        class AllocationSites {
          B allocationFresh() { return new B(); }
        }
        """;
    try (var loader = this.compile(ALLOCATION_TYPES)) {
      var allocation = allocations("package fixtures; " + sites, loader).get("allocationFresh");
      var proof = new VerifiedConstruction(loader);
      assertTrue(proof.isBuilderAllocation(allocation));
      var impure = ALLOCATION_TYPES.replace("B() {}", "B() { System.nanoTime(); }");
      try (var updated = this.compile(impure)) {
        assertFalse(
            proof.isBuilderAllocation(allocation),
            "old PMD symbols do not preserve old constructor evidence");
        assertEquals(0, updated.applicationLoads);
      }
      var reads = loader.resourceReads;
      var parent = this.getClass().getClassLoader();
      assertTrue(
          proof.isBuilderAllocation(
              allocations(ALLOCATION_TYPES + sites, parent).get("allocationFresh")));
      assertFalse(
          proof.isBuilderAllocation(allocations(impure + sites, parent).get("allocationFresh")));
      assertEquals(
          reads, loader.resourceReads, "source proof must not fall back to conflicting classfiles");
      assertEquals(0, loader.applicationLoads);
    }
  }

  @Test
  void allocationProofRejectsUnresolvedConstructorCalls() throws Exception {
    var loader = this.getClass().getClassLoader();
    var allocation =
        allocations(
                """
        class Missing {
          Object allocationMissing() { return new Unknown(); }
        }
        """,
                loader)
            .get("allocationMissing");
    assertNotNull(allocation);
    assertFalse(new VerifiedConstruction(loader).isBuilderAllocation(allocation));
  }

  @Test
  void initializerCallsOnFactoryBuilderOrProductCannotHideInAConstructionProof() throws Exception {
    for (String owner : List.of("Constructions", "ProductBuilder", "Product")) {
      var source = initialize(owner, "static { Repository.save(); }");
      var methods = sourceMethods(source);
      var proof = new VerifiedConstruction(this.getClass().getClassLoader());
      assertFalse(proof.isConstructionStep(methods.get("Constructions.start()")), owner);
      assertEquals(
          !owner.equals("Product"),
          proof.isConstructionStep(methods.get("ProductBuilder.build()")),
          owner);
      assertFalse(
          proof.hasExactReceiver(
              calls(source + CALLS, this.getClass().getClassLoader()).get("caseFactoryChain")),
          owner);
      try (var loader = this.compile(source)) {
        var compiled = binaryMethods(loader);
        var binaryProof = new VerifiedConstruction(loader);
        assertFalse(binaryProof.isConstructionStep(compiled.get("Constructions.start()")), owner);
        assertEquals(
            !owner.equals("Product"),
            binaryProof.isConstructionStep(compiled.get("ProductBuilder.build()")),
            owner);
        assertFalse(
            binaryProof.hasExactReceiver(
                calls("package fixtures; " + CALLS, loader).get("caseFactoryChain")),
            owner);
        assertEquals(0, loader.applicationLoads);
      }
    }
  }

  @Test
  void nonconstantStaticFieldsAreRejectedButConstantVariablesAndEmptyBlocksAreSafe()
      throws Exception {
    var cases =
        Map.of(
            "static final String MARK = Repository.unknown(\"value\");",
            false,
            "static final String MARK = \"constant\"; static {}",
            true);
    for (var entry : cases.entrySet()) {
      var source = initialize("ProductBuilder", entry.getKey());
      var sourceProof = new VerifiedConstruction(this.getClass().getClassLoader());
      assertEquals(
          entry.getValue(),
          sourceProof.isConstructionStep(sourceMethods(source).get("Constructions.start()")));
      try (var loader = this.compile(source)) {
        var binaryProof = new VerifiedConstruction(loader);
        assertEquals(
            entry.getValue(),
            binaryProof.isConstructionStep(binaryMethods(loader).get("Constructions.start()")));
      }
    }
  }

  @Test
  void allocatingAStaticNestedBuilderDoesNotInitializeItsEnclosingFactoryClass() throws Exception {
    var source = initialize("Constructions", "static { Repository.save(); }");
    assertCalls(
        calls(source + CALLS, this.getClass().getClassLoader()),
        new VerifiedConstruction(this.getClass().getClassLoader()),
        List.of("caseNewChain"),
        true,
        true);
    try (var loader = this.compile(source)) {
      assertCalls(
          calls("package fixtures; " + CALLS, loader),
          new VerifiedConstruction(loader),
          List.of("caseNewChain"),
          true,
          true);
      assertEquals(0, loader.applicationLoads);
    }
  }

  @Test
  void initializationThroughParentsOrDefaultInterfacesIsConservativelyRejected() throws Exception {
    for (String clause : List.of("extends Parent", "implements Startup")) {
      var source =
          FIXTURE.replace(
              "public static class ProductBuilder {",
              """
          public static class Parent { static { Repository.save(); } }
          public interface Startup {
            String EVENT = Repository.unknown("initialization");
            default void hook() {}
          }
          public static class ProductBuilder
          """
                  + clause
                  + " {");
      var sourceProof = new VerifiedConstruction(this.getClass().getClassLoader());
      assertFalse(
          sourceProof.isConstructionStep(sourceMethods(source).get("Constructions.start()")));
      try (var loader = this.compile(source)) {
        var binaryProof = new VerifiedConstruction(loader);
        assertFalse(
            binaryProof.isConstructionStep(binaryMethods(loader).get("Constructions.start()")));
      }
    }
  }

  @Test
  void receiverChainsAreBoundedAndDoNotPoisonSubsequentQueries() throws Exception {
    var shortChain = "new Constructions.ProductBuilder()" + ".text(\"value\")".repeat(31);
    var source =
        "class Chains { static Constructions.Product caseBounded() { return "
            + shortChain
            + ".build(); } static Constructions.Product caseTooLong() { return "
            + shortChain
            + ".text(\"value\").build(); } }";
    var proof = new VerifiedConstruction(this.getClass().getClassLoader());
    var sourceCalls = calls(FIXTURE + source, this.getClass().getClassLoader());
    assertFalse(proof.hasExactReceiver(sourceCalls.get("caseTooLong")));
    assertTrue(proof.hasExactReceiver(sourceCalls.get("caseBounded")));
    try (var loader = this.compile(FIXTURE)) {
      var binaryCalls = calls("package fixtures; " + source, loader);
      var binaryProof = new VerifiedConstruction(loader);
      assertFalse(binaryProof.hasExactReceiver(binaryCalls.get("caseTooLong")));
      assertTrue(binaryProof.hasExactReceiver(binaryCalls.get("caseBounded")));
    }
  }

  @Test
  void productConstructorCallsComputationsAndInitializersInvalidateTheProtocol() throws Exception {
    for (String replacement :
        List.of("this.text = text.trim();", "Repository.save(); this.text = text;")) {
      var source =
          FIXTURE.replace("this.text = text; this.sequence", replacement + " this.sequence");
      assertDecisions(
          sourceMethods(source),
          new VerifiedConstruction(this.getClass().getClassLoader()),
          ACCEPTED,
          false);
      try (var loader = this.compile(source)) {
        assertDecisions(binaryMethods(loader), new VerifiedConstruction(loader), ACCEPTED, false);
      }
    }
    var initialized =
        FIXTURE.replace(
            "final String text;\n    final long",
            "String text = Repository.unknown(\"seed\");\n    final long");
    var sourceProof = new VerifiedConstruction(this.getClass().getClassLoader());
    assertFalse(
        sourceProof.isConstructionStep(sourceMethods(initialized).get("ProductBuilder.build()")));
    try (var loader = this.compile(initialized)) {
      var binaryProof = new VerifiedConstruction(loader);
      assertFalse(
          binaryProof.isConstructionStep(binaryMethods(loader).get("ProductBuilder.build()")));
    }
  }

  @Test
  void factoryConstructorSideEffectsDoNotTaintIndependentMethodProofs() throws Exception {
    var source = FIXTURE.replace("ProductBuilder() {}", "ProductBuilder() { Repository.save(); }");
    var sourceProof = new VerifiedConstruction(this.getClass().getClassLoader());
    var methods = sourceMethods(source);
    assertFalse(sourceProof.isConstructionStep(methods.get("Constructions.start()")));
    assertTrue(sourceProof.isConstructionStep(methods.get("ProductBuilder.build()")));
    try (var loader = this.compile(source)) {
      var compiled = binaryMethods(loader);
      var proof = new VerifiedConstruction(loader);
      assertFalse(proof.isConstructionStep(compiled.get("Constructions.start()")));
      assertTrue(proof.isConstructionStep(compiled.get("ProductBuilder.build()")));
    }
  }

  @Test
  void sourceEvidenceWinsOverStaleClassfilesWithoutReadingThoseResources() throws Exception {
    try (var loader = this.compile(FIXTURE)) {
      var source =
          FIXTURE.replace(
              "this.text = value; return this;", "this.text = value.trim(); return this;");
      var proof = new VerifiedConstruction(loader);
      assertFalse(
          proof.isConstructionStep(sourceMethods(source).get("ProductBuilder.text(String)")));
      assertEquals(0, loader.resourceReads);
    }
  }

  @Test
  void missingResourcesAndUnresolvedCallsFailClosed() throws Exception {
    var types = TypeSystem.usingClassLoaderClasspath(this.getClass().getClassLoader());
    assertFalse(
        new VerifiedConstruction(this.getClass().getClassLoader())
            .isConstructionStep(types.UNRESOLVED_METHOD));
    try (var loader = this.compile(FIXTURE)) {
      var methods = binaryMethods(loader);
      var factory = calls("package fixtures; " + CALLS, loader).get("caseFactory");
      var allocation =
          assertInstanceOf(
              ASTConstructorCall.class,
              calls("package fixtures; " + CALLS, loader).get("caseNew").getQualifier());
      assertFalse(
          new VerifiedConstruction(new ClassLoader(null) {})
              .isConstructionStep(methods.get("Constructions.start()")));
      assertFalse(new VerifiedConstruction(new ClassLoader(null) {}).hasExactReceiver(factory));
      assertFalse(
          new VerifiedConstruction(new ClassLoader(null) {}).isBuilderAllocation(allocation));
      var hiding =
          new ClassLoader(loader) {
            @Override
            public InputStream getResourceAsStream(String name) {
              return name.endsWith("$Product.class") ? null : super.getResourceAsStream(name);
            }
          };
      assertDecisions(methods, new VerifiedConstruction(hiding), ACCEPTED, false);
      assertFalse(new VerifiedConstruction(hiding).isBuilderAllocation(allocation));
    }
  }

  @Test
  void unreadableResourcesFailVisiblyOnEveryRequest() throws Exception {
    try (var loader = this.compile(FIXTURE)) {
      var signature = binaryMethods(loader).get("Constructions.start()");
      var factory = calls("package fixtures; " + CALLS, loader).get("caseFactory");
      var allocation =
          assertInstanceOf(
              ASTConstructorCall.class,
              calls("package fixtures; " + CALLS, loader).get("caseNew").getQualifier());
      var broken =
          new ClassLoader(loader) {
            @Override
            public InputStream getResourceAsStream(String name) {
              return new InputStream() {
                @Override
                public int read() throws IOException {
                  throw new IOException("fixture read failure");
                }
              };
            }
          };
      var proof = new VerifiedConstruction(broken);
      for (var attempt = 0; attempt < 2; attempt++) {
        var error =
            assertThrows(UncheckedIOException.class, () -> proof.isConstructionStep(signature));
        assertTrue(error.getMessage().contains("fixtures/Constructions.class"));
        assertEquals("fixture read failure", error.getCause().getMessage());
        assertThrows(UncheckedIOException.class, () -> proof.hasExactReceiver(factory));
        assertThrows(UncheckedIOException.class, () -> proof.isBuilderAllocation(allocation));
      }
    }
  }

  @Test
  void corruptOrWrongOwnerResourcesFailVisiblyAndAreNotHiddenByCachedProofs() throws Exception {
    try (var loader = this.compile(FIXTURE)) {
      var signature = binaryMethods(loader).get("Constructions.start()");
      var factory = calls("package fixtures; " + CALLS, loader).get("caseFactory");
      var proof = new VerifiedConstruction(loader);
      assertTrue(proof.isConstructionStep(signature));
      assertTrue(proof.hasExactReceiver(factory));
      Files.write(this.temporary.resolve("fixtures/Constructions.class"), new byte[] {0, 1, 2, 3});
      assertThrows(IllegalArgumentException.class, () -> proof.isConstructionStep(signature));
      assertThrows(IllegalArgumentException.class, () -> proof.hasExactReceiver(factory));
      var wrong =
          new ClassLoader(loader) {
            @Override
            public InputStream getResourceAsStream(String name) {
              return super.getResourceAsStream("fixtures/Constructions$PlainValue.class");
            }
          };
      var error =
          assertThrows(
              IllegalArgumentException.class,
              () -> new VerifiedConstruction(wrong).isConstructionStep(signature));
      assertTrue(error.getMessage().contains("wrong owner"));
    }
  }

  @Test
  void structurallyParsableInvalidInstructionsFailVisibly() throws Exception {
    try (var loader = this.compile(FIXTURE)) {
      var signature = binaryMethods(loader).get("Constructions.start()");
      var descriptor = MethodTypeDesc.ofDescriptor("()Lfixtures/Constructions$ProductBuilder;");
      var invalid =
          ClassFile.of()
              .build(
                  ClassDesc.of("fixtures.Constructions"),
                  builder ->
                      builder.withMethodBody(
                          "start",
                          descriptor,
                          ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                          code -> code.iconst_0().areturn()));
      var broken =
          new ClassLoader(loader) {
            @Override
            public InputStream getResourceAsStream(String name) {
              return name.equals("fixtures/Constructions.class")
                  ? new ByteArrayInputStream(invalid)
                  : super.getResourceAsStream(name);
            }
          };
      var error =
          assertThrows(
              IllegalArgumentException.class,
              () -> new VerifiedConstruction(broken).isConstructionStep(signature));
      assertTrue(error.getMessage().contains("Invalid class resource"));
      assertTrue(error.getCause() instanceof VerifyError);
    }
  }

  @Test
  void cyclicAndOversizedEvidenceTerminatesWithoutPoisoningLaterRequests() throws Exception {
    var proof = new VerifiedConstruction(this.getClass().getClassLoader());
    var methods = sourceMethods(FIXTURE);
    var cycle =
        FIXTURE.replace(
            "Cyclic(int ignored) { Repository.save(); }", "Cyclic(int ignored) { this(); }");
    var cyclicMethods = sourceMethods(cycle);
    assertTimeout(
        Duration.ofSeconds(5),
        () -> {
          assertFalse(proof.isConstructionStep(methods.get("ProductBuilder.cycleA()")));
          assertFalse(proof.isConstructionStep(cyclicMethods.get("Constructions.cyclicFactory()")));
          assertTrue(proof.isConstructionStep(methods.get("Constructions.start()")));
        });
    var additions = new StringBuilder();
    for (var index = 0; index < 260; index++) {
      additions.append("public void extra").append(index).append("() {} ");
    }
    var source = FIXTURE.replace("ProductBuilder() {}", "ProductBuilder() {} " + additions);
    assertFalse(proof.isConstructionStep(sourceMethods(source).get("Constructions.start()")));
    assertTrue(proof.isConstructionStep(methods.get("Constructions.start()")));
    try (var loader = this.compile(source)) {
      var binaryProof = new VerifiedConstruction(loader);
      assertFalse(
          binaryProof.isConstructionStep(binaryMethods(loader).get("Constructions.start()")));
    }
  }

  private void assertAllocations(String source, String sites, Map<String, Boolean> expected)
      throws Exception {
    var parent = this.getClass().getClassLoader();
    assertAllocations(
        allocations(source + sites, parent), new VerifiedConstruction(parent), expected);
    try (var loader = this.compile(source + sites)) {
      assertAllocations(
          allocations("package fixtures; " + sites, loader),
          new VerifiedConstruction(loader),
          expected);
      assertEquals(0, loader.applicationLoads);
      assertTrue(loader.resourceReads > 0);
    }
  }

  private ResourceOnlyLoader compile(String source) throws IOException {
    var file = this.temporary.resolve("Constructions.java");
    Files.writeString(file, source);
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "Java 24 JDK required");
    assertEquals(
        0,
        compiler.run(
            null,
            null,
            null,
            "--release",
            "24",
            "-proc:none",
            "-d",
            this.temporary.toString(),
            file.toString()),
        "fixture compilation");
    return new ResourceOnlyLoader(this.temporary.toUri().toURL());
  }

  private static final class ResourceOnlyLoader extends URLClassLoader {
    private final Map<String, byte[]> classes;
    private int applicationLoads;
    private int resourceReads;

    private ResourceOnlyLoader(URL directory) {
      super(new URL[] {directory}, VerifiedConstructionTest.class.getClassLoader());
      this.classes = Map.of();
    }

    private ResourceOnlyLoader(Map<String, byte[]> classes) {
      super(new URL[0], VerifiedConstructionTest.class.getClassLoader());
      this.classes = classes;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (name.startsWith("fixtures.")) {
        this.applicationLoads++;
        throw new AssertionError("Application class loading is forbidden: " + name);
      }
      return super.loadClass(name, resolve);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
      if (name.startsWith("fixtures/")) {
        this.resourceReads++;
      }
      var bytes = this.classes.get(name);
      if (bytes != null) {
        return new ByteArrayInputStream(bytes);
      }
      return super.getResourceAsStream(name);
    }
  }
}
