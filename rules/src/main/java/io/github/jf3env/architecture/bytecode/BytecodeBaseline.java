package io.github.jf3env.architecture.bytecode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The frozen findings of a consumer that adopts the target suite with inherited debt.
 *
 * <p>A baseline is a ratchet, not a suppression: every rule is executed over every class, a finding
 * that is not frozen fails the build, and so does a frozen finding that no longer occurs, so the
 * file can only shrink. A finding is keyed by its rule identity and detail without source line
 * numbers, which keeps the key stable while unrelated lines move; the rule description is left out
 * so that rewording a rule does not thaw its findings.
 */
public final class BytecodeBaseline {
  private static final Pattern LINE = Pattern.compile("(\\.java):\\d+\\)");
  private static final Pattern BREAK = Pattern.compile("\\s*\\R\\s*");
  private final Set<String> frozen;

  private BytecodeBaseline(Set<String> frozen) {
    this.frozen = frozen;
  }

  /** Blank lines and lines starting with {@code #} are comments. */
  public static BytecodeBaseline parse(List<String> lines) {
    var frozen = new TreeSet<String>();
    for (var line : lines) {
      var entry = line.strip();
      if (entry.isEmpty() || entry.startsWith("#")) continue;
      if (!frozen.add(entry)) {
        throw new IllegalArgumentException("Duplicate architecture baseline entry: " + entry);
      }
    }
    return new BytecodeBaseline(frozen);
  }

  /** The baseline a consumer starts from: everything the report found. */
  public static BytecodeBaseline freeze(BytecodeReport report) {
    return new BytecodeBaseline(new TreeSet<>(keys(report)));
  }

  public static String key(String finding) {
    var parts = finding.split(" \\| ", 3);
    var identified = parts.length == 3 ? parts[0] + " | " + parts[2] : finding;
    var single = BREAK.matcher(identified.strip()).replaceAll(" ");
    return LINE.matcher(single).replaceAll("$1)");
  }

  public Verdict judge(BytecodeReport report) {
    var introduced = new ArrayList<String>();
    for (var finding : findings(report)) {
      if (!frozen.contains(key(finding))) introduced.add(finding);
    }
    var resolved = new TreeSet<>(frozen);
    resolved.removeAll(keys(report));
    return new Verdict(
        List.copyOf(introduced), List.copyOf(resolved), frozen.size() - resolved.size());
  }

  /** This baseline without the entries the report no longer finds; it never gains an entry. */
  public BytecodeBaseline tightened(BytecodeReport report) {
    var retained = new TreeSet<>(frozen);
    retained.retainAll(keys(report));
    return new BytecodeBaseline(retained);
  }

  public List<String> entries() {
    return List.copyOf(frozen);
  }

  private static Set<String> keys(BytecodeReport report) {
    var keys = new TreeSet<String>();
    findings(report).forEach(finding -> keys.add(key(finding)));
    return keys;
  }

  private static List<String> findings(BytecodeReport report) {
    var findings = new ArrayList<>(report.violations());
    findings.addAll(report.errors());
    return findings;
  }

  /** Findings that are not frozen, frozen entries that no longer occur, and what stays frozen. */
  public record Verdict(List<String> introduced, List<String> resolved, int frozen) {
    public boolean passed() {
      return introduced.isEmpty() && resolved.isEmpty();
    }
  }
}
