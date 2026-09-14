package io.github.jf3env.architecture.maven;

import io.github.jf3env.architecture.bytecode.BytecodePolicy;
import io.github.jf3env.architecture.bytecode.BytecodeReport;
import io.github.jf3env.architecture.bytecode.BytecodeRequest;
import io.github.jf3env.architecture.bytecode.BytecodeRules;
import io.github.jf3env.architecture.bytecode.DomainPolicy;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/** Complete ArchUnit and construction-policy execution, independent of Surefire. */
@Mojo(
    name = "check-bytecode",
    defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
    requiresDependencyResolution = ResolutionScope.COMPILE)
public final class CheckBytecodeMojo extends AbstractMojo {
  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(required = true)
  private String basePackage;

  @Parameter(required = true)
  private String authorityDomain;

  @Parameter(required = true)
  private String forbiddenDomain;

  @Parameter(required = true)
  private String authorityAggregate;

  @Parameter(required = true)
  private String authorityRepository;

  @Parameter(required = true)
  private List<String> authorityServices;

  @Parameter(required = true)
  private String forbiddenRepository;

  @Parameter(
      defaultValue = "${project.build.directory}/architecture/bytecode-report.txt",
      readonly = true)
  private File reportFile;

  public CheckBytecodeMojo() {}

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    BytecodeReport report;
    try {
      if ("pom".equals(project.getPackaging())) {
        throw new IllegalArgumentException(
            "Apply check-bytecode to a complete application module, not a POM aggregator");
      }
      var domain =
          new DomainPolicy(
              authorityDomain,
              forbiddenDomain,
              authorityAggregate,
              authorityRepository,
              Set.copyOf(authorityServices),
              forbiddenRepository);
      var policy = new BytecodePolicy(basePackage, domain);
      var request =
          new BytecodeRequest(
              policy,
              Path.of(project.getBuild().getOutputDirectory()),
              project.getCompileClasspathElements().stream().map(Path::of).toList());
      report = new BytecodeRules().analyze(request);
      writeReport(render(report));
    } catch (Exception failure) {
      try {
        writeReport("ANALYSIS_ERROR\n" + failure + "\n");
      } catch (IOException reportFailure) {
        failure.addSuppressed(reportFailure);
      }
      throw new MojoExecutionException(
          "Architecture bytecode analysis could not complete: " + failure.getMessage(), failure);
    }
    report.rules().forEach(rule -> getLog().info("Architecture rule: " + rule));
    report.violations().forEach(getLog()::error);
    report.errors().forEach(getLog()::error);
    if (!report.errors().isEmpty())
      throw new MojoExecutionException("Architecture bytecode analysis failed; see " + reportFile);
    if (!report.passed())
      throw new MojoFailureException("Architecture bytecode contracts violated; see " + reportFile);
    getLog()
        .info(
            "Architecture: "
                + report.rules().size()
                + " backend rules and construction policy passed; "
                + report.classFiles()
                + " application classes inspected");
  }

  private String render(BytecodeReport report) {
    return (report.passed()
            ? "PASSED"
            : report.errors().isEmpty() ? "VIOLATIONS" : "ANALYSIS_ERROR")
        + "\nbasePackage="
        + basePackage
        + "\nauthorityDomain="
        + authorityDomain
        + "\nauthorityAggregate="
        + authorityAggregate
        + "\nauthorityRepository="
        + authorityRepository
        + "\nauthorityServices="
        + new java.util.TreeSet<>(authorityServices)
        + "\nforbiddenDomain="
        + forbiddenDomain
        + "\nforbiddenRepository="
        + forbiddenRepository
        + "\nclassFiles="
        + report.classFiles()
        + "\nrules="
        + report.rules()
        + "\nconstructionPolicy=EXECUTED\n"
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
