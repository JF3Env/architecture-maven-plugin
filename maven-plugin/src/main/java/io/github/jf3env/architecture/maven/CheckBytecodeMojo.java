package io.github.jf3env.architecture.maven;

import io.github.jf3env.architecture.bytecode.BytecodeBaseline;
import io.github.jf3env.architecture.bytecode.BytecodePolicy;
import io.github.jf3env.architecture.bytecode.BytecodeReport;
import io.github.jf3env.architecture.bytecode.BytecodeRequest;
import io.github.jf3env.architecture.bytecode.BytecodeRules;
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

/** Complete context-first ArchUnit and construction-policy execution, independent of Surefire. */
@Mojo(
    name = "check-bytecode",
    defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
    requiresDependencyResolution = ResolutionScope.COMPILE)
public final class CheckBytecodeMojo extends AbstractMojo {
  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(required = true)
  private String basePackage;

  /** The shared kernel's single package segment below {@code basePackage}; never a context. */
  @Parameter(defaultValue = "platform")
  private String platformPackage;

  /** Fully qualified transaction-boundary type declared by the consumer's platform. */
  @Parameter private String unitOfWorkType;

  /** Fully qualified integration-event contract declared by the consumer's platform. */
  @Parameter private String integrationEventType;

  /** Fully qualified aggregate-root marker annotation declared by the consumer's platform. */
  @Parameter private String aggregateRootAnnotation;

  /** ArchUnit package patterns a bounded context's domain may never depend on. */
  @Parameter private List<String> frameworkPackages;

  /**
   * Frozen findings of a consumer with inherited debt. Every rule still runs over every class: a
   * finding that is not frozen fails the build, and so does a frozen entry that no longer occurs.
   */
  @Parameter private File baselineFile;

  /**
   * Creates a missing baseline from the current findings, or removes the entries that no longer
   * occur from an existing one. An existing baseline never gains an entry.
   */
  @Parameter(property = "architecture.baseline.update", defaultValue = "false")
  private boolean updateBaseline;

  @Parameter(
      defaultValue = "${project.build.directory}/architecture/bytecode-report.txt",
      readonly = true)
  private File reportFile;

  public CheckBytecodeMojo() {}

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    BytecodeReport report;
    BytecodePolicy policy;
    BytecodeBaseline.Verdict verdict = null;
    try {
      if ("pom".equals(project.getPackaging())) {
        throw new IllegalArgumentException(
            "Apply check-bytecode to a complete application module, not a POM aggregator");
      }
      policy =
          new BytecodePolicy(
              basePackage,
              platformPackage,
              unitOfWorkType,
              integrationEventType,
              aggregateRootAnnotation,
              frameworkPackages);
      var request =
          new BytecodeRequest(
              policy,
              Path.of(project.getBuild().getOutputDirectory()),
              project.getCompileClasspathElements().stream().map(Path::of).toList());
      report = new BytecodeRules().analyze(request);
      if (baselineFile != null) verdict = baseline(report).judge(report);
      writeReport(render(policy, report, verdict));
    } catch (Exception failure) {
      try {
        writeReport("ANALYSIS_ERROR\n" + failure + "\n");
      } catch (IOException reportFailure) {
        failure.addSuppressed(reportFailure);
      }
      throw new MojoExecutionException(
          "Architecture bytecode analysis could not complete: " + failure.getMessage(), failure);
    }
    getLog().info("Architecture contexts: " + report.contexts());
    report.rules().forEach(rule -> getLog().info("Architecture rule: " + rule));
    if (verdict != null) {
      judged(report, verdict);
      return;
    }
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
                + " context-first rules and construction policy passed; "
                + report.classFiles()
                + " application classes inspected");
  }

  private BytecodeBaseline baseline(BytecodeReport report) throws IOException {
    var path = baselineFile.toPath();
    if (!Files.exists(path) && !updateBaseline) {
      throw new IOException(
          "Missing architecture baseline "
              + path
              + "; create it once with -Darchitecture.baseline.update=true");
    }
    var baseline =
        Files.exists(path)
            ? BytecodeBaseline.parse(Files.readAllLines(path))
            : BytecodeBaseline.freeze(report);
    if (!updateBaseline) return baseline;
    var tightened = baseline.tightened(report);
    if (path.getParent() != null) Files.createDirectories(path.getParent());
    Files.writeString(path, String.join("\n", header(tightened.entries())) + "\n");
    return tightened;
  }

  private List<String> header(List<String> entries) {
    var lines = new ArrayList<String>();
    lines.add(
        "# Frozen check-bytecode findings. Every rule applies to everything that is not here.");
    lines.add("# This file only shrinks: fix a finding, then remove its line or run");
    lines.add("# -Darchitecture.baseline.update=true, which never adds an entry.");
    lines.addAll(entries);
    return lines;
  }

  private void judged(BytecodeReport report, BytecodeBaseline.Verdict verdict)
      throws MojoFailureException {
    verdict.introduced().forEach(getLog()::error);
    verdict
        .resolved()
        .forEach(entry -> getLog().error("Resolved, remove it from the baseline: " + entry));
    if (report.classFiles() == 0 || report.rules().isEmpty() || !verdict.passed()) {
      throw new MojoFailureException(
          "Architecture bytecode contracts violated beyond the baseline ("
              + verdict.introduced().size()
              + " introduced, "
              + verdict.resolved().size()
              + " resolved but still frozen); see "
              + reportFile);
    }
    getLog()
        .info(
            "Architecture: "
                + report.rules().size()
                + " context-first rules and construction policy executed; nothing beyond the "
                + verdict.frozen()
                + " frozen findings of "
                + baselineFile
                + "; "
                + report.classFiles()
                + " application classes inspected");
  }

  private String render(
      BytecodePolicy policy, BytecodeReport report, BytecodeBaseline.Verdict verdict) {
    var lines = new ArrayList<String>();
    lines.add(
        report.passed()
            ? "PASSED"
            : verdict != null && verdict.passed()
                ? "FROZEN"
                : report.errors().isEmpty() ? "VIOLATIONS" : "ANALYSIS_ERROR");
    lines.add("basePackage=" + policy.basePackage());
    lines.add("platformPackage=" + policy.platformPackage());
    lines.add("unitOfWorkType=" + policy.unitOfWorkType());
    lines.add("integrationEventType=" + policy.integrationEventType());
    lines.add("aggregateRootAnnotation=" + policy.aggregateRootAnnotation());
    lines.add("frameworkPackages=" + policy.frameworkPackages());
    lines.add("contexts=" + report.contexts());
    lines.add("classFiles=" + report.classFiles());
    lines.add("rules=" + report.rules());
    lines.add("constructionPolicy=EXECUTED");
    if (verdict != null) {
      lines.add("baseline=" + baselineFile);
      lines.add("frozen=" + verdict.frozen());
      verdict.introduced().forEach(finding -> lines.add("INTRODUCED | " + finding));
      verdict.resolved().forEach(entry -> lines.add("RESOLVED | " + entry));
    }
    lines.addAll(report.violations());
    lines.addAll(report.errors());
    return String.join("\n", lines) + "\n";
  }

  private void writeReport(String content) throws IOException {
    var path = reportFile.toPath();
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
  }
}
