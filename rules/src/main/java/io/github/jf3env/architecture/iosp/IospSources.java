package io.github.jf3env.architecture.iosp;

import io.github.jf3env.architecture.ContextShape;
import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.sourceforge.pmd.lang.LanguageProcessorRegistry;
import net.sourceforge.pmd.lang.ast.FileAnalysisException;
import net.sourceforge.pmd.lang.ast.Parser.ParserTask;
import net.sourceforge.pmd.lang.ast.SemanticErrorReporter;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.document.TextDocument;
import net.sourceforge.pmd.lang.java.ast.ASTAnnotation;
import net.sourceforge.pmd.lang.java.ast.ASTImportDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTStringLiteral;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.internal.JavaLanguageProcessor;
import net.sourceforge.pmd.lang.java.internal.JavaLanguageProperties;

/**
 * Validates the complete production-backend source/class inventory before selecting every
 * handwritten type-bearing source unit. Class resources are parsed, never loaded or initialized.
 *
 * <p>Freshness is a worktree mtime check, not a proof of semantic equivalence between Java and
 * bytecode. Every remaining class is checked, including obsolete nested classes left by incremental
 * compilation; those deliberately require a clean build. External dependencies remain governed by
 * the analysis classpath, not this source inventory. Explicit generated roots authorize only
 * MapStruct sources with processor metadata and a handwritten mapper origin; names and annotations
 * in the handwritten root never exempt a source from analysis.
 */
public final class IospSources {
  private static final String MAPPING_PROCESSOR = "org.mapstruct.ap.MappingProcessor";

  private IospSources() {}

  static List<Path> discover(Path sources, Path classes, String basePackage) throws IOException {
    return discover(sources, classes, List.of(), basePackage);
  }

  static List<Path> discover(
      Path sources, Path classes, List<Path> generatedRoots, String basePackage)
      throws IOException {
    return inspect(sources, classes, generatedRoots, basePackage).sources();
  }

  public static Inventory inspect(
      Path sources, Path classes, List<Path> generatedRoots, String basePackage)
      throws IOException {
    var handwritten = sourceUnits(sources, true, basePackage);
    var units = new LinkedHashMap<>(handwritten);
    var generated = generatedUnits(generatedRoots, units, basePackage);
    var types = compiledTypes(classes, units, basePackage);
    verifyDeclarations(units, types);
    verifyNests(units, types);
    verifyGenerated(generated, handwritten, types);
    var selected =
        handwritten.values().stream()
            .filter(unit -> !unit.names.isEmpty())
            .map(SourceUnit::path)
            .toList();
    if (selected.isEmpty()) {
      throw unavailable("found no handwritten backend types; check source scope");
    }
    var applicationTypes =
        types.keySet().stream()
            .map(name -> name.replace('/', '.'))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    return new Inventory(selected, applicationTypes);
  }

  private static void verifyDeclarations(Map<String, SourceUnit> units, Map<String, Type> types) {
    for (var unit : units.values()) {
      for (var name : unit.names) {
        var type = types.get(name);
        if (type == null || !type.source.equals(unit.path)) {
          throw unavailable("missing compiled evidence for " + unit.path + " (" + name + ")");
        }
      }
    }
  }

  private static void verifyNests(Map<String, SourceUnit> units, Map<String, Type> types) {
    for (var entry : types.entrySet()) {
      var name = entry.getKey();
      var type = entry.getValue();
      for (var member : type.members) {
        var nested = types.get(member);
        if (nested == null) {
          throw unavailable(
              "missing compiled evidence for nest member " + member + " in " + type.source);
        }
        if (!nested.host.equals(name) || !nested.source.equals(type.source)) {
          throw unavailable("inconsistent nest metadata for " + member + " in " + type.source);
        }
      }
      if (!type.host.isEmpty()) {
        var host = types.get(type.host);
        if (host == null
            || !host.host.isEmpty()
            || !host.members.contains(name)
            || !host.source.equals(type.source)) {
          throw unavailable(
              "inconsistent nest host " + type.host + " for " + name + " in " + type.source);
        }
      }
    }
    var topLevels = new LinkedHashSet<String>();
    units.values().forEach(unit -> topLevels.addAll(unit.topLevels));
    for (var entry : types.entrySet()) {
      if (!topLevels.contains(entry.getKey())
          && !entry.getKey().endsWith("/package-info")
          && !topLevels.contains(entry.getValue().host)) {
        throw unavailable(
            "compiled type has no source declaration or recognized nest provenance: "
                + entry.getKey()
                + " in "
                + entry.getValue().source);
      }
    }
  }

