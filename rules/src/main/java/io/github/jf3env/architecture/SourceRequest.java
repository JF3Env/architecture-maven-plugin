package io.github.jf3env.architecture;

import java.nio.file.Path;
import java.util.List;
import javax.lang.model.SourceVersion;

/**
 * Immutable inputs for one consumer; dependencies only provide type-resolution evidence.
 *
 * <p>{@code persistenceBoundary} names the one class whose {@code execute} method wraps all
 * JPA/EntityManager work; it is a single application-wide contract, not a per-domain authority, so
 * it is supplied explicitly rather than derived from the compiled inventory. IOSP structural
 * analysis inspects only the first entry of {@code sourceRoots}, the consumer's primary handwritten
 * source root.
 */
public record SourceRequest(
    String basePackage,
    String persistenceBoundary,
    List<Path> sourceRoots,
    List<Path> generatedRoots,
    Path classesDirectory,
    List<Path> classpath) {
  public SourceRequest {
    if (basePackage == null || !SourceVersion.isName(basePackage, SourceVersion.RELEASE_24)) {
      throw new IllegalArgumentException("A valid Java basePackage is required");
    }
    if (persistenceBoundary == null
        || !SourceVersion.isName(persistenceBoundary, SourceVersion.RELEASE_24)) {
      throw new IllegalArgumentException("A valid Java persistenceBoundary class name is required");
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
