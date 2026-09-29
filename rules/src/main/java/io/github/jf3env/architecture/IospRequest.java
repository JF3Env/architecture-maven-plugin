package io.github.jf3env.architecture;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
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
    List<Path> classpath,
    Set<String> plumbingCalls) {
  private static final Pattern PLUMBING_CALL =
      Pattern.compile(
          "[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}.$]*#"
              + "[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*");

  public IospRequest(
      String basePackage,
      Path sourceRoot,
      List<Path> generatedRoots,
      Path classesDirectory,
      List<Path> classpath) {
    this(basePackage, sourceRoot, generatedRoots, classesDirectory, classpath, Set.of());
  }

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
    for (var call : plumbingCalls) {
      if (call == null || !PLUMBING_CALL.matcher(call).matches()) {
        throw new IllegalArgumentException(
            "A plumbing call is Owner#method with the owner's binary name: " + call);
      }
    }
    plumbingCalls = Set.copyOf(plumbingCalls);
    for (var generated : generatedRoots) {
      if (sourceRoot.startsWith(generated)) {
        throw new IllegalArgumentException(
            "Handwritten source root cannot be generated: " + sourceRoot);
      }
    }
  }
}
