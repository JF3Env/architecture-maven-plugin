package io.github.jf3env.architecture;

import java.nio.file.Path;
import java.util.List;
import javax.lang.model.SourceVersion;

/**
 * Immutable inputs for one consumer; dependencies only provide type-resolution evidence.
 *
 * <p>{@code aggregateRootAnnotation} names the consumer's own marker annotation for aggregate
 * roots; it lives in the consumer's platform and therefore cannot be referenced by this library.
 * Bounded contexts are derived from the package shape, never configured.
 */
public record SourceRequest(
    String basePackage,
    String aggregateRootAnnotation,
    List<Path> sourceRoots,
    List<Path> generatedRoots,
    Path classesDirectory,
    List<Path> classpath) {
  public SourceRequest {
    if (basePackage == null || !SourceVersion.isName(basePackage, SourceVersion.RELEASE_24)) {
      throw new IllegalArgumentException("A valid Java basePackage is required");
    }
    if (aggregateRootAnnotation == null
        || !SourceVersion.isName(aggregateRootAnnotation, SourceVersion.RELEASE_24)) {
      throw new IllegalArgumentException(
          "A valid Java aggregateRootAnnotation class name is required");
    }
    sourceRoots =
        sourceRoots.stream().map(path -> path.toAbsolutePath().normalize()).distinct().toList();
    generatedRoots =
        generatedRoots.stream().map(path -> path.toAbsolutePath().normalize()).distinct().toList();
    classesDirectory = classesDirectory.toAbsolutePath().normalize();
    classpath =
        classpath.stream().map(path -> path.toAbsolutePath().normalize()).distinct().toList();
    if (sourceRoots.isEmpty()) {
      throw new IllegalArgumentException("At least one handwritten source root is required");
    }
    for (var root : sourceRoots) {
      if (generatedRoots.stream().anyMatch(root::startsWith)) {
        throw new IllegalArgumentException("Handwritten source root cannot be generated: " + root);
      }
    }
  }
}