  private static List<Path> inventory(Path directory, String suffix, boolean requireFiles)
      throws IOException {
    if (!Files.isDirectory(directory)) {
      throw unavailable("missing production-backend directory " + directory);
    }
    try (var paths = Files.walk(directory)) {
      var files =
          paths
              .peek(
                  path -> {
                    if (Files.isSymbolicLink(path)) {
                      throw unavailable(
                          "symbolic link cannot establish source/class provenance: " + path);
                    }
                  })
              .filter(Files::isRegularFile)
              .filter(path -> path.toString().endsWith(suffix))
              .sorted()
              .toList();
      if (requireFiles && files.isEmpty()) {
        throw unavailable("empty " + suffix + " inventory in " + directory);
      }
      return files;
    }
  }

  /**
   * Uses the gate's PMD Java AST to inventory declarations without resolving their dependencies.
   */
  private static Map<String, SourceUnit> sourceUnits(
      Path sources, boolean requireFiles, String basePackage) throws IOException {
    var paths = inventory(sources, ".java", requireFiles);
    var units = new LinkedHashMap<String, SourceUnit>();
    var properties = new JavaLanguageProperties();
    properties.setLanguageVersion("24");
    var processor = new JavaLanguageProcessor(properties);
    try (var registry = LanguageProcessorRegistry.singleton(processor)) {
      for (var path : paths) {
        var unit = sourceTypes(sources, path, processor, registry, basePackage);
        var relative =
            sources.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
        units.put(relative, unit);
      }
    }
    return units;
  }

  private static SourceUnit sourceTypes(
      Path root,
      Path path,
      JavaLanguageProcessor processor,
      LanguageProcessorRegistry registry,
      String basePackage)
      throws IOException {
    try (var document =
        TextDocument.readOnlyString(
            Files.readString(path), FileId.fromPath(path), processor.getLanguageVersion())) {
      var task = new ParserTask(document, SemanticErrorReporter.noop(), registry);
      var unit = processor.getParserWithoutProcessing().parse(task);
      if (unit.getPackageDeclaration() == null) {
        throw unavailable("source unit has no backend package declaration: " + path);
      }
      var names = new LinkedHashSet<String>();
      var packageName = unit.getPackageName().replace('.', '/');
      var topLevelNames =
          path.getFileName().toString().equals("package-info.java")
              ? List.of(ContextShape.PACKAGE_INFO)
              : unit.getTypeDeclarations().toList().stream()
                  .map(ASTTypeDeclaration::getSimpleName)
                  .toList();
      requireOwnership(packageName, topLevelNames, path, basePackage);
      if (!root.resolve(packageName).equals(path.getParent())) {
        throw unavailable(
            "source package does not match resource path: " + path + " (" + packageName + ")");
      }
      var prefix = packageName + "/";
      declaredTypes(unit.getTypeDeclarations(), prefix, names);
      // javac may emit no package-info.class for an unannotated package declaration.
      // If a class does exist, compiledTypes still validates its source and freshness.
      if (names.isEmpty() && !path.getFileName().toString().equals("package-info.java")) {
        throw unavailable("source unit has no type declarations: " + path);
      }
      var topLevels = new LinkedHashSet<String>();
      var mappers = new LinkedHashSet<String>();
      mapperTypes(unit.getTypeDeclarations(), prefix, mappers);
      var mapstruct = !names.isEmpty();
      for (var type : unit.getTypeDeclarations()) {
        topLevels.add(prefix + type.getSimpleName());
        mapstruct &= type.getDeclaredAnnotations().any(IospSources::mapstructGenerated);
      }
      return new SourceUnit(path, names, topLevels, mappers, mapstruct);
    } catch (FileAnalysisException exception) {
      throw new IllegalStateException(
          "IOSP cannot parse production-backend sources: " + path + "; run a clean test build",
          exception);
    }
  }

  private static void requireOwnership(
      String internalPackage, List<String> topLevelTypes, Path path, String basePackage) {
    var shape = ContextShape.of(basePackage);
    var packageName = internalPackage.replace('/', '.');
    shape
        .ownershipViolation(packageName, topLevelTypes)
        .ifPresent(
            reason -> {
              throw unavailable(
                  "type is outside backend ownership root "
                      + shape.description()
                      + ": "
                      + path
                      + " ("
                      + internalPackage
                      + "); "
                      + reason);
            });
  }

