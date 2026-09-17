package io.github.jf3env.architecture.source.passthrough;

import io.github.jf3env.architecture.source.passthrough.PassthroughRule.CallSite;
import io.github.jf3env.architecture.source.passthrough.PassthroughRule.MethodFact;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns collected forwarding facts into pass-through findings.
 *
 * <p>S1 — single-use forwarder: a private, non-override, non-producer method whose body is a single
 * delegating call and which has exactly one in-class caller. For a private method the in-class
 * caller set is exact (private members are not visible outside the class file), so this is a
 * precise candidate. Confidence is MEDIUM on purpose: some single-use forwarders are legitimately
 * separate (IOSP forces an operation to leave a coordination scope), so the finding is "review
 * before inlining", not "must inline".
 *
 * <p>S3 — forwarding chain: an intra-class path of pure forwarders where each intermediate is
 * single-use. The head may be a public API entry; the point is the redundant middle hop(s) that
 * only re-wrap the same parameters. The suggestion is for the head to call the terminal directly.
 */
public final class PassthroughAnalyzer {

  private static final String KEY = "\u0000";

  private PassthroughAnalyzer() {}

  public static List<PassthroughFinding> analyze(List<MethodFact> methods, List<CallSite> calls) {
    var byFile = indexMethods(methods);
    var callers = callerMap(calls, byFile);
    var findings = new ArrayList<PassthroughFinding>();
    findings.addAll(singleUseForwarders(methods, byFile, callers));
    findings.addAll(wrapUnwrap(methods, byFile, callers));
    findings.addAll(forwardingChains(methods, byFile, callers));
    findings.sort(
        Comparator.comparing(PassthroughFinding::file).thenComparingInt(PassthroughFinding::line));
    return findings;
  }

  private static Map<String, Map<String, MethodFact>> indexMethods(List<MethodFact> methods) {
    var byFile = new LinkedHashMap<String, Map<String, MethodFact>>();
    for (var m : methods) {
      byFile.computeIfAbsent(m.file(), k -> new HashMap<>()).put(m.id(), m);
    }
    return byFile;
  }

  /** targetKey (file + method id) -> distinct calling methods in the same file. */
  private static Map<String, Set<String>> callerMap(
      List<CallSite> calls, Map<String, Map<String, MethodFact>> byFile) {
    var callers = new HashMap<String, Set<String>>();
    for (var c : calls) {
      if (!c.inClass()) {
        continue;
      }
      var target = byFile.getOrDefault(c.file(), Map.of()).get(c.callee());
      if (target == null || c.enclosingMethod().equals(target.id())) {
        continue;
      }
      callers
          .computeIfAbsent(targetKey(target), k -> new LinkedHashSet<>())
          .add(c.enclosingMethod());
    }
    return callers;
  }

  private static String targetKey(MethodFact m) {
    return m.file() + KEY + m.id();
  }

  private static List<PassthroughFinding> singleUseForwarders(
      List<MethodFact> methods,
      Map<String, Map<String, MethodFact>> byFile,
      Map<String, Set<String>> callers) {
    var out = new ArrayList<PassthroughFinding>();
    for (var m : methods) {
      if (!m.pureForward()
          || !m.forwardInClass()
          || !m.isPrivate()
          || m.isOverride()
          || m.isProducer()
          || m.forwardTarget() == null) {
        continue;
      }
      // The target must be a real method in the same class; otherwise this is a
      // legitimate delegation to a collaborator, not a collapsible middle layer.
      if (byFile.get(m.file()).get(m.forwardTarget()) == null) {
        continue;
      }
      var callerSet = callers.get(targetKey(m));
      if (callerSet == null || callerSet.size() != 1) {
        continue;
      }
      var caller = callerSet.iterator().next();
      var callerFact = byFile.get(m.file()).get(caller);
      var callerLabel =
          callerFact == null ? caller : callerFact.className() + "." + callerFact.methodName();
      out.add(
          new PassthroughFinding(
              "S1",
              "MEDIUM",
              m.file(),
              m.line(),
              m.className(),
              m.methodName(),
              "single-use forwarder: body is only "
                  + describeForward(m)
                  + ", called solely by "
                  + callerLabel,
              "inline into "
                  + callerLabel
                  + " unless IOSP requires the split (operation isolated from coordination)"));
    }
    return out;
  }

