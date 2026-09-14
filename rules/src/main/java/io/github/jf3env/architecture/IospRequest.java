package io.github.jf3env.architecture;

import java.nio.file.Path;
import java.util.List;
import javax.lang.model.SourceVersion;

/**
 * Immutable inputs for one consumer's IOSP analysis.
 *
 * <p>Only the primary handwritten source root is inspected: the whole-inventory source/class
 * provenance proof is defined against one ownership root, matching the reference project's
 * pre-extraction gate. Dependencies only provide type-resolution evidence.
 */
public record IospRequest(
    String basePackage,
    Path sourceRoot,
    List<Path> generatedRoots,
    Path classesDirectory,
    List<Path> classpath) {
  public IospRequest {
    if (basePackage == null || !SourceVersion.isName(basePackage, SourceVersion.RELEASE_24)) {
      throw new IllegalArgumentException("A valid Java basePackage is required");
    }
    sourceRoot = sourceRoot.toAbsolutePath().normalize();
    generatedRoots =
        generatedRoots.stream().map(path -> path.toAbsolutePath().normalize()).distinct().toList();
    classesDirectory = classesDirectory.toAbsolutePath().normalize();
    classpath =
        classpath.stream().map(path -> path.toAbsolutePath().normalize()).distinct().toList();
    for (var generated : generatedRoots) {
      if (sourceRoot.startsWith(generated)) {
        throw new IllegalArgumentException(
            "Handwritten source root cannot be generated: " + sourceRoot);
      }
    }
  }
}
