package io.github.jf3env.architecture.maven;

import io.github.jf3env.architecture.ContextShape;
import io.github.jf3env.architecture.SourceReport;
import io.github.jf3env.architecture.SourceRequest;
import io.github.jf3env.architecture.SourceRules;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/** Enforces the versioned source contracts independently of test execution. */
@Mojo(
    name = "check",
    defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
    requiresDependencyResolution = ResolutionScope.COMPILE)
public final class CheckMojo extends AbstractMojo {
  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(required = true)
  private String basePackage;

  /** The shared kernel's single package segment below {@code basePackage}; never a context. */
  @Parameter(defaultValue = "platform")
  private String platformPackage;

  /**
   * Fully qualified aggregate-root marker annotation declared by the consumer's platform; defaults
   * to {@code <basePackage>.<platformPackage>.domain.AggregateRoot}.
   */
  @Parameter private String aggregateRootAnnotation;

  @Parameter private List<File> generatedSourceRoots;

  @Parameter(
      defaultValue = "${project.build.directory}/architecture/source-report.txt",
      readonly = true)
  private File reportFile;

  public CheckMojo() {}

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    SourceReport report;
    SourceRequest request;
    try {
      if ("pom".equals(project.getPackaging())) {
        throw new IllegalArgumentException(
            "Apply architecture:check to a complete application module, not a POM aggregator");
      }
      var release = project.getProperties().getProperty("maven.compiler.release");
      if (release != null && !"24".equals(release)) {
        throw new IllegalArgumentException(
            "This version supports Java release 24; configured release is " + release);
      }
      request = request();
      report = new SourceRules().analyze(request);
      writeReport(render(request, report));
    } catch (Exception failure) {
      try {
        writeReport("ANALYSIS_ERROR\n" + failure + "\n");
      } catch (IOException reportFailure) {
        failure.addSuppressed(reportFailure);
      }
      throw new MojoExecutionException(
          "Architecture source analysis could not complete: " + failure.getMessage(), failure);
    }
    getLog()
        .info(
            "Architecture source rules: "
                + report.rules()
                + "; inspected "
                + report.sourceFiles()
                + " source files");
    report.violations().forEach(getLog()::error);
    report.errors().forEach(getLog()::error);
    if (!report.errors().isEmpty()) {
      throw new MojoExecutionException("Architecture source analysis failed; see " + reportFile);
    }
    if (!report.passed()) {
      throw new MojoFailureException("Architecture source contracts violated; see " + reportFile);
    }
    getLog().info("Architecture source contracts passed");
  }

  private SourceRequest request() throws Exception {
    var generated =
        generatedSourceRoots == null
            ? List.of(
                Path.of(project.getBuild().getDirectory(), "generated-sources", "annotations")
                    .toAbsolutePath()
                    .normalize())
            : generatedSourceRoots.stream()
                .map(file -> file.toPath().toAbsolutePath().normalize())
                .toList();
    var roots = new ArrayList<Path>();
    roots.add(Path.of(project.getBuild().getSourceDirectory()).toAbsolutePath().normalize());
    project.getCompileSourceRoots().stream()
        .map(root -> Path.of(root).toAbsolutePath().normalize())
        .filter(root -> generated.stream().noneMatch(root::startsWith))
        .forEach(roots::add);
    var classpath = project.getCompileClasspathElements().stream().map(Path::of).toList();
    var marker =
        aggregateRootAnnotation == null || aggregateRootAnnotation.isBlank()
            ? ContextShape.of(basePackage, platformPackage).defaultAggregateRootAnnotation()
            : aggregateRootAnnotation;
    return new SourceRequest(
        basePackage,
        marker,
        roots,
        generated,
        Path.of(project.getBuild().getOutputDirectory()),
        classpath);
  }

  private String render(SourceRequest request, SourceReport report) {
    return (report.passed()
            ? "PASSED"
            : report.errors().isEmpty() ? "VIOLATIONS" : "ANALYSIS_ERROR")
        + "\nbasePackage="
        + request.basePackage()
        + "\nplatformPackage="
        + platformPackage
        + "\naggregateRootAnnotation="
        + request.aggregateRootAnnotation()
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
