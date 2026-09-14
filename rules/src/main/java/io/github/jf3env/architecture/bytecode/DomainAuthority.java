package io.github.jf3env.architecture.bytecode;

import java.util.ArrayList;
import java.util.List;

/** The local persistence authority declared by one domain in the compiled inventory. */
public record DomainAuthority(
    String domain,
    List<String> aggregateRoots,
    List<String> repositories,
    List<String> rootRepositories) {
  public DomainAuthority {
    aggregateRoots = sorted(aggregateRoots);
    repositories = sorted(repositories);
    rootRepositories = sorted(rootRepositories);
  }

  public String aggregate() {
    return aggregateRoots.size() == 1 ? aggregateRoots.get(0) : null;
  }

  public String rootRepository() {
    return rootRepositories.size() == 1 ? rootRepositories.get(0) : null;
  }

  public String describe() {
    return "aggregate=" + orNone(aggregateRoots) + " repository=" + orNone(rootRepositories);
  }

  private static String orNone(List<String> candidates) {
    return candidates.size() == 1 ? candidates.get(0) : "none";
  }

  private static List<String> sorted(List<String> values) {
    var copy = new ArrayList<String>(values);
    copy.sort(String::compareTo);
    return List.copyOf(copy);
  }
}
