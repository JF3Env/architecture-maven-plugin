package io.github.jf3env.architecture.iosp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.attribute.NestHostAttribute;
import java.lang.classfile.attribute.NestMembersAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.classfile.instruction.ReturnInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class IospSourcesTest {
  private static final String DOMAIN = "com/ai/label/probe";
  private static final String PACKAGE = DOMAIN + "/domain";
  private static final String DECLARATION = "package com.ai.label.probe.domain;\n";
  private static final String MAPSTRUCT =
      "@javax.annotation.processing.Generated(value = \"org.mapstruct.ap.MappingProcessor\")\n";
  private static final FileTime SOURCE_TIME = FileTime.fromMillis(1_700_000_000_000L);

  @TempDir Path temporary;
  private Path sources;
  private Path classes;
  private Path generated;

  private static void sourceFile(Path compiled, String sourceFile) throws IOException {
    var parser = ClassFile.of();
    Files.write(
        compiled,
        parser.transformClass(
            parser.parse(compiled),
            (builder, element) ->
                builder.with(
                    element instanceof SourceFileAttribute
                        ? SourceFileAttribute.of(sourceFile)
                        : element)));
  }

  private static Path write(Path path, String content) throws IOException {
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
    Files.setLastModifiedTime(path, SOURCE_TIME);
    return path;
  }

  private static void stale(Path source, Path compiled) throws IOException {
    Files.setLastModifiedTime(compiled, SOURCE_TIME);
    Files.setLastModifiedTime(source, FileTime.fromMillis(SOURCE_TIME.toMillis() + 2_000));
  }

  private static void assertDiagnostic(IllegalStateException failure, String... details) {
    assertTrue(failure.getMessage().contains("IOSP"), failure.getMessage());
    assertTrue(failure.getMessage().contains("run a clean test build"), failure.getMessage());
    for (var detail : details) {
      assertTrue(failure.getMessage().contains(detail), failure.getMessage());
    }
  }

  private static void returnReference(CodeBuilder builder, CodeElement element) {
    if (element instanceof ReturnInstruction) {
      builder.areturn();
    } else {
      builder.with(element);
    }
  }

  @BeforeEach
  void directories() throws IOException {
    this.sources = this.temporary.resolve("sources");
    this.classes = this.temporary.resolve("classes");
    this.generated = this.temporary.resolve("generated/annotations");
    Files.createDirectories(this.sources.resolve(PACKAGE));
    Files.createDirectories(this.classes.resolve(PACKAGE));
    Files.createDirectories(this.generated);
  }

  @Test
  void selectsEveryTypeBearingUnitRegardlessOfServiceNamesOrInheritanceWithoutInitializingClasses()
      throws IOException {
    var arbitrary =
        this.source(
            "Unrelated.java",
            """
        class AService {
          static { if (System.nanoTime() != 0) throw new AssertionError("initialized"); }
          int run() { return 1; }
        }
        class BService extends AService {}
        """);
    var inherited = this.source("Hidden.java", "class Hidden extends AService {}");
    var transitive = this.source("Worker.java", "class Worker extends Hidden {}");
    var misleading = this.source("MisleadingService.java", "class PlainValue {}");
    this.compile(arbitrary, inherited, transitive, misleading);

    assertEquals(
        List.of(inherited, misleading, arbitrary, transitive),
        IospSources.discover(this.sources, this.classes, "com.ai.label"));
  }

  @Test
  void discoversNestedServiceInAnArbitrarilyNamedUnitOnce() throws IOException {
    var unit =
        this.source(
            "Container.java",
            "class Container { static class NestedService {} static class OtherService {} }");
    this.compile(unit);

    assertEquals(List.of(unit), IospSources.discover(this.sources, this.classes, "com.ai.label"));
  }

  @Test
  void preservesRelativeSourceRootPaths() throws IOException {
    var service = this.validService();
    var workingDirectory = Path.of("").toAbsolutePath();
    var relativeSources = workingDirectory.relativize(this.sources);
    var relativeClasses = workingDirectory.relativize(this.classes);

    assertEquals(
        List.of(workingDirectory.relativize(service)),
        IospSources.discover(relativeSources, relativeClasses, "com.ai.label"));
  }

  @ParameterizedTest
  @CsvSource({
    "BService.java, BService",
    "Arbitrary.java, BService",
    "Hidden.java, Hidden extends AService",
    "DataValue.java, DataValue"
  })
  void rejectsNewUncompiledUnitsEvenWithAValidService(String filename, String declaration)
      throws IOException {
    this.validService();
    this.source(filename, "class " + declaration + " {}");

    this.rejected("missing compiled evidence", filename);
  }

  @ParameterizedTest
  @ValueSource(strings = {"BService", "DataValue"})
  void rejectsDeletedBytecodeEvenWithAValidService(String name) throws IOException {
    this.validService();
    var unit = this.source(name + ".java", "class " + name + " {}");
    this.compile(unit);
    Files.delete(this.bytecode(name));

    this.rejected("missing compiled evidence", name + ".java");
  }

  @ParameterizedTest
  @ValueSource(strings = {"BService", "DataValue"})
  void anotherClassFromTheSameUnitCannotMaskDeletedBytecode(String name) throws IOException {
    this.validService();
    var unit = this.source("Arbitrary.java", "class BService {} class DataValue {}");
    this.compile(unit);
    Files.delete(this.bytecode(name));

    this.rejected("missing compiled evidence", "Arbitrary.java", name);
  }

  @Test
  void rejectsMissingDeclaredMemberClass() throws IOException {
    this.validService();
    this.compile(this.source("Holder.java", "class Holder { static class Member {} }"));
    Files.delete(this.bytecode("Holder$Member"));

    this.rejected("missing compiled evidence", "Holder$Member");
  }

  @Test
  void rejectsMissingAnonymousClassRecordedByItsNestHost() throws IOException {
    this.validService();
    this.compile(
        this.source(
            "Holder.java",
            "class Holder { Runnable action() { return new Runnable() { public void run() {} };"
                + " } }"));
    Files.delete(this.bytecode("Holder$1"));

    this.rejected("missing compiled evidence", "Holder$1", "Holder.java");
  }

  @Test
  void changedValueGetterCannotUseOldAccessorEvidence() throws IOException {
    var value =
        this.source(
            "DataValue.java", "final class DataValue { int value; int read() { return value; } }");
    var service =
        this.source(
            "AService.java",
            "class AService { int run(DataValue data) { return data.read() + 1; } }");
    this.compile(service, value);
    IospAnalysis.requireClean(IospAnalysis.analyze(this.sources, this.classes, "com.ai.label"));

    this.source(
        "DataValue.java", "final class DataValue { int value; int read() { return value * 2; } }");
    stale(value, this.bytecode("DataValue"));

    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> IospAnalysis.analyze(sources, classes, "com.ai.label"));
    assertDiagnostic(failure, "stale", "DataValue.java", "DataValue.class");
    this.compile(value);
    var current = IospAnalysis.analyze(this.sources, this.classes, "com.ai.label");
    assertTrue(current.getProcessingErrors().isEmpty(), current.getProcessingErrors().toString());
    assertTrue(
        current.getConfigurationErrors().isEmpty(), current.getConfigurationErrors().toString());
    assertTrue(
        current.getViolations().stream()
            .anyMatch(item -> item.getDescription().contains("IOSP_MIXED")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"BService", "DataValue"})
  void rejectsDeletedSourcesForEveryCompiledType(String name) throws IOException {
    this.validService();
    var unit = this.source(name + ".java", "class " + name + " {}");
    this.compile(unit);
    Files.delete(unit);

    this.rejected("missing production-backend source", name + ".java", name + ".class");
  }

  @Test
  void incrementalNestedRenameRequiresACleanBuild() throws IOException {
    var service = this.validService();
    var unit = this.source("Holder.java", "class Holder { static class Old {} }");
    this.compile(unit);
    this.source("Holder.java", "class Holder { static class Renamed {} }");
    stale(unit, this.bytecode("Holder$Old"));
    this.compile(unit);
    assertTrue(Files.isRegularFile(this.bytecode("Holder$Renamed")));

    this.rejected("stale", "Holder.java", "Holder$Old.class");
    Files.delete(this.bytecode("Holder$Old"));
    assertEquals(
        List.of(service, unit), IospSources.discover(this.sources, this.classes, "com.ai.label"));
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 1})
  void acceptsEqualOrNewerCompiledMtime(long difference) throws IOException {
    var service = this.validService();
    Files.setLastModifiedTime(
        this.bytecode("AService"), FileTime.fromMillis(SOURCE_TIME.toMillis() + difference));

    assertEquals(
        List.of(service), IospSources.discover(this.sources, this.classes, "com.ai.label"));
  }

  @Test
  void rejectsBytecodeOneMillisecondOlderThanItsSource() throws IOException {
    this.validService();
    Files.setLastModifiedTime(
        this.bytecode("AService"), FileTime.fromMillis(SOURCE_TIME.toMillis() - 1));

    this.rejected("stale", "AService.java");
  }

  @ParameterizedTest
  @ValueSource(strings = {"empty", "truncated", "bad-magic"})
  void rejectsCorruptNonServiceClassBeforeAnalyzingSources(String corruption) throws IOException {
    this.validService();
    this.compile(this.source("DataValue.java", "class DataValue {}"));
    var path = this.bytecode("DataValue");
    var original = Files.readAllBytes(path);
    var bytes =
        switch (corruption) {
          case "empty" -> new byte[0];
          case "truncated" -> Arrays.copyOf(original, original.length / 2);
          default -> new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
        };
    Files.write(path, bytes);

    this.rejected("unusable compiled evidence", "DataValue.class");
  }

  @Test
  void verifiesMethodBodiesEvenWhenClassIdentityAndSourceFileAreReadable() throws IOException {
    this.validService();
    this.compile(this.source("DataValue.java", "class DataValue { int read() { return 1; } }"));
    var path = this.bytecode("DataValue");
    var parser = ClassFile.of();
    var readReturnsReference =
        ClassTransform.transformingMethodBodies(
            method -> method.methodName().equalsString("read"), IospSourcesTest::returnReference);
    var broken = parser.transformClass(parser.parse(path), readReturnsReference);
    var model = parser.parse(broken);
    assertEquals(PACKAGE + "/DataValue", model.thisClass().asInternalName());
    assertTrue(model.elementStream().anyMatch(SourceFileAttribute.class::isInstance));
    assertFalse(parser.verify(model).isEmpty());
    Files.write(path, broken);

    this.rejected("unusable compiled evidence", "DataValue.class");
  }

  @ParameterizedTest
  @ValueSource(strings = {"BService", "DataValue"})
  void rejectsClassesWithoutSourceFileIncludingNonServices(String name) throws IOException {
    this.validService();
    this.compileWithDebug("-g:none", this.source(name + ".java", "class " + name + " {}"));
    var model = ClassFile.of().parse(this.bytecode(name));
    assertFalse(model.elementStream().anyMatch(SourceFileAttribute.class::isInstance));

    this.rejected("SourceFile", name + ".class");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "DataValue.txt",
        "../DataValue.java",
        "nested/DataValue.java",
        "nested\\DataValue.java"
      })
  void rejectsUnusableSourceFileAttributes(String sourceFile) throws IOException {
    this.validService();
    this.compile(this.source("DataValue.java", "class DataValue {}"));
    var path = this.bytecode("DataValue");
    var parser = ClassFile.of();
    Files.write(
        path,
        parser.transformClass(
            parser.parse(path),
            (builder, element) ->
                builder.with(
                    element instanceof SourceFileAttribute
                        ? SourceFileAttribute.of(sourceFile)
                        : element)));

    this.rejected("SourceFile", "DataValue.class");
  }

  @Test
  void rejectsAClassAtTheWrongResourcePath() throws IOException {
    this.validService();
    this.compile(this.source("DataValue.java", "class DataValue {}"));
    Files.copy(this.bytecode("DataValue"), this.bytecode("Wrong"));

    this.rejected("wrong type identity", "Wrong.class", "DataValue");
  }

  @Test
  void permitsPackageInfoWithoutEmittedClass() throws IOException {
    var service = this.validService();
    var packageInfo = this.source("package-info.java", "");
    this.compile(packageInfo);
    assertFalse(Files.exists(this.bytecode("package-info")));

    assertEquals(
        List.of(service), IospSources.discover(this.sources, this.classes, "com.ai.label"));
  }

  @Test
  void validatesFreshnessWhenPackageInfoDoesEmitAClass() throws IOException {
    var service = this.validService();
    var packageInfo =
        write(
            this.sources.resolve(PACKAGE).resolve("package-info.java"),
            "@Deprecated\n" + DECLARATION);
    this.compile(packageInfo);
    assertTrue(Files.isRegularFile(this.bytecode("package-info")));
    assertEquals(
        List.of(service), IospSources.discover(this.sources, this.classes, "com.ai.label"));
    stale(packageInfo, this.bytecode("package-info"));

    this.rejected("stale", "package-info.java", "package-info.class");
  }

  @Test
  void rejectsDeletedPackageInfoSourceWhenClassRemains() throws IOException {
    this.validService();
    var packageInfo =
        write(
            this.sources.resolve(PACKAGE).resolve("package-info.java"),
            "@Deprecated\n" + DECLARATION);
    this.compile(packageInfo);
    Files.delete(packageInfo);

    this.rejected("missing production-backend source", "package-info.java");
  }

  @Test
  void leavesJarDependenciesToTheClasspath() throws IOException {
    var service =
        this.source(
            "AService.java",
            "class AService { void run(org.junit.jupiter.api.function.Executable action) throws"
                + " Throwable { action.execute(); } }");
    this.compile(service);
    assertEquals(
        List.of(service), IospSources.discover(this.sources, this.classes, "com.ai.label"));
  }

  @Test
  void rejectsMissingSourceDirectory() {
    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> IospSources.discover(sources.resolve("missing"), classes, "com.ai.label"));

    assertDiagnostic(failure, "missing production-backend directory", "missing");
  }

  @Test
  void rejectsMissingClassDirectory() throws IOException {
    this.source("AService.java", "class AService {}");
    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> IospSources.discover(sources, classes.resolve("missing"), "com.ai.label"));

    assertDiagnostic(failure, "missing production-backend directory", "missing");
  }

  @Test
  void rejectsEmptySourceInventoryEvenWhenBytecodeRemains() throws IOException {
    Files.delete(this.validService());

    this.rejected("empty .java inventory");
  }

  @Test
  void rejectsEmptyBytecodeInventoryEvenWhenSourcesExist() throws IOException {
    this.source("AService.java", "class AService {}");

    this.rejected("empty .class inventory");
  }

  @ParameterizedTest
  @ValueSource(strings = {"Empty.java", "EmptyService.java"})
  void ordinaryUnitsWithoutTypesCannotBorrowEvidenceFromOtherUnits(String filename)
      throws IOException {
    this.validService();
    var empty = this.source(filename, "// no type declarations");
    this.compile(empty);

    this.rejected("no type declarations", filename);
  }

  @Test
  void emptyPackageInfoIsNotAValidSourceOnlyPackageDeclaration() throws IOException {
    this.validService();
    write(this.sources.resolve(PACKAGE).resolve("package-info.java"), "");

    this.rejected("no backend package declaration", "package-info.java");
  }

  @Test
  void rejectsInvalidSourceEvenIfItsMtimeWasPreserved() throws IOException {
    var service = this.validService();
    write(service, DECLARATION + "class AService { int invalid = ; }");

    this.rejected("cannot parse production-backend sources", "AService.java");
  }

  @Test
  void acceptsAnInventoryWithNoServices() throws IOException {
    var value = this.source("DataValue.java", "class DataValue {}");
    this.compile(value);

    assertEquals(List.of(value), IospSources.discover(this.sources, this.classes, "com.ai.label"));
  }

  @Test
  void packageInfoAloneCannotMakeAnEmptyGatePass() throws IOException {
    this.compile(
        write(
            this.sources.resolve(PACKAGE).resolve("package-info.java"),
            "@Deprecated\n" + DECLARATION));

    this.rejected("found no handwritten backend types");
  }

  @ParameterizedTest
  @ValueSource(strings = {"api", "domain", "application", "infrastructure"})
  void inventoriesOrdinaryClassesInterfacesEnumsRecordsAndAnnotationsWithoutAnyService(String layer)
      throws IOException {
    var unit =
        this.sourceIn(
            "com.ai.label.probe." + layer,
            "Ordinary.java",
            """
        class Ordinary { int value(int n) { return n * 2; } }
        interface Contract { default int value(int n) { return n * 2; } }
        enum Choice { FIRST; int value(int n) { return n * 2; } }
        record Snapshot(int value) { int doubled() { return value * 2; } }
        @interface Marker {}
        """);
    this.compile(unit);

    assertEquals(List.of(unit), IospSources.discover(this.sources, this.classes, "com.ai.label"));
  }

  @ParameterizedTest
  @CsvSource({
    "domain, Ordinary",
    "domain, OrdinaryService",
    "application, Ordinary",
    "application, OrdinaryService",
    "infrastructure, Ordinary",
    "infrastructure, OrdinaryService",
    "infrastructure, MapperImpl"
  })
  void compiledOrdinaryMixedOperationsReachTheRuleInEveryBackendLayer(String layer, String name)
      throws IOException {
    var collaborator =
        this.source("Collaborator.java", "public interface Collaborator { int load(); }");
    var unit =
        this.sourceIn(
            "com.ai.label.probe." + layer,
            name + ".java",
            "class "
                + name
                + " { int run(com.ai.label.probe.domain.Collaborator input)"
                + " { return input.load() + 1; } }");
    this.compile(collaborator, unit);
    assertEquals(
        List.of(collaborator, unit).stream().sorted().toList(),
        IospSources.discover(this.sources, this.classes, "com.ai.label"));

    var report = IospAnalysis.analyze(this.sources, this.classes, "com.ai.label");
    assertTrue(report.getProcessingErrors().isEmpty(), report.getProcessingErrors().toString());
    assertTrue(
        report.getConfigurationErrors().isEmpty(), report.getConfigurationErrors().toString());
    assertTrue(
        report.getViolations().stream()
            .anyMatch(
                violation ->
                    violation.getDescription().contains("IOSP_MIXED")
                        && violation.getFileId().getAbsolutePath().equals(unit.toString())),
        report.getViolations().toString());
  }

  @ParameterizedTest
  @CsvSource({
    "application, uncompiled",
    "infrastructure, uncompiled",
    "application, deleted-class",
    "infrastructure, deleted-class",
    "application, deleted-source",
    "infrastructure, deleted-source",
    "application, stale",
    "infrastructure, stale",
    "application, corrupt",
    "infrastructure, corrupt"
  })
  void validatesEveryNonDomainSourceAndClass(String layer, String damage) throws IOException {
    this.validService();
    var packageName = "com.ai.label.probe." + layer;
    var unit = this.sourceIn(packageName, "Ordinary.java", "class Ordinary {}");
    var compiled = this.classes.resolve(packageName.replace('.', '/')).resolve("Ordinary.class");
    if (damage.equals("uncompiled")) {
      this.rejected("missing compiled evidence", "Ordinary.java");
      return;
    }
    this.compile(unit);
    switch (damage) {
      case "deleted-class" -> {
        Files.delete(compiled);
        this.rejected("missing compiled evidence", "Ordinary.java");
      }
      case "deleted-source" -> {
        Files.delete(unit);
        this.rejected("missing production-backend source", "Ordinary.java");
      }
      case "stale" -> {
        stale(unit, compiled);
        this.rejected("stale", "Ordinary.java", "Ordinary.class");
      }
      default -> {
        Files.write(compiled, new byte[0]);
        this.rejected("unusable compiled evidence", "Ordinary.class");
      }
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "com.ai.label.domainlike.probe",
        "com.ai.label.application.probe",
        "com.ai.label.infra",
        "com.ai.label.domain",
        "rogue.probe"
      })
  void rejectsSourcesOutsideBackendTypeOwnership(String packageName) throws IOException {
    this.validService();
    var unit = this.sourceIn(packageName, "Rogue.java", "class Rogue {}");
    this.compile(unit);

    this.rejected("outside backend ownership root", "Rogue.java", packageName.replace('.', '/'));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "com.ai.label.domainlike.probe",
        "com.ai.label.application.probe",
        "com.ai.label.infra",
        "rogue.probe"
      })
  void rejectsOrphanBytecodeOutsideBackendTypeOwnership(String packageName) throws IOException {
    this.validService();
    var unit = this.sourceIn(packageName, "Rogue.java", "class Rogue {}");
    this.compile(unit);
    Files.delete(unit);

    this.rejected("outside backend ownership root", "Rogue.class");
  }

  @Test
  void corruptBytecodeAtTheOutputRootCannotRemainInvisible() throws IOException {
    this.validService();
    Files.write(this.classes.resolve("Corrupt.class"), new byte[0]);

    this.rejected("unusable compiled evidence", "Corrupt.class");
  }

  @ParameterizedTest
  @ValueSource(strings = {"Rogue.java", "package-info.java"})
  void rejectsMisplacedSourceUnitsEvenWithoutExecutableDeclarations(String filename)
      throws IOException {
    this.validService();
    write(
        this.sources.resolve("rogue").resolve(PACKAGE).resolve(filename),
        DECLARATION + (filename.equals("package-info.java") ? "" : "class Rogue {}"));

    this.rejected("source package does not match resource path", filename);
  }

  @Test
  void rejectsClassFilesBorrowingAnotherUnitsSourceFile() throws IOException {
    this.validService();
    this.compile(this.source("DataValue.java", "class DataValue {}"));
    sourceFile(this.bytecode("DataValue"), "AService.java");

    this.rejected("missing compiled evidence", "DataValue.java", "DataValue");
  }

  @Test
  void aSourceFileAttributeAloneCannotProveAnUndeclaredGeneratedClass() throws IOException {
    this.validService();
    var outside =
        write(
            this.temporary.resolve("outside/AService.java"),
            DECLARATION + "class AService_Bean {}");
    this.compile(outside);

    this.rejected(
        "no source declaration or recognized nest provenance", "AService_Bean", "AService.java");
  }

  @ParameterizedTest
  @ValueSource(strings = {"host", "members", "source-file"})
  void rejectsInconsistentNestedClassMetadata(String damage) throws IOException {
    this.validService();
    this.compile(
        this.source(
            "Holder.java",
            "class Holder { Runnable action() { return new Runnable()"
                + " { public void run() {} }; } }"));
    if (damage.equals("source-file")) {
      sourceFile(this.bytecode("Holder$1"), "AService.java");
    } else {
      var path = this.bytecode(damage.equals("host") ? "Holder$1" : "Holder");
      var parser = ClassFile.of();
      Files.write(
          path,
          parser.transformClass(
              parser.parse(path),
              (builder, element) -> {
                if (!(element instanceof NestHostAttribute)
                    && !(element instanceof NestMembersAttribute)) {
                  builder.with(element);
                }
              }));
    }

    this.rejected("inconsistent nest", "Holder");
  }

  @ParameterizedTest
  @ValueSource(strings = {"api", "domain", "application", "infrastructure"})
  void lombokGeneratedMembersBelongToTheirHandwrittenSourceAndRequireCompleteNests(String layer)
      throws IOException {
    var unit =
        this.sourceIn(
            "com.ai.label.probe." + layer,
            "Ordinary.java",
            "@lombok.Value @lombok.Builder class Ordinary { int value; }");
    this.compileWithOptions(
        List.of("-processor", "lombok.launch.AnnotationProcessorHider$AnnotationProcessor"),
        "-g",
        unit);
    var builder =
        this.classes.resolve("com/ai/label/probe/" + layer + "/Ordinary$OrdinaryBuilder.class");
    assertTrue(Files.isRegularFile(builder));
    assertEquals(List.of(unit), IospSources.discover(this.sources, this.classes, "com.ai.label"));
    Files.delete(builder);

    this.rejected("missing compiled evidence", "Ordinary$OrdinaryBuilder");
  }

  @Test
  void removingBothSidesOfANestCannotHideItsDeclaredMemberRelationship() throws IOException {
    this.compile(this.source("Holder.java", "class Holder { static class Member {} }"));
    var parser = ClassFile.of();
    for (var name : List.of("Holder", "Holder$Member")) {
      var path = this.bytecode(name);
      Files.write(
          path,
          parser.transformClass(
              parser.parse(path),
              (builder, element) -> {
                if (!(element instanceof NestHostAttribute)
                    && !(element instanceof NestMembersAttribute)) {
                  builder.with(element);
                }
              }));
    }

    this.rejected("no source declaration or recognized nest provenance", "Holder$Member");
  }

  @Test
  void packageInfoClassCannotBorrowAnOrdinarySourcesMetadata() throws IOException {
    this.validService();
    this.compile(
        write(
            this.sources.resolve(PACKAGE).resolve("package-info.java"),
            "@Deprecated\n" + DECLARATION));
    sourceFile(this.bytecode("package-info"), "AService.java");

    this.rejected("SourceFile does not identify package metadata", "package-info.class");
  }

  @ParameterizedTest
  @CsvSource({
    "application, false",
    "infrastructure, false",
    "application, true",
    "infrastructure, true"
  })
  void excludesOnlyProvenMapstructUnitsRegardlessOfImplementationName(
      String layer, boolean abstractMapper) throws IOException {
    var fixture = this.mapstructFixture(layer, abstractMapper);

    assertEquals(
        List.of(fixture.mapper),
        IospSources.discover(this.sources, this.classes, List.of(this.generated), "com.ai.label"));
    assertEquals(
        List.of(fixture.mapper),
        IospSources.discover(
            this.sources, this.classes, List.of(this.generated, this.generated), "com.ai.label"));
    this.rejected(
        "missing production-backend source",
        "BoundCodec.java",
        "no recognized generated-source provenance");
  }

  @Test
  void preservesRelativeRootsWithExplicitGeneratedProvenance() throws IOException {
    var fixture = this.mapstructFixture("infrastructure", false);
    var working = Path.of("").toAbsolutePath();

    assertEquals(
        List.of(working.relativize(fixture.mapper)),
        IospSources.discover(
            working.relativize(this.sources),
            working.relativize(this.classes),
            List.of(working.relativize(this.generated)),
            "com.ai.label"));
  }

  @Test
  void emptyGeneratedRootDoesNotExemptUnknownClasses() throws IOException {
    var fixture = this.mapstructFixture("infrastructure", false);
    Files.delete(fixture.unit);

    this.generatedRejected(
        "missing production-backend source", "BoundCodec.java", "BoundCodec$1.class");
  }

  @Test
  void missingExplicitGeneratedRootIsDiagnosed() throws IOException {
    this.validService();
    var failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                IospSources.discover(
                    sources, classes, List.of(generated.resolve("missing")), "com.ai.label"));

    assertDiagnostic(failure, "missing production-backend directory", "missing");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "@javax.annotation.processing.Generated(\"another.Processor\")",
        "// @javax.annotation.processing.Generated(\"org.mapstruct.ap.MappingProcessor\")\n",
        "@javax.annotation.processing.Generated(value = \"another.Processor\","
            + " comments = \"org.mapstruct.ap.MappingProcessor\")",
        "@javax.annotation.processing.Generated({\"org.mapstruct.ap.MappingProcessor\", \"other\"})"
      })
  void rejectsAbsentOrUnrecognizedProcessorMetadataEvenOnMapperImpl(String annotation)
      throws IOException {
    var mapper = this.source("ShapeMapper.java", "@org.mapstruct.Mapper interface ShapeMapper {}");
    var unit =
        write(
            this.generated.resolve(PACKAGE).resolve("ShapeMapperImpl.java"),
            DECLARATION + annotation + "\nclass ShapeMapperImpl implements ShapeMapper {}");
    this.compile(mapper, unit);

    this.generatedRejected("unrecognized generated-source provenance", "ShapeMapperImpl.java");
  }

  @Test
  void requiresEveryGeneratedTopLevelTypeToCarryProcessorMetadata() throws IOException {
    var fixture = this.mapstructFixture("infrastructure", false);
    Files.writeString(fixture.unit, Files.readString(fixture.unit) + "\nclass Intruder {}\n");
    this.compile(fixture.unit);

    this.generatedRejected("unrecognized generated-source provenance", "BoundCodec.java");
  }

  @Test
  void customGeneratedAnnotationCannotImpersonateMapstructProvenance() throws IOException {
    var marker = this.source("Generated.java", "public @interface Generated { String value(); }");
    var mapper = this.source("ShapeMapper.java", "@org.mapstruct.Mapper interface ShapeMapper {}");
    var unit =
        write(
            this.generated.resolve(PACKAGE).resolve("ShapeMapperImpl.java"),
            DECLARATION
                + "import com.ai.label.probe.domain.Generated;\n"
                + "@Generated(\"org.mapstruct.ap.MappingProcessor\")"
                + " class ShapeMapperImpl implements ShapeMapper {}");
    this.compile(marker, mapper, unit);

    this.generatedRejected("unrecognized generated-source provenance", "ShapeMapperImpl.java");
  }

  @Test
  void shadowedGeneratedImportCannotProvideProcessorProvenance() throws IOException {
    var mapper = this.source("ShapeMapper.java", "@org.mapstruct.Mapper interface ShapeMapper {}");
    var unit =
        write(
            this.generated.resolve(PACKAGE).resolve("ShapeMapperImpl.java"),
            DECLARATION
                + """
        import javax.annotation.processing.Generated;
        @Generated("org.mapstruct.ap.MappingProcessor")
        class ShapeMapperImpl implements ShapeMapper {
          @interface Generated { String value(); }
        }
        """);
    this.compile(mapper, unit);

    this.generatedRejected("unrecognized generated-source provenance", "ShapeMapperImpl.java");
  }

  @Test
  void processorMetadataOnAMethodIsNotTypeProvenance() throws IOException {
    var mapper = this.source("ShapeMapper.java", "@org.mapstruct.Mapper interface ShapeMapper {}");
    var unit =
        write(
            this.generated.resolve(PACKAGE).resolve("ShapeMapperImpl.java"),
            DECLARATION
                + "class ShapeMapperImpl implements ShapeMapper { "
                + MAPSTRUCT
                + " int value() { return 1; } }");
    this.compile(mapper, unit);

    this.generatedRejected("unrecognized generated-source provenance", "ShapeMapperImpl.java");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "interface ShapeMapper {}",
        "@interface Mapper {} @Mapper interface ShapeMapper {}"
      })
  void requiresARecognizedHandwrittenMapperOrigin(String mapperBody) throws IOException {
    var mapper = this.source("ShapeMapper.java", mapperBody);
    var unit =
        write(
            this.generated.resolve(PACKAGE).resolve("ShapeMapperImpl.java"),
            DECLARATION + MAPSTRUCT + "class ShapeMapperImpl implements ShapeMapper {}");
    this.compile(mapper, unit);

    this.generatedRejected("no handwritten @Mapper origin", "ShapeMapperImpl.java");
  }

  @Test
  void compiledMapperIdentitySupportsWildcardImportsInHandwrittenSources() throws IOException {
    var mapper =
        this.source(
            "ShapeMapper.java", "import org.mapstruct.*;\n@Mapper interface ShapeMapper {}");
    var unit =
        write(
            this.generated.resolve(PACKAGE).resolve("ShapeMapperImpl.java"),
            DECLARATION + MAPSTRUCT + "class ShapeMapperImpl implements ShapeMapper {}");
    this.compile(mapper, unit);

    assertEquals(
        List.of(mapper),
        IospSources.discover(this.sources, this.classes, List.of(this.generated), "com.ai.label"));
  }

  @Test
  void aShadowedMapperImportCannotEstablishACompiledMapperOrigin() throws IOException {
    var mapper =
        this.source(
            "MapperContainer.java",
            """
        import org.mapstruct.Mapper;
        class MapperContainer {
          @interface Mapper {}
          @Mapper interface ShapeMapper {}
        }
        """);
    var unit =
        write(
            this.generated.resolve(PACKAGE).resolve("ShapeMapperImpl.java"),
            DECLARATION
                + MAPSTRUCT
                + "class ShapeMapperImpl implements MapperContainer.ShapeMapper {}");
    this.compile(mapper, unit);

    this.generatedRejected("no handwritten @Mapper origin", "ShapeMapperImpl.java");
  }

  @Test
  void mapperSourceAnnotationRequiresMatchingCompiledMetadata() throws IOException {
    var fixture = this.mapstructFixture("infrastructure", false);
    var mapperClass = fixture.compiled.resolveSibling("ShapeMapper.class");
    var parser = ClassFile.of();
    Files.write(
        mapperClass,
        parser.transformClass(
            parser.parse(mapperClass),
            (builder, element) -> {
              if (!(element instanceof RuntimeInvisibleAnnotationsAttribute)) {
                builder.with(element);
              }
            }));

    this.generatedRejected("no handwritten @Mapper origin", "BoundCodec.java");
  }

  @Test
  void processorMetadataDoesNotProveAnUnrelatedClassWasGeneratedByAMapper() throws IOException {
    var mapper = this.source("ShapeMapper.java", "@org.mapstruct.Mapper interface ShapeMapper {}");
    var unit =
        write(
            this.generated.resolve(PACKAGE).resolve("ShapeMapperImpl.java"),
            DECLARATION + MAPSTRUCT + "class ShapeMapperImpl {}");
    this.compile(mapper, unit);

    this.generatedRejected("no handwritten @Mapper origin", "ShapeMapperImpl.java");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "deleted-class",
        "stale-class",
        "corrupt",
        "no-source-file",
        "fake-source-file",
        "missing-nest-member"
      })
  void generatedProvenanceDoesNotExemptCompiledEvidenceValidation(String damage)
      throws IOException {
    var fixture = this.mapstructFixture("infrastructure", false);
    switch (damage) {
      case "deleted-class" -> {
        Files.delete(fixture.compiled);
        this.generatedRejected("missing compiled evidence", "BoundCodec.java");
      }
      case "stale-class" -> {
        stale(fixture.unit, fixture.compiled);
        this.generatedRejected("stale", "BoundCodec.java", "BoundCodec.class");
      }
      case "corrupt" -> {
        Files.write(fixture.compiled, new byte[0]);
        this.generatedRejected("unusable compiled evidence", "BoundCodec.class");
      }
      case "no-source-file" -> {
        this.compileWithDebug("-g:none", fixture.unit);
        this.generatedRejected("SourceFile", "BoundCodec");
      }
      case "fake-source-file" -> {
        sourceFile(fixture.compiled, "ShapeMapper.java");
        this.generatedRejected("missing compiled evidence", "BoundCodec.java");
      }
      default -> {
        Files.delete(fixture.compiled.resolveSibling("BoundCodec$1.class"));
        this.generatedRejected("missing compiled evidence", "BoundCodec$1");
      }
    }
  }

  @Test
  void rejectsNewUncompiledGeneratedSourceEvenWhenAnotherGeneratedUnitIsComplete()
      throws IOException {
    this.mapstructFixture("infrastructure", false);
    var unit = this.generated.resolve("com/ai/label/probe/infrastructure/OtherCodec.java");
    write(
        unit,
        "package com.ai.label.probe.infrastructure;\n"
            + MAPSTRUCT
            + "class OtherCodec implements ShapeMapper { public int read(int value) { return value; } }");

    this.generatedRejected("missing compiled evidence", "OtherCodec.java");
  }

  @Test
  void currentBytecodeCannotMaskGeneratedSourceOlderThanItsMapper() throws IOException {
    var fixture = this.mapstructFixture("application", false);
    Files.setLastModifiedTime(fixture.mapper, FileTime.fromMillis(SOURCE_TIME.toMillis() + 1));

    this.generatedRejected("stale", "ShapeMapper.java", "BoundCodec.java");
  }

  @Test
  void deletedMapperSourceCannotLeaveAnExemptGeneratedImplementation() throws IOException {
    this.validService();
    var fixture = this.mapstructFixture("infrastructure", false);
    Files.delete(fixture.mapper);

    this.generatedRejected("missing production-backend source", "ShapeMapper.java");
  }

  @Test
  void generatedRootCannotReclassifyHandwrittenUnits() throws IOException {
    var mapper = this.source("ShapeMapper.java", "@org.mapstruct.Mapper interface ShapeMapper {}");
    var handwritten =
        this.source(
            "ShapeMapperImpl.java", MAPSTRUCT + "class ShapeMapperImpl implements ShapeMapper {}");
    this.compile(mapper, handwritten);

    assertEquals(
        List.of(mapper, handwritten),
        IospSources.discover(this.sources, this.classes, List.of(this.sources), "com.ai.label"));
    assertEquals(
        List.of(mapper, handwritten),
        IospSources.discover(this.sources, this.classes, "com.ai.label"));
  }

  @Test
  void separateGeneratedCopyCannotMaskHandwrittenEvidence() throws IOException {
    var handwritten = this.source("ShapeMapperImpl.java", MAPSTRUCT + "class ShapeMapperImpl {}");
    this.compile(handwritten);
    write(
        this.generated.resolve(PACKAGE).resolve("ShapeMapperImpl.java"),
        Files.readString(handwritten));
    Files.delete(this.bytecode("ShapeMapperImpl"));

    this.generatedRejected("ambiguous source provenance", handwritten.toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing-class", "missing-source", "missing-source-file"})
  void handwrittenGeneratedMarkersNeverExcuseMissingEvidence(String damage) throws IOException {
    this.validService();
    var handwritten = this.source("ShapeMapperImpl.java", MAPSTRUCT + "class ShapeMapperImpl {}");
    this.compile(handwritten);
    switch (damage) {
      case "missing-class" -> {
        Files.delete(this.bytecode("ShapeMapperImpl"));
        this.generatedRejected("missing compiled evidence", "ShapeMapperImpl.java");
      }
      case "missing-source" -> {
        Files.delete(handwritten);
        this.generatedRejected("missing production-backend source", "ShapeMapperImpl.java");
      }
      default -> {
        this.compileWithDebug("-g:none", handwritten);
        this.generatedRejected("SourceFile", "ShapeMapperImpl.class");
      }
    }
  }

  @Test
  void handwrittenGeneratedMarkerDoesNotExemptMixedBehavior() throws IOException {
    var unit =
        this.sourceIn(
            "com.ai.label.probe.infrastructure",
            "ShapeMapperImpl.java",
            MAPSTRUCT
                + "class ShapeMapperImpl { int load() { return 1; } int read() { return load() + 1; } }");
    this.compile(unit);
    assertEquals(
        List.of(unit),
        IospSources.discover(
            this.sources, this.classes, List.of(this.sources, this.generated), "com.ai.label"));

    var report = IospAnalysis.analyze(this.sources, this.classes, "com.ai.label");
    assertTrue(report.getProcessingErrors().isEmpty(), report.getProcessingErrors().toString());
    assertTrue(
        report.getConfigurationErrors().isEmpty(), report.getConfigurationErrors().toString());
    assertTrue(
        report.getViolations().stream()
            .anyMatch(violation -> violation.getDescription().contains("IOSP_MIXED")),
        report.getViolations().toString());
  }

  @Test
  void aGeneratedSourceSymlinkCannotProvideExemptionProvenance() throws IOException {
    this.validService();
    var outside =
        write(
            this.temporary.resolve("outside/Fake.java"), DECLARATION + MAPSTRUCT + "class Fake {}");
    var linked = this.generated.resolve(PACKAGE).resolve("Fake.java");
    Files.createDirectories(linked.getParent());
    Files.createSymbolicLink(linked, outside);

    this.generatedRejected("symbolic link cannot establish source/class provenance", "Fake.java");
  }

  private GeneratedFixture mapstructFixture(String layer, boolean abstractMapper)
      throws IOException {
    var packageName = "com.ai.label.probe." + layer;
    var mapper =
        this.sourceIn(
            packageName,
            "ShapeMapper.java",
            "import org.mapstruct.Mapper;\n@Mapper public "
                + (abstractMapper
                    ? "abstract class ShapeMapper { public abstract int read(int value); }"
                    : "interface ShapeMapper { int read(int value); }"));
    var relative = packageName.replace('.', '/') + "/BoundCodec.java";
    var unit =
        write(
            this.generated.resolve(relative),
            "package "
                + packageName
                + ";\n"
                + "import javax.annotation.processing.Generated;\n"
                + "@Generated(value = \"org.mapstruct.ap.MappingProcessor\", comments = \"fixture\")\n"
                + "public class BoundCodec "
                + (abstractMapper ? "extends" : "implements")
                + " ShapeMapper {\n"
                + "  public int read(int value) { return value; }\n"
                + "  static class Nested {}\n"
                + "  Runnable action() { return new Runnable() { public void run() {} }; }\n}");
    this.compile(mapper, unit);
    return new GeneratedFixture(
        mapper, unit, this.classes.resolve(relative.replace(".java", ".class")));
  }

  private Path validService() throws IOException {
    var service = this.source("AService.java", "class AService { int run() { return 1; } }");
    this.compile(service);
    return service;
  }

  private Path source(String filename, String body) throws IOException {
    return write(this.sources.resolve(PACKAGE).resolve(filename), DECLARATION + body);
  }

  private Path sourceIn(String packageName, String filename, String body) throws IOException {
    return write(
        this.sources.resolve(packageName.replace('.', '/')).resolve(filename),
        "package " + packageName + ";\n" + body);
  }

  private Path bytecode(String name) {
    return this.classes.resolve(PACKAGE).resolve(name + ".class");
  }

  private void compile(Path... units) throws IOException {
    this.compileWithDebug("-g", units);
  }

  private void compileWithDebug(String debug, Path... units) throws IOException {
    this.compileWithOptions(List.of("-proc:none"), debug, units);
  }

  private void compileWithOptions(List<String> processing, String debug, Path... units)
      throws IOException {
    var compiler = ToolProvider.getSystemJavaCompiler();
    var diagnostics = new DiagnosticCollector<JavaFileObject>();
    try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
      var options = new java.util.ArrayList<>(processing);
      options.addAll(
          List.of(
              debug,
              "-d",
              this.classes.toString(),
              "-classpath",
              this.classes + File.pathSeparator + System.getProperty("java.class.path")));
      var task =
          compiler.getTask(
              null,
              files,
              diagnostics,
              options,
              null,
              files.getJavaFileObjectsFromPaths(List.of(units)));
      assertTrue(task.call(), diagnostics.getDiagnostics().toString());
    }
  }

  private void rejected(String... details) {
    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> IospSources.discover(sources, classes, "com.ai.label"));
    assertDiagnostic(failure, details);
  }

  private void generatedRejected(String... details) {
    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> IospSources.discover(sources, classes, List.of(generated), "com.ai.label"));
    assertDiagnostic(failure, details);
  }

  private record GeneratedFixture(Path mapper, Path unit, Path compiled) {}
}
