package io.github.jf3env.architecture;

import io.github.jf3env.architecture.iosp.IospAnalysis;
import io.github.jf3env.architecture.iosp.IospSources;
import java.io.IOException;
import java.util.List;
import java.util.TreeSet;
import net.sourceforge.pmd.reporting.RuleViolation;

/**
 * IOSP structural-segregation analysis for one consumer's primary handwritten source root.
 *
 * <p>Kept independent of {@link SourceRules}: the whole-inventory source/class provenance proof it
 * requires (nest membership, generated-mapper origin and freshness, complete backend-package
 * coverage) is a strictly stronger precondition than the per-file typed and XML rules, so it runs
 * as its own isolated PMD analysis behind its own Maven goal.
 */
public final class IospRules {
  public IospRules() {}

  public SourceReport analyze(IospRequest request) throws IOException {
    var inventory =
        IospSources.inspect(
            request.sourceRoot(),
            request.classesDirectory(),
            request.generatedRoots(),
            request.basePackage());
    var report =
        IospAnalysis.analyze(
            request.sourceRoot(),
            request.classesDirectory(),
            request.generatedRoots(),
            request.basePackage(),
            request.classpath());
    var violations = new TreeSet<String>();
    report.getViolations().forEach(violation -> violations.add(diagnostic(violation)));
    report
        .getSuppressedViolations()
        .forEach(
            suppressed ->
                violations.add("SUPPRESSED " + diagnostic(suppressed.getRuleViolation())));
    var errors = new TreeSet<String>();
    report.getProcessingErrors().forEach(error -> errors.add(error.getDetail()));
    report.getConfigurationErrors().forEach(error -> errors.add(error.toString()));
    return new SourceReport(
        inventory.sources().size(),
        List.of("IospMixedAbstraction"),
        List.copyOf(violations),
        List.copyOf(errors));
  }

  private String diagnostic(RuleViolation violation) {
    return violation.getRule().getName()
        + " | "
        + violation.getFileId().getAbsolutePath()
        + ":"
        + violation.getBeginLine()
        + " | "
        + violation.getDescription();
  }
}
