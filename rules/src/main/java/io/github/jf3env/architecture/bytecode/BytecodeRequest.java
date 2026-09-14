package io.github.jf3env.architecture.bytecode;

import java.nio.file.Path;
import java.util.List;

/** Only outputDirectory is an application inventory; classpath entries resolve dependencies. */
public record BytecodeRequest(BytecodePolicy policy, Path outputDirectory, List<Path> classpath) {
  public BytecodeRequest {
    if (policy == null) throw new IllegalArgumentException("A bytecode policy is required");
    outputDirectory = outputDirectory.toAbsolutePath().normalize();
    classpath =
        classpath.stream().map(path -> path.toAbsolutePath().normalize()).distinct().toList();
  }
}
