package io.github.jf3env.architecture.iosp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.Opcode;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import net.sourceforge.pmd.lang.LanguageProcessorRegistry;
import net.sourceforge.pmd.lang.ast.Parser.ParserTask;
import net.sourceforge.pmd.lang.ast.SemanticErrorReporter;
import net.sourceforge.pmd.lang.document.TextDocument;
import net.sourceforge.pmd.lang.java.ast.ASTMethodCall;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.internal.JavaLanguageProcessor;
import net.sourceforge.pmd.lang.java.internal.JavaLanguageProperties;
import net.sourceforge.pmd.lang.java.types.JMethodSig;
import net.sourceforge.pmd.lang.java.types.TypeSystem;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class VerifiedAccessorsTest {
  private static final String FIXTURE =
      """
      package fixtures;
      import java.util.Collections;
      import java.util.List;
      import java.util.Map;
      import java.util.Optional;
      import java.util.Set;
      import static java.util.Optional.ofNullable;
      public final class Accessors {
        int number;
        long wide;
        double precise;
        boolean flag;
        String text;
        String[] array;
        List<String> list;
        Set<String> set;
        Map<String, String> map;
        Leaf leaf;
        Open open;
        Stable stable;
        Generic<String> generic;
        static String global;
        static { if (System.nanoTime() != 0) throw new AssertionError("initialized"); }
        public int arbitrary() { return number; }
        public int explicitThis() { return this.number; }
        public long wide() { return wide; }
        public double precise() { return precise; }
        public boolean flag() { return flag; }
        public String[] array() { return array; }
        public String text() { return text; }
        public int delegated() { return leaf.unusuallyNamed(); }
        public int fieldChain() { return leaf.value; }
        public int selfDelegated() { return arbitrary(); }
        public int finalMethod() { return stable.stored(); }
        public int privateDelegated() { return privateRead(); }
        private int privateRead() { return number; }
        public String genericRead() { return generic.stored(); }
        public Optional<String> optional() { return Optional.ofNullable(text); }
        public Optional<String> nonnullOptional() { return Optional.of(text); }
        public Optional<String> staticImport() { return ofNullable(text); }
        public Optional<String> wrappedDelegate() { return Optional.ofNullable(text()); }
        public List<String> copy() { return List.copyOf(list); }
        public Set<String> copySet() { return Set.copyOf(set); }
        public Map<String, String> copyMap() { return Map.copyOf(map); }
        public List<String> unmodifiable() { return Collections.unmodifiableList(list); }
        public Set<String> unmodifiableSet() { return Collections.unmodifiableSet(set); }
        public Map<String, String> unmodifiableMap() { return Collections.unmodifiableMap(map); }
        public Optional<List<String>> nestedWrapper() { return Optional.ofNullable(List.copyOf(list)); }
        public int getComputed() { return number + 1; }
        public int getBranch() { return flag ? number : 0; }
        public int getWrite() { return ++number; }
        public int getAssign() { return number = 3; }
        public int getReassigned() { number = 3; return number; }
        public int getSideEffect() { list.clear(); return number; }
        public int getLocal() { int saved = number; return saved; }
        public int getArgument(int input) { return input; }
        public int getConstant() { return 7; }
        public String getGlobal() { return global; }
        public static String staticGetter() { return global; }
        public Accessors getSelf() { return this; }
        public synchronized int locked() { return number; }
        public native int missingBody();
        public void noValue() { }
        public long widened() { return number; }
        public Integer boxed() { return number; }
        public int getVirtual() { return open.stored(); }
        public String getDynamic() { return text + number; }
        public Runnable getLambda() { return () -> list.clear(); }
        public int getCaught() { try { return number; } catch (Exception e) { return 0; } }
        public Optional<String> computedWrapper() { return Optional.ofNullable(text.trim()); }
        public Optional<Integer> boxedWrapper() { return Optional.ofNullable(number); }
        public Optional<String> constantWrapper() { return Optional.ofNullable("literal"); }
        public Optional<String> assignedWrapper() { return Optional.ofNullable(text = "changed"); }
        public List<String> computedCopy() { return List.copyOf(List.of(text)); }
        public String fakeWrapper() { return FakeOptional.ofNullable(text); }
        public int cycleA() { return cycleB(); }
        public int cycleB() { return cycleA(); }
        @javax.annotation.processing.Generated("anything")
        public int getGeneratedComputation() { return number * 2; }
        public static final class Leaf {
          int value;
          public int unusuallyNamed() { return value; }
        }
        public static class Open {
          int value;
          public int stored() { return value; }
        }
        public static class Stable {
          int value;
          public final int stored() { return value; }
        }
        public static final class Generic<T> {
          T value;
          public T stored() { return value; }
        }
        static final class FakeOptional {
          static String ofNullable(String text) { return text; }
        }
      }
      """;

  private static final List<String> ACCEPTED =
      List.of(
          "arbitrary",
          "explicitThis",
          "wide",
          "precise",
          "flag",
          "array",
          "text",
          "delegated",
          "fieldChain",
          "selfDelegated",
          "finalMethod",
          "privateDelegated",
          "genericRead",
          "optional",
          "nonnullOptional",
          "staticImport",
          "wrappedDelegate",
          "copy",
          "copySet",
          "copyMap",
          "unmodifiable",
          "unmodifiableSet",
          "unmodifiableMap",
          "nestedWrapper");
  private static final List<String> REJECTED =
      List.of(
          "getComputed",
          "getBranch",
          "getWrite",
          "getAssign",
          "getReassigned",
          "getSideEffect",
          "getLocal",
          "getArgument",
          "getConstant",
          "getGlobal",
          "staticGetter",
          "getSelf",
          "locked",
          "missingBody",
          "noValue",
          "widened",
          "boxed",
          "getVirtual",
          "getDynamic",
          "getLambda",
          "getCaught",
          "computedWrapper",
          "boxedWrapper",
          "constantWrapper",
          "assignedWrapper",
          "computedCopy",
          "fakeWrapper",
          "cycleA",
          "cycleB",
          "getGeneratedComputation");

  private static final String ARRAY_FIXTURE =
      """
      package fixtures;
      public final class ArrayAccessors implements Cloneable {
        float[] floats;
        int[] ints;
        boolean[] booleans;
        byte[] bytes;
        short[] shorts;
        char[] chars;
        long[] longs;
        double[] doubles;
        String[] strings;
        Object[] objects;
        int[][] matrix;
        Object erased;
        Delegate delegate;
        Open open;
        Custom custom;
        boolean choice;
        public float[] getFloatSamples() { return floats.clone(); }
        public int[] getIntegerSamples() { return this.ints.clone(); }
        public boolean[] booleans() { return booleans.clone(); }
        public byte[] bytes() { return bytes.clone(); }
        public short[] shorts() { return shorts.clone(); }
        public char[] chars() { return chars.clone(); }
        public long[] longs() { return longs.clone(); }
        public double[] doubles() { return doubles.clone(); }
        public String[] strings() { return strings.clone(); }
        public int[][] matrix() { return matrix.clone(); }
        public int[] delegated() { return delegate.samples().clone(); }
        public int[] fieldChain() { return delegate.values.clone(); }
        public int[] repeated() { return ints.clone().clone(); }
        public int[] selfDelegated() { return getIntegerSamples().clone(); }
        public Object asObject() { return ints.clone(); }
        public java.util.Optional<int[]> wrapped() {
          return java.util.Optional.ofNullable(ints.clone());
        }
        public int[] virtual() { return open.samples().clone(); }
        public Object objectClone() throws CloneNotSupportedException { return super.clone(); }
        public Object customClone() throws CloneNotSupportedException { return custom.clone(); }
        public int[] argument(int[] input) { return input.clone(); }
        public int[] indexedClone() { return matrix[0].clone(); }
        public int indexedResult() { return ints.clone()[0]; }
        public int length() { return ints.clone().length; }
        public int arithmetic() { return ints.clone()[0] + 1; }
        public int[] assigned() { return (ints = delegate.values).clone(); }
        public int[] branched() { return (choice ? ints : delegate.values).clone(); }
        public int[] allocated() { return new int[3].clone(); }
        public int[] computed() { return java.util.Arrays.copyOf(ints, 3).clone(); }
        public int[] erasedReceiver() { return ((int[]) erased).clone(); }
        public String[] narrowedReceiver() { return ((String[]) objects).clone(); }
        public Object[] widenedOwner() { return ((Object[]) strings).clone(); }
        public String[] mismatchedCast() { return (String[]) objects.clone(); }
        public long[] mismatchedPrimitiveCast() { return (long[]) (Object) ints.clone(); }
        public int[] sideEffect() { ints[0]++; return ints.clone(); }
        public int[] cloneArgument() { return custom.clone(1); }
        public int[] guardedClone() {
          if (ints == null) { throw new IllegalStateException("samples_are_not_integral"); }
          return ints.clone();
        }
        public static final class Delegate {
          int[] values;
          public int[] samples() { return values; }
        }
        public static class Open {
          int[] values;
          public int[] samples() { return values; }
        }
        public static final class Custom implements Cloneable {
          int[] values;
          @Override public Object clone() throws CloneNotSupportedException { return super.clone(); }
          public int[] clone(int ignored) { return values; }
        }
      }
      """;

  private static final List<String> ACCEPTED_ARRAYS =
      List.of(
          "getFloatSamples",
          "getIntegerSamples",
          "booleans",
          "bytes",
          "shorts",
          "chars",
          "longs",
          "doubles",
          "strings",
          "matrix",
          "delegated",
          "fieldChain",
          "repeated",
          "selfDelegated",
          "asObject",
          "wrapped");

  private static final List<String> REJECTED_ARRAYS =
      List.of(
          "virtual",
          "objectClone",
          "customClone",
          "argument",
          "indexedClone",
          "indexedResult",
          "length",
          "arithmetic",
          "assigned",
          "branched",
          "allocated",
          "computed",
          "erasedReceiver",
          "narrowedReceiver",
          "widenedOwner",
          "mismatchedCast",
          "mismatchedPrimitiveCast",
          "sideEffect",
          "cloneArgument",
          "guardedClone");

  @TempDir Path temporary;

  private static String primitiveFixture(String fieldType, String returnType) {
    return """
        package fixtures;
        public final class PrimitiveAccessors {
          private %1$s number;
          public %1$s stored() { return number; }
          public %2$s read() { return number; }
          public %2$s delegated() { return stored(); }
          public %2$s transitive() { return read(); }
        }
        """
        .formatted(fieldType, returnType);
  }

  private static Stream<Arguments> intReturnTypes() {
    var types = List.of("B", "S", "C", "I", "Z");
    return types.stream()
        .flatMap(
            field ->
                types.stream()
                    .map(
                        result ->
                            Arguments.of(
                                ClassDesc.ofDescriptor(field), ClassDesc.ofDescriptor(result))));
  }

  private static void assertArithmeticDecision(String source, ClassLoader loader, int violations) {
    var report = IospAnalysis.analyzeSource(source, loader, "com.ai.label");
    assertTrue(report.getProcessingErrors().isEmpty(), report.getProcessingErrors().toString());
    assertTrue(
        report.getConfigurationErrors().isEmpty(), report.getConfigurationErrors().toString());
    assertTrue(report.getSuppressedViolations().isEmpty());
    assertEquals(violations, report.getViolations().size(), report.getViolations().toString());
    report
        .getViolations()
        .forEach(
            violation ->
                assertTrue(
                    violation.getDescription().contains("IOSP_MIXED"), violation.getDescription()));
  }

  private static void assertBounded(Map<String, JMethodSig> methods, VerifiedAccessors proof) {
    assertFalse(proof.isAccessor(methods.get("m0")));
    assertFalse(proof.isAccessor(methods.get("m8")));
    assertTrue(proof.isAccessor(methods.get("m9")));
    assertTrue(proof.isAccessor(methods.get("m40")));
    assertFalse(proof.isAccessor(methods.get("m0")));
  }

  private static void assertDecisions(
      Map<String, JMethodSig> methods,
      VerifiedAccessors proof,
      List<String> names,
      boolean expected) {
    for (String name : names) {
      assertNotNull(methods.get(name), name);
      assertEquals(expected, proof.isAccessor(methods.get(name)), name);
    }
  }

  private static Map<String, JMethodSig> sourceMethods(String source) throws Exception {
    var properties = new JavaLanguageProperties();
    properties.setLanguageVersion("24");
    var processor = new JavaLanguageProcessor(properties);
    try (var registry = LanguageProcessorRegistry.singleton(processor);
        var document = TextDocument.readOnlyString(source, processor.getLanguageVersion())) {
      var task = new ParserTask(document, SemanticErrorReporter.noop(), registry);
      var root = processor.getParser().parse(task);
      Map<String, JMethodSig> result = new HashMap<>();
      root.descendants(ASTMethodDeclaration.class)
          .forEach(method -> result.put(method.getName(), method.getGenericSignature()));
      return result;
    }
  }

  private static Map<String, JMethodSig> binaryMethods(ClassLoader loader, String owner) {
    var types = TypeSystem.usingClassLoaderClasspath(loader);
    var symbol = types.getClassSymbol(owner);
    assertNotNull(symbol, owner);
    Map<String, JMethodSig> result = new HashMap<>();
    symbol
        .getDeclaredMethods()
        .forEach(method -> result.put(method.getSimpleName(), method.getGenericSignature()));
    return result;
  }

  @Test
  void provesSourceReadsAndExactWrappersWithoutNamingHeuristics() throws Exception {
    var methods = sourceMethods(FIXTURE);
    var proof = new VerifiedAccessors(this.getClass().getClassLoader());
    assertDecisions(methods, proof, ACCEPTED, true);
    assertDecisions(methods, proof, REJECTED, false);
  }

  @Test
  void provesTheSameCompiledMethodsFromResourcesWithoutLoadingClasses() throws Exception {
    try (var loader = this.compile("Accessors", FIXTURE)) {
      var methods = binaryMethods(loader, "fixtures.Accessors");
      var proof = new VerifiedAccessors(loader);
      assertDecisions(methods, proof, ACCEPTED, true);
      assertDecisions(methods, proof, REJECTED, false);
      assertEquals(0, loader.applicationLoads);
      assertTrue(loader.resourceReads > 0);
    }
  }

  @ParameterizedTest
  @CsvSource({
    "byte, byte, true",
    "short, short, true",
    "char, char, true",
    "int, int, true",
    "boolean, boolean, true",
    "long, long, true",
    "float, float, true",
    "double, double, true",
    "byte, short, false",
    "byte, int, false",
    "short, int, false",
    "char, int, false"
  })
  void primitiveReadsAndDelegatesRequireExactSourceAndBinaryReturnTypes(
      String fieldType, String returnType, boolean expected) throws Exception {
    var source = primitiveFixture(fieldType, returnType);
    var reads = List.of("read", "delegated", "transitive");
    assertDecisions(
        sourceMethods(source),
        new VerifiedAccessors(this.getClass().getClassLoader()),
        reads,
        expected);
    try (var loader = this.compile("PrimitiveAccessors", source)) {
      var methods = binaryMethods(loader, "fixtures.PrimitiveAccessors");
      var proof = new VerifiedAccessors(loader);
      assertDecisions(methods, proof, reads, expected);
      assertTrue(proof.isAccessor(methods.get("stored")));
      assertEquals(0, loader.applicationLoads);
    }
  }

  @ParameterizedTest
  @MethodSource("intReturnTypes")
  void sharedIreturnStackKindDoesNotProveMatchingJavaStorageTypes(
      ClassDesc fieldType, ClassDesc returnType) throws Exception {
    // The VM also accepts narrowing and boolean/numeric pairs that Java cannot return implicitly.
    this.writePrimitiveRead(fieldType, returnType);
    try (var loader = new ResourceOnlyLoader(this.temporary.toUri().toURL())) {
      var signature = binaryMethods(loader, "fixtures.PrimitiveAccessors").get("read");
      assertNotNull(signature);
      assertEquals(
          fieldType.equals(returnType), new VerifiedAccessors(loader).isAccessor(signature));
      assertEquals(0, loader.applicationLoads);
    }
  }

  @ParameterizedTest
  @CsvSource({
    "byte, byte, 0",
    "short, short, 0",
    "char, char, 0",
    "int, int, 0",
    "byte, short, 1",
    "byte, int, 1",
    "short, int, 1",
    "char, int, 1"
  })
  void gateCombinesWideningDelegationWithArithmeticInSourceAndBinary(
      String fieldType, String returnType, int violations) throws Exception {
    var declaration = "package com.ai.label.probe.domain;";
    var accessor =
        primitiveFixture(fieldType, returnType).replace("package fixtures;", declaration);
    var service =
        """
        final class ArithmeticService {
          int run(PrimitiveAccessors data) { return data.read() + 1; }
        }
        """;
    assertArithmeticDecision(accessor + service, this.getClass().getClassLoader(), violations);
    try (var loader = this.compile("PrimitiveAccessors", accessor)) {
      assertArithmeticDecision(declaration + service, loader, violations);
      assertEquals(0, loader.applicationLoads);
      assertTrue(loader.resourceReads > 0);
    }
  }

  @Test
  void rereadsPrimitiveFieldDescriptorsBetweenProofRequests() throws Exception {
    var result = ClassDesc.ofDescriptor("I");
    this.writePrimitiveRead(result, result);
    try (var loader = new ResourceOnlyLoader(this.temporary.toUri().toURL())) {
      var signature = binaryMethods(loader, "fixtures.PrimitiveAccessors").get("read");
      assertNotNull(signature);
      var proof = new VerifiedAccessors(loader);
      for (String descriptor : List.of("I", "B", "S", "C", "Z", "I")) {
        this.writePrimitiveRead(ClassDesc.ofDescriptor(descriptor), result);
        assertEquals(descriptor.equals("I"), proof.isAccessor(signature), descriptor);
      }
      assertEquals(0, loader.applicationLoads);
    }
  }

  private void writePrimitiveRead(ClassDesc fieldType, ClassDesc returnType) throws IOException {
    var owner = ClassDesc.of("fixtures.PrimitiveAccessors");
    var parser = ClassFile.of();
    var bytes =
        parser.build(
            owner,
            builder ->
                builder
                    .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL)
                    .withField("number", fieldType, ClassFile.ACC_PRIVATE)
                    .withMethodBody(
                        "read",
                        MethodTypeDesc.of(returnType),
                        ClassFile.ACC_PUBLIC,
                        code -> code.aload(0).getfield(owner, "number", fieldType).ireturn()));
    var errors = parser.verify(parser.parse(bytes));
    assertTrue(errors.isEmpty(), errors.toString());
    var directory = Files.createDirectories(this.temporary.resolve("fixtures"));
    Files.write(directory.resolve("PrimitiveAccessors.class"), bytes);
  }

  @Test
  void provesIntrinsicArrayClonesInSourceIncludingDelegatesAndWrappers() throws Exception {
    assertDecisions(
        sourceMethods(ARRAY_FIXTURE),
        new VerifiedAccessors(this.getClass().getClassLoader()),
        ACCEPTED_ARRAYS,
        true);
  }

  @Test
  void rejectsUnprovenSourceClonesAndArrayComputations() throws Exception {
    assertDecisions(
        sourceMethods(ARRAY_FIXTURE),
        new VerifiedAccessors(this.getClass().getClassLoader()),
        REJECTED_ARRAYS,
        false);
  }

  @Test
  void provesIntrinsicArrayClonesAndMatchingBytecodeCastsWithoutClassLoading() throws Exception {
    try (var loader = this.compile("ArrayAccessors", ARRAY_FIXTURE)) {
      assertDecisions(
          binaryMethods(loader, "fixtures.ArrayAccessors"),
          new VerifiedAccessors(loader),
          ACCEPTED_ARRAYS,
          true);
      assertEquals(0, loader.applicationLoads);
    }
  }

  @Test
  void rejectsUnprovenBytecodeClonesAndArrayComputations() throws Exception {
    try (var loader = this.compile("ArrayAccessors", ARRAY_FIXTURE)) {
      assertDecisions(
          binaryMethods(loader, "fixtures.ArrayAccessors"),
          new VerifiedAccessors(loader),
          REJECTED_ARRAYS,
          false);
      assertEquals(0, loader.applicationLoads);
    }
  }

  @Test
  void arrayCloneSignatureAloneDoesNotProveAFieldDerivedReceiver() throws Exception {
    var properties = new JavaLanguageProperties();
    properties.setLanguageVersion("24");
    var processor = new JavaLanguageProcessor(properties);
    var source = "final class ArrayCall { int[] values; int[] read() { return values.clone(); } }";
    try (var registry = LanguageProcessorRegistry.singleton(processor);
        var document = TextDocument.readOnlyString(source, processor.getLanguageVersion())) {
      var root =
          processor
              .getParser()
              .parse(new ParserTask(document, SemanticErrorReporter.noop(), registry));
      var call = root.descendants(ASTMethodCall.class).first();
      assertNotNull(call);
      assertTrue(call.getMethodType().getDeclaringType().isArray());
      assertFalse(
          new VerifiedAccessors(this.getClass().getClassLoader()).isAccessor(call.getMethodType()));
    }
  }

  @Test
  void arrayCloneBytecodeRequiresTheExactOpcodeAndDescriptor() throws Exception {
    try (var loader = this.compile("ArrayAccessors", ARRAY_FIXTURE)) {
      var signature = binaryMethods(loader, "fixtures.ArrayAccessors").get("asObject");
      assertFalse(
          this.proofForCloneInstruction(
              loader,
              signature,
              Opcode.INVOKESTATIC,
              MethodTypeDesc.ofDescriptor("()Ljava/lang/Object;")));
      assertFalse(
          this.proofForCloneInstruction(
              loader, signature, Opcode.INVOKEVIRTUAL, MethodTypeDesc.ofDescriptor("()[I")));
      assertTrue(
          this.proofForCloneInstruction(
              loader,
              signature,
              Opcode.INVOKEVIRTUAL,
              MethodTypeDesc.ofDescriptor("()Ljava/lang/Object;")));
      assertEquals(0, loader.applicationLoads);
    }
  }

  private boolean proofForCloneInstruction(
      ResourceOnlyLoader loader, JMethodSig signature, Opcode opcode, MethodTypeDesc type)
      throws IOException {
    var owner = ClassDesc.of("fixtures.ArrayAccessors");
    var array = ClassDesc.ofDescriptor("[I");
    var asObject = MethodTypeDesc.ofDescriptor("()Ljava/lang/Object;");
    var bytes =
        ClassFile.of()
            .build(
                owner,
                builder ->
                    builder
                        .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL)
                        .withField("ints", array, ClassFile.ACC_PRIVATE)
                        .withMethodBody(
                            "asObject",
                            asObject,
                            ClassFile.ACC_PUBLIC,
                            code ->
                                code.aload(0)
                                    .getfield(owner, "ints", array)
                                    .invoke(opcode, array, "clone", type, false)
                                    .areturn()));
    Files.write(this.temporary.resolve("fixtures/ArrayAccessors.class"), bytes);
    return new VerifiedAccessors(loader).isAccessor(signature);
  }

  @Test
  void rejectsOverridableDeclarationsEvenWhenTheCurrentBodyIsAFieldRead() throws Exception {
    var source =
        "package fixtures; public class Open { int value; public int stored() { return value; } }";
    assertFalse(
        new VerifiedAccessors(this.getClass().getClassLoader())
            .isAccessor(sourceMethods(source).get("stored")));
    try (var loader = this.compile("Open", source)) {
      assertFalse(
          new VerifiedAccessors(loader)
              .isAccessor(binaryMethods(loader, "fixtures.Open").get("stored")));
    }
  }

  @Test
  void sourceEvidenceWinsOverAnOlderCompiledGetter() throws Exception {
    try (var loader = this.compile("Accessors", FIXTURE)) {
      var changed = sourceMethods(FIXTURE.replace("return number;", "return number + 1;"));
      assertFalse(new VerifiedAccessors(loader).isAccessor(changed.get("arbitrary")));
      assertEquals(0, loader.resourceReads);
    }
  }

  @Test
  void missingClassEvidenceIsNotAnExemption() throws Exception {
    try (var loader = this.compile("Accessors", FIXTURE)) {
      var signature = binaryMethods(loader, "fixtures.Accessors").get("arbitrary");
      assertFalse(new VerifiedAccessors(new ClassLoader(null) {}).isAccessor(signature));
    }
  }

  @Test
  void missingDelegatedEvidenceIsNotAnExemption() throws Exception {
    try (var loader = this.compile("Accessors", FIXTURE)) {
      var methods = binaryMethods(loader, "fixtures.Accessors");
      var hiding =
          new ClassLoader(loader) {
            @Override
            public InputStream getResourceAsStream(String name) {
              return name.endsWith("$Leaf.class") ? null : super.getResourceAsStream(name);
            }
          };
      assertFalse(new VerifiedAccessors(hiding).isAccessor(methods.get("delegated")));
    }
  }

  @Test
  void unreadableResourcesFailVisiblyAndAreNotCachedAsRejections() throws Exception {
    try (var loader = this.compile("Accessors", FIXTURE)) {
      var signature = binaryMethods(loader, "fixtures.Accessors").get("arbitrary");
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
      var proof = new VerifiedAccessors(broken);
      for (var attempt = 0; attempt < 2; attempt++) {
        var exception = assertThrows(UncheckedIOException.class, () -> proof.isAccessor(signature));
        assertTrue(exception.getMessage().contains("fixtures/Accessors.class"));
        assertEquals("fixture read failure", exception.getCause().getMessage());
      }
    }
  }

  @Test
  void corruptResourcesFailVisibly() throws Exception {
    try (var loader = this.compile("Accessors", FIXTURE)) {
      var signature = binaryMethods(loader, "fixtures.Accessors").get("arbitrary");
      var broken =
          new ClassLoader(loader) {
            @Override
            public InputStream getResourceAsStream(String name) {
              return new ByteArrayInputStream(new byte[] {0, 1, 2, 3});
            }
          };
      assertThrows(
          IllegalArgumentException.class,
          () -> new VerifiedAccessors(broken).isAccessor(signature));
    }
  }

  @Test
  void structurallyParsableButInvalidBytecodeFailsVisibly() throws Exception {
    try (var loader = this.compile("Accessors", FIXTURE)) {
      var signature = binaryMethods(loader, "fixtures.Accessors").get("arbitrary");
      var invalid =
          ClassFile.of()
              .build(
                  ClassDesc.of("fixtures.Accessors"),
                  builder ->
                      builder
                          .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL)
                          .withMethodBody(
                              "arbitrary",
                              MethodTypeDesc.ofDescriptor("()I"),
                              ClassFile.ACC_PUBLIC,
                              code -> code.aload(0).ireturn()));
      Files.write(this.temporary.resolve("fixtures/Accessors.class"), invalid);
      var exception =
          assertThrows(
              IllegalArgumentException.class,
              () -> new VerifiedAccessors(loader).isAccessor(signature));
      assertTrue(exception.getMessage().contains("Invalid class resource"));
      assertTrue(exception.getCause() instanceof VerifyError);
    }
  }

  @Test
  void wrongClassResourceFailsVisibly() throws Exception {
    try (var loader = this.compile("Accessors", FIXTURE)) {
      var signature = binaryMethods(loader, "fixtures.Accessors").get("arbitrary");
      var wrong =
          new ClassLoader(loader) {
            @Override
            public InputStream getResourceAsStream(String name) {
              return super.getResourceAsStream("fixtures/Accessors$Leaf.class");
            }
          };
      var exception =
          assertThrows(
              IllegalArgumentException.class,
              () -> new VerifiedAccessors(wrong).isAccessor(signature));
      assertTrue(exception.getMessage().contains("wrong owner"));
    }
  }

  @Test
  void boundsDelegationWithoutPoisoningLaterProofs() throws Exception {
    var source = new StringBuilder("package fixtures; public final class Chain { int value; ");
    for (var i = 0; i < 40; i++) {
      source.append("public int m").append(i).append("() { return m").append(i + 1).append("(); }");
    }
    source.append("public int m40() { return value; } }");
    var sourceMethods = sourceMethods(source.toString());
    assertBounded(sourceMethods, new VerifiedAccessors(this.getClass().getClassLoader()));
    try (var loader = this.compile("Chain", source.toString())) {
      assertBounded(binaryMethods(loader, "fixtures.Chain"), new VerifiedAccessors(loader));
    }
  }

  @Test
  void cyclesTerminateAndDoNotPoisonAnIndependentGetter() throws Exception {
    try (var loader = this.compile("Accessors", FIXTURE)) {
      var methods = binaryMethods(loader, "fixtures.Accessors");
      var proof = new VerifiedAccessors(loader);
      Assertions.assertTimeout(
          Duration.ofSeconds(5),
          () -> {
            for (int attempt = 0; attempt < 3; attempt++) {
              assertFalse(proof.isAccessor(methods.get("cycleA")));
              assertFalse(proof.isAccessor(methods.get("cycleB")));
              assertTrue(proof.isAccessor(methods.get("arbitrary")));
            }
          });
    }
  }

  @Test
  void doesNotRetainStaleBytecodeBetweenRequests() throws Exception {
    try (var loader = this.compile("Accessors", FIXTURE)) {
      var signature = binaryMethods(loader, "fixtures.Accessors").get("arbitrary");
      var proof = new VerifiedAccessors(loader);
      assertTrue(proof.isAccessor(signature));
      Files.write(this.temporary.resolve("fixtures/Accessors.class"), new byte[] {0, 1, 2, 3});
      assertThrows(IllegalArgumentException.class, () -> proof.isAccessor(signature));
    }
  }

  @Test
  void doesNotRetainSourceResultsAcrossDifferentTreesWithTheSameBinaryName() throws Exception {
    var proof = new VerifiedAccessors(this.getClass().getClassLoader());
    assertTrue(proof.isAccessor(sourceMethods(FIXTURE).get("arbitrary")));
    var changed = sourceMethods(FIXTURE.replace("return number;", "return number + 1;"));
    assertFalse(proof.isAccessor(changed.get("arbitrary")));
  }

  @Test
  void rejectsUnresolvedMethodEvidence() {
    var types = TypeSystem.usingClassLoaderClasspath(this.getClass().getClassLoader());
    assertFalse(
        new VerifiedAccessors(this.getClass().getClassLoader())
            .isAccessor(types.UNRESOLVED_METHOD));
  }

  @Test
  void provesCompilerGeneratedRecordAccessorsButNotRecordComputations() throws Exception {
    var source = "package fixtures; public record Snapshot(String value) {}";
    try (var loader = this.compile("Snapshot", source)) {
      var methods = binaryMethods(loader, "fixtures.Snapshot");
      var proof = new VerifiedAccessors(loader);
      assertTrue(proof.isAccessor(methods.get("value")));
      assertFalse(proof.isAccessor(methods.get("hashCode")));
      assertFalse(proof.isAccessor(methods.get("toString")));
      assertFalse(proof.isAccessor(methods.get("equals")));
      assertEquals(0, loader.applicationLoads);
    }
  }

  @Test
  void rejectsSourceBranchesAndMutationsHiddenInReceivers() throws Exception {
    var source =
        """
        package fixtures;
        final class Receivers {
          Leaf leaf;
          Leaf other;
          boolean choice;
          int value;
          java.util.Optional<String> wrapper;
          String text;
          public int branch() { return (choice ? leaf : other).stored(); }
          public int assigned() { return (leaf = other).stored(); }
          public int allocated() { return new Leaf().stored(); }
          public java.util.Optional<String> staticReceiver() {
            return sideEffect().ofNullable(text);
          }
          private java.util.Optional<String> sideEffect() { value++; return wrapper; }
          final class Leaf {
            int number;
            final int stored() { return number; }
          }
        }
        """;
    var methods = sourceMethods(source);
    assertDecisions(
        methods,
        new VerifiedAccessors(this.getClass().getClassLoader()),
        List.of("branch", "assigned", "allocated", "staticReceiver"),
        false);
  }

  private ResourceOnlyLoader compile(String name, String source) throws IOException {
    var file = this.temporary.resolve(name + ".java");
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
    private int applicationLoads;
    private int resourceReads;

    private ResourceOnlyLoader(URL directory) {
      super(new URL[] {directory}, VerifiedAccessorsTest.class.getClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (name.startsWith("fixtures.") || name.startsWith("com.ai.label.probe.domain.")) {
        this.applicationLoads++;
        throw new AssertionError("Application class loading is forbidden: " + name);
      }
      return super.loadClass(name, resolve);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
      if (name.startsWith("fixtures/") || name.startsWith("com/ai/label/probe/domain/")) {
        this.resourceReads++;
      }
      return super.getResourceAsStream(name);
    }
  }
}
