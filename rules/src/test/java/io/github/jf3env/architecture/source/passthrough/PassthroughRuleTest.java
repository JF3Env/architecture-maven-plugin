package io.github.jf3env.architecture.source.passthrough;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jf3env.architecture.SourceReport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Positive and negative controls for the advisory pass-through detector. */
class PassthroughRuleTest {
  @TempDir Path directory;

  @Test
  void reportsAPrivateSingleUseForwarderAsS1() throws IOException {
    var findings =
        this.analyze(
            """
            class Fixture {
              int entry(int value) { return helper(value) + 1; }
              private int helper(int value) { return target(value); }
              int target(int value) { return value; }
            }
            """);
    assertEquals(1, findings.size(), findings.toString());
    var finding = findings.get(0);
    assertEquals("S1", finding.kind());
    assertEquals("MEDIUM", finding.confidence());
    assertEquals("helper", finding.method());
    assertEquals("Fixture", finding.className());
    assertTrue(finding.detail().contains("single-use forwarder"), finding.detail());
    assertTrue(finding.detail().contains("called solely by Fixture.entry"), finding.detail());
    assertTrue(finding.suggestion().contains("inline into Fixture.entry"), finding.suggestion());
  }

  @Test
  void reportsAWrapUnwrapRoundTripAsS2() throws IOException {
    var findings =
        this.analyze(
            """
            class Fixture {
              record Command(int value) {}
              int wrap(int value) { return unwrap(new Command(value)); }
              private int unwrap(Command command) { return command.value(); }
            }
            """);
    assertEquals(1, findings.size(), findings.toString());
    var finding = findings.get(0);
    assertEquals("S2", finding.kind());
    assertEquals("MEDIUM", finding.confidence());
    assertEquals("wrap", finding.method());
    assertTrue(finding.detail().contains("wrap-unwrap round trip"), finding.detail());
    assertTrue(
        finding.suggestion().contains("pass the raw values to unwrap"), finding.suggestion());
  }

  @Test
  void reportsOneMaximalForwardingChainAsS3() throws IOException {
    var findings =
        this.analyze(
            """
            class Fixture {
              int entry(int value) { return first(value); }
              private int first(int value) { return second(value); }
              private int second(int value) { return terminal(value); }
              int terminal(int value) { return value * 2; }
            }
            """);
    var chains = findings.stream().filter(finding -> finding.kind().equals("S3")).toList();
    assertEquals(1, chains.size(), findings.toString());
    var chain = chains.get(0);
    assertEquals("entry", chain.method());
    assertEquals("MEDIUM", chain.confidence());
    assertTrue(chain.detail().contains("entry -> first -> second -> terminal"), chain.detail());
    assertTrue(chain.detail().contains("2 single-use intermediate hop(s)"), chain.detail());
    assertTrue(
        chain.suggestion().contains("collapse Fixture.entry to call terminal directly"),
        chain.suggestion());
    assertTrue(
        chain.suggestion().contains("verify the public entry still has external callers"),
        chain.suggestion());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        """
        class Fixture {
          int a(int value) { return helper(value) + 1; }
          int b(int value) { return helper(value) + 2; }
          private int helper(int value) { return target(value); }
          int target(int value) { return value; }
        }
        """,
        """
        abstract class Base { abstract int run(int value); }
        class Fixture extends Base {
          @Override int run(int value) { return target(value); }
          int target(int value) { return value; }
        }
        """,
        """
        class Fixture {
          int entry(int value) { return helper(value) + 1; }
          @jakarta.enterprise.inject.Produces
          private int helper(int value) { return target(value); }
          int target(int value) { return value; }
        }
        """,
        """
        class Fixture {
          int entry(int value) { return helper(value) + 1; }
          private int helper(int value) { audit(value); return target(value); }
          void audit(int value) {}
          int target(int value) { return value; }
        }
        """,
        """
        class Fixture {
          private final Collaborator collaborator = new Collaborator();
          int entry(int value) { return helper(value) + 1; }
          private int helper(int value) { return collaborator.target(value); }
        }
        class Collaborator { int target(int value) { return value; } }
        """
      })
  void leavesJustifiedForwardingAlone(String source) throws IOException {
    assertEquals(List.of(), this.analyze(source));
  }

  @Test
  void anAdvisoryNeverFailsTheContract() throws IOException {
    var findings =
        this.analyze(
            """
            class Fixture {
              int entry(int value) { return helper(value) + 1; }
              private int helper(int value) { return target(value); }
              int target(int value) { return value; }
            }
            """);
    assertEquals(
        Set.of("S1"), findings.stream().map(PassthroughFinding::kind).collect(Collectors.toSet()));
    var report =
        new SourceReport(
            1,
            List.of(PassthroughRule.NAME),
            List.of(),
            List.of(),
            findings.stream().map(PassthroughFinding::detail).toList());
    assertTrue(report.passed(), report.toString());
    assertEquals(1, report.advisories().size());
    assertTrue(new SourceReport(1, List.of(PassthroughRule.NAME), List.of(), List.of()).passed());
  }

  private List<PassthroughFinding> analyze(String body) throws IOException {
    return PassthroughFixture.analyze(this.directory, PassthroughFixture.PACKAGE + body);
  }
}
