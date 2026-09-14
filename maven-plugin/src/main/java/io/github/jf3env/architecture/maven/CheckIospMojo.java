package io.github.jf3env.architecture.maven;

import io.github.jf3env.architecture.IospRequest;
import io.github.jf3env.architecture.IospRules;
import io.github.jf3env.architecture.SourceReport;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * Enforces the IOSP structural-segregation contract over the primary handwritten source root.
 *
 * <p>Separate from {@code check} because the whole-inventory source/class provenance proof is a
 * strictly stronger precondition than the per-file typed and XML rules.
 */
@Mojo(
    name = "check-iosp",
    defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
    requiresDependencyResolution = ResolutionScope.COMPILE)
public final class CheckIospMojo extends AbstractMojo {
  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(required = true)
  private String basePackage;

  @Parameter private List<File> generatedSourceRoots;

  @Parameter(
      defaultValue = "${project.build.directory}/architecture/iosp-report.txt",
      readonly = true)
  private File reportFile;

  public CheckIospMojo() {}

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    SourceReport report;
    try {
      if ("pom".equals(project.getPackaging())) {
        throw new IllegalArgumentException(
            "Apply architecture:check-iosp to a complete application module, not a POM aggregator");
      }
      report = new IospRules().analyze(request());
      writeReport(render(report));
    } catch (Exception failure) {
      try {
        writeReport("ANALYSIS_ERROR\n" + failure + "\n");
      } catch (IOException reportFailure) {
        failure.addSuppressed(reportFailure);
      }
      throw new MojoExecutionException(
          "Architecture IOSP analysis could not complete: " + failure.getMessage(), failure);
    }
    getLog()
        .info("IOSP: inspected " + report.sourceFiles() + " handwritten production source units");
    report.violations().forEach(getLog()::error);
    report.errors().forEach(getLog()::error);
    if (!report.errors().isEmpty()) {
      throw new MojoExecutionException("Architecture IOSP analysis failed; see " + reportFile);
    }
    if (!report.passed()) {
      throw new MojoFailureException("IOSP contract violated; see " + reportFile);
    }
    getLog().info("IOSP: all handwritten backend scopes satisfy the structural segregation policy");
  }

  private IospRequest request() throws Exception {
    var generated =
        generatedSourceRoots == null
            ? List.of(
                Path.of(project.getBuild().getDirectory(), "generated-sources", "annotations")
                    .toAbsolutePath()
                    .normalize())
            : generatedSourceRoots.stream()
                .map(file -> file.toPath().toAbsolutePath().normalize())
                .toList();
    return new IospRequest(
        basePackage,
        Path.of(project.getBuild().getSourceDirectory()),
        generated,
        Path.of(project.getBuild().getOutputDirectory()),
        project.getCompileClasspathElements().stream().map(Path::of).toList());
  }

  private String render(SourceReport report) {
    return (report.passed()
            ? "PASSED"
            : report.errors().isEmpty() ? "VIOLATIONS" : "ANALYSIS_ERROR")
        + "\nbasePackage="
        + basePackage
        + "\nsourceFiles="
        + report.sourceFiles()
        + "\nrules="
        + report.rules()
        + "\n"
        + String.join("\n", report.violations())
        + "\n"
        + String.join("\n", report.errors())
        + "\n";
  }

  private void writeReport(String content) throws IOException {
    var path = reportFile.toPath();
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
  }
}