  private static boolean annotationType(ASTAnnotation annotation, String qualified) {
    var written = annotation.getTypeNode().getText().toString();
    if (written.equals(qualified)) {
      return true;
    }
    var simple = qualified.substring(qualified.lastIndexOf('.') + 1);
    if (!written.equals(simple)
        || annotation
            .getRoot()
            .descendants(ASTTypeDeclaration.class)
            .crossFindBoundaries()
            .any(type -> simple.equals(type.getSimpleName()))) {
      return false;
    }
    return annotation
        .getRoot()
        .children(ASTImportDeclaration.class)
        .any(imported -> explicitImport(imported, qualified));
  }

  private static boolean explicitImport(ASTImportDeclaration imported, String qualified) {
    return !imported.isStatic()
        && !imported.isImportOnDemand()
        && qualified.equals(imported.getImportedName());
  }

  private static boolean mapstructGenerated(ASTAnnotation annotation) {
    if (!annotationType(annotation, "javax.annotation.processing.Generated")
        && !annotationType(annotation, "javax.annotation.Generated")) {
      return false;
    }
    var values = annotation.getFlatValue("value").toList();
    if (values.size() != 1 || !(values.getFirst() instanceof ASTStringLiteral literal)) {
      return false;
    }
    return MAPPING_PROCESSOR.equals(literal.getConstValue());
  }

  private static void mapperTypes(
      Iterable<? extends ASTTypeDeclaration> declarations, String prefix, Set<String> names) {
    for (var type : declarations) {
      var name = prefix + type.getSimpleName();
      // Compiled @Mapper identity corroborates this spelling, including wildcard imports and
      // shadowed names.
      if (type.getDeclaredAnnotations()
          .any(annotation -> annotation.getSimpleName().equals("Mapper"))) {
        names.add(name);
      }
      mapperTypes(type.getDeclarations(ASTTypeDeclaration.class), name + "$", names);
    }
  }

  private static List<SourceUnit> generatedUnits(
      List<Path> roots, Map<String, SourceUnit> units, String basePackage) throws IOException {
    var generated = new ArrayList<SourceUnit>();
    for (var root : roots) {
      for (var entry : sourceUnits(root, false, basePackage).entrySet()) {
        var previous = units.get(entry.getKey());
        var unit = entry.getValue();
        if (previous != null) {
          if (!Files.isSameFile(previous.path, unit.path)) {
            throw unavailable(
                "ambiguous source provenance: " + previous.path + " and " + unit.path);
          }
          // A configured root cannot turn a handwritten source into an exempt generated source.
          continue;
        }
        if (!unit.mapstruct) {
          throw unavailable(
              "unrecognized generated-source provenance; expected MapStruct @Generated metadata: "
                  + unit.path);
        }
        units.put(entry.getKey(), unit);
        generated.add(unit);
      }
    }
    return generated;
  }

  private static void verifyGenerated(
      List<SourceUnit> generated, Map<String, SourceUnit> handwritten, Map<String, Type> types)
      throws IOException {
    var mappers = new LinkedHashMap<String, Path>();
    handwritten
        .values()
        .forEach(
            unit ->
                unit.mappers.stream()
                    .filter(name -> types.get(name).mapper)
                    .forEach(name -> mappers.put(name, unit.path)));
    for (var unit : generated) {
      for (var name : unit.topLevels) {
        var origins = types.get(name).parents.stream().filter(mappers::containsKey).toList();
        if (origins.isEmpty()) {
          throw unavailable(
              "generated source has no handwritten @Mapper origin: "
                  + unit.path
                  + " ("
                  + name
                  + ")");
        }
        for (var origin : origins) {
          requireFresh(mappers.get(origin), unit.path);
        }
      }
    }
  }

  private static void declaredTypes(
      Iterable<? extends ASTTypeDeclaration> declarations, String prefix, Set<String> names) {
    for (var type : declarations) {
      var name = prefix + type.getSimpleName();
      names.add(name);
      declaredTypes(type.getDeclarations(ASTTypeDeclaration.class), name + "$", names);
    }
  }

