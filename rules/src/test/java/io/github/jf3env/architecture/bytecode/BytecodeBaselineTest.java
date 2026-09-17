package io.github.jf3env.architecture.bytecode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BytecodeBaselineTest {
  private static final String FROZEN =
      "LAYERS | classes should point inward | Method <a.B.c()> calls <d.E.f()> in (B.java:12)";
  private static final String EMPTY = "API | Rule 'api' failed to check any classes";

  @Test
  void aFindingIsKeyedByIdentityAndDetailWithoutDescriptionLineNumbersOrLineBreaks() {
    assertEquals(
        "LAYERS | Method <a.B.c()> calls <d.E.f()> in (B.java)", BytecodeBaseline.key(FROZEN));
    assertEquals(
        BytecodeBaseline.key(FROZEN),
        BytecodeBaseline.key(FROZEN.replace(":12)", ":98)").replace("point inward", "reworded")));
    assertEquals(
        "POLICY | a.B: several owners [x, y]",
        BytecodeBaseline.key("POLICY | a.B: several owners\n   [x, y]"));
  }

  @Test
  void frozenFindingsPassAndEverythingElseIsIntroduced() {
    var baseline = BytecodeBaseline.freeze(report(List.of(FROZEN), List.of(EMPTY)));
    var moved = FROZEN.replace(":12)", ":40)");
    var same = baseline.judge(report(List.of(moved), List.of(EMPTY)));
    assertTrue(same.passed());
    assertEquals(2, same.frozen());
    var added =
        "LAYERS | classes should point inward | Method <a.B.g()> calls <d.E.f()> in (B.java:50)";
    var worse = baseline.judge(report(List.of(moved, added), List.of(EMPTY, "NEW | failed")));
    assertFalse(worse.passed());
    assertEquals(List.of(added, "NEW | failed"), worse.introduced());
    assertEquals(List.of(), worse.resolved());
  }

  @Test
  void aResolvedFindingMustLeaveTheBaselineAndTighteningNeverAddsAnEntry() {
    var baseline = BytecodeBaseline.freeze(report(List.of(FROZEN), List.of(EMPTY)));
    var added = "LAYERS | d | Method <a.B.g()> calls <d.E.f()> in (B.java:50)";
    var current = report(List.of(added), List.of(EMPTY));
    var verdict = baseline.judge(current);
    assertFalse(verdict.passed());
    assertEquals(List.of(BytecodeBaseline.key(FROZEN)), verdict.resolved());
    assertEquals(1, verdict.frozen());
    var tightened = baseline.tightened(current);
    assertEquals(List.of(BytecodeBaseline.key(EMPTY)), tightened.entries());
    assertEquals(List.of(added), tightened.judge(current).introduced());
  }

  @Test
  void aBaselineFileIgnoresCommentsAndRejectsDuplicates() {
    var key = BytecodeBaseline.key(FROZEN);
    var parsed = BytecodeBaseline.parse(List.of("# frozen", "", "  " + key + "  "));
    assertEquals(List.of(key), parsed.entries());
    assertEquals(
        "Duplicate architecture baseline entry: " + key,
        assertThrows(
                IllegalArgumentException.class, () -> BytecodeBaseline.parse(List.of(key, key)))
            .getMessage());
  }

  private static BytecodeReport report(List<String> violations, List<String> errors) {
    return new BytecodeReport(1, List.of("LAYERS"), List.of("orders"), violations, errors);
  }
}