  /**
   * S2 — wrap-unwrap round trip: {@code m1} only packages its arguments into a freshly constructed
   * value object to hand to {@code m2}, a single-parameter, single-use method that immediately
   * reads the values back through accessors. The intermediate value object exists solely to ferry
   * the parameters. Confidence is MEDIUM: the value type may be reused elsewhere, so this is
   * "review before flattening", not "must flatten".
   */
  private static List<PassthroughFinding> wrapUnwrap(
      List<MethodFact> methods,
      Map<String, Map<String, MethodFact>> byFile,
      Map<String, Set<String>> callers) {
    var out = new ArrayList<PassthroughFinding>();
    for (var m : methods) {
      if (m.wrapTarget() == null) {
        continue;
      }
      var unwrap = byFile.get(m.file()).get(m.wrapTarget());
      if (unwrap == null || !unwrap.paramOnlyProjected() || !unwrap.isPrivate()) {
        continue;
      }
      var unwrapCallers = callers.get(targetKey(unwrap));
      if (unwrapCallers == null || unwrapCallers.size() != 1 || !unwrapCallers.contains(m.id())) {
        continue;
      }
      out.add(
          new PassthroughFinding(
              "S2",
              "MEDIUM",
              m.file(),
              m.line(),
              m.className(),
              m.methodName(),
              "wrap-unwrap round trip: "
                  + m.methodName()
                  + " constructs a value object only to hand it to "
                  + unwrap.methodName()
                  + ", which reads it back through accessors",
              "pass the raw values to "
                  + unwrap.methodName()
                  + " (or inline it) instead of wrapping them in a single-use value object"));
    }
    return out;
  }

  private static List<PassthroughFinding> forwardingChains(
      List<MethodFact> methods,
      Map<String, Map<String, MethodFact>> byFile,
      Map<String, Set<String>> callers) {
    var chains = new ArrayList<List<MethodFact>>();
    for (var head : methods) {
      if (!head.pureForward() || !head.forwardInClass() || head.forwardTarget() == null) {
        continue;
      }
      var chain = new ArrayList<MethodFact>();
      chain.add(head);
      var current = head;
      for (; ; ) {
        var next = byFile.get(current.file()).get(current.forwardTarget());
        if (next == null || !next.pureForward() || !next.forwardInClass()) {
          break;
        }
        var nextCallers = callers.get(targetKey(next));
        if (nextCallers == null || nextCallers.size() != 1 || !nextCallers.contains(current.id())) {
          break;
        }
        chain.add(next);
        current = next;
      }
      if (chain.size() >= 2) {
        chains.add(chain);
      }
    }
    // Keep only maximal chains: drop any chain whose head lies inside a longer reported chain.
    chains.sort(Comparator.comparingInt((List<MethodFact> c) -> c.size()).reversed());
    var covered = new HashSet<String>();
    var out = new ArrayList<PassthroughFinding>();
    for (var chain : chains) {
      if (covered.contains(chain.get(0).id() + "@" + chain.get(0).file())) {
        continue;
      }
      for (var m : chain) {
        covered.add(m.id() + "@" + m.file());
      }
      var terminal = terminalOf(chain.get(chain.size() - 1), byFile);
      var path = new StringBuilder();
      for (var i = 0; i < chain.size(); i++) {
        if (i > 0) {
          path.append(" -> ");
        }
        path.append(chain.get(i).methodName());
      }
      if (terminal != null) {
        path.append(" -> ").append(terminal.methodName());
      }
      var intermediates = chain.size() - 1;
      var headLabel = chain.get(0).className() + "." + chain.get(0).methodName();
      var terminalLabel = terminal == null ? "(chain tail)" : terminal.methodName();
      out.add(
          new PassthroughFinding(
              "S3",
              "MEDIUM",
              chain.get(0).file(),
              chain.get(0).line(),
              chain.get(0).className(),
              chain.get(0).methodName(),
              "forwarding chain ("
                  + path
                  + "): "
                  + intermediates
                  + " single-use intermediate hop(s)",
              "collapse "
                  + headLabel
                  + " to call "
                  + terminalLabel
                  + " directly"
                  + (chain.get(0).isPrivate()
                      ? ""
                      : "; verify the public entry still has external callers")));
    }
    return out;
  }

  private static MethodFact terminalOf(
      MethodFact lastForwarder, Map<String, Map<String, MethodFact>> byFile) {
    if (lastForwarder.forwardTarget() == null) {
      return null;
    }
    return byFile.get(lastForwarder.file()).get(lastForwarder.forwardTarget());
  }

  private static String describeForward(MethodFact m) {
    if (m.forwardTarget() == null) {
      return "a single call";
    }
    return "a single call to " + m.forwardTarget();
  }
}