  private static Map<String, Type> compiledTypes(
      Path classes, Map<String, SourceUnit> units, String basePackage) throws IOException {
    var types = new LinkedHashMap<String, Type>();
    var paths = inventory(classes, ".class", true);
    try (var loader =
        new URLClassLoader(
            new URL[] {classes.toUri().toURL()}, IospSources.class.getClassLoader())) {
      var parser =
          ClassFile.of(
              ClassFile.ClassHierarchyResolverOption.of(
                  ClassHierarchyResolver.ofResourceParsing(loader)));
      for (var path : paths) {
        var model = readClass(parser, path);
        var name = model.thisClass().asInternalName();
        var simple = name.substring(name.lastIndexOf('/') + 1);
        requireOwnership(
            name.substring(0, Math.max(0, name.lastIndexOf('/'))),
            List.of(simple.contains("$") ? simple.substring(0, simple.indexOf('$')) : simple),
            path,
            basePackage);
        if (!classes.resolve(name + ".class").equals(path)) {
          throw unavailable(
              "compiled evidence has wrong type identity: " + path + " (" + name + ")");
        }
        var source = sourcePath(path, model, units);
        var parents = new ArrayList<String>();
        model.superclass().ifPresent(parent -> parents.add(parent.asInternalName()));
        model.interfaces().forEach(parent -> parents.add(parent.asInternalName()));
        types.put(
            name,
            new Type(
                source,
                parents,
                model
                    .findAttribute(Attributes.nestMembers())
                    .map(
                        attribute ->
                            attribute.nestMembers().stream()
                                .map(member -> member.asInternalName())
                                .toList())
                    .orElse(List.of()),
                model
                    .findAttribute(Attributes.nestHost())
                    .map(attribute -> attribute.nestHost().asInternalName())
                    .orElse(""),
                model
                    .findAttribute(Attributes.runtimeInvisibleAnnotations())
                    .map(
                        attribute ->
                            attribute.annotations().stream()
                                .anyMatch(IospSources::mapperAnnotation))
                    .orElse(false)));
      }
    }
    return types;
  }

  private static boolean mapperAnnotation(java.lang.classfile.Annotation annotation) {
    return annotation.className().equalsString("Lorg/mapstruct/Mapper;");
  }

  private static ClassModel readClass(ClassFile parser, Path path) {
    try {
      var model = parser.parse(path);
      var errors = parser.verify(model);
      if (!errors.isEmpty()) {
        throw new IllegalArgumentException("invalid bytecode", errors.getFirst());
      }
      return model;
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException(
          "IOSP unusable compiled evidence: " + path + "; run a clean test build", exception);
    }
  }

  private static Path sourcePath(Path compiled, ClassModel model, Map<String, SourceUnit> units)
      throws IOException {
    var source =
        model
            .elementStream()
            .filter(SourceFileAttribute.class::isInstance)
            .map(SourceFileAttribute.class::cast)
            .map(attribute -> attribute.sourceFile().stringValue())
            .findFirst()
            .orElse("");
    if (!source.endsWith(".java") || source.contains("/") || source.contains("\\")) {
      throw unavailable("cannot identify source from SourceFile for " + compiled);
    }
    var name = model.thisClass().asInternalName();
    if (name.endsWith("/package-info") && !source.equals("package-info.java")) {
      throw unavailable("SourceFile does not identify package metadata for " + compiled);
    }
    var relative = name.substring(0, name.lastIndexOf('/') + 1) + source;
    var unit = units.get(relative);
    if (unit == null) {
      throw unavailable(
          "missing production-backend source "
              + relative
              + " for "
              + compiled
              + "; no recognized generated-source provenance");
    }
    requireFresh(unit.path, compiled);
    return unit.path;
  }

  private static void requireFresh(Path source, Path compiled) throws IOException {
    if (Files.getLastModifiedTime(source).compareTo(Files.getLastModifiedTime(compiled)) > 0) {
      throw unavailable("derived evidence is stale for " + source + " (" + compiled + ")");
    }
  }

  private static IllegalStateException unavailable(String detail) {
    return new IllegalStateException("IOSP " + detail + "; run a clean test build");
  }

  public record Inventory(List<Path> sources, Set<String> applicationTypes) {}

  private record SourceUnit(
      Path path,
      Set<String> names,
      Set<String> topLevels,
      Set<String> mappers,
      boolean mapstruct) {}

  private record Type(
      Path source, List<String> parents, List<String> members, String host, boolean mapper) {}
}
