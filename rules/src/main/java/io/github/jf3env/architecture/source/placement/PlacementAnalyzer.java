package io.github.jf3env.architecture.source.placement;

import io.github.jf3env.architecture.source.placement.PlacementRule.TypeFact;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns collected declaration facts into type-placement findings.
 *
 * <p>The contract is the consumer template's role vocabulary: a package that organizes a unit of
 * code keeps its types in the role folders {@code command}, {@code query}, {@code result}, {@code
 * value}, {@code exceptions}, {@code factory}, {@code mappers}, {@code dto}, {@code entities} and
 * {@code projection}. A type whose name announces a role but that is declared outside that role's
 * folder is reported.
 *
 * <p><b>Self-calibration.</b> This library is generic and must never impose a convention on a
 * consumer that does not use one. A finding therefore requires evidence that the convention is
 * already in use <em>at that exact point of the tree</em>: the expected role package must already
 * exist, that is, some analyzed compilation unit must declare it. If {@code value} does not exist
 * next to the type, a misnamed value type is not a finding. Existence is read from the analyzed
 * sources rather than from the filesystem, so an empty directory — a folder nobody has adopted —
 * does not calibrate anything.
 *
 * <p>The expected package is a child of the declaring package ({@code orders} → {@code
 * orders.factory}) unless the declaring package is itself a role folder, in which case the expected
 * package is its sibling ({@code orders.value} → {@code orders.factory}).
 *
 * <p><b>Why {@code enum} and {@code record} are not classified.</b> The role vocabulary maps a name
 * suffix to a folder, and that mapping is decidable from sources. "A value object belongs in {@code
 * value}" is not: no source-only criterion separates a value {@code enum} from a state or status
 * {@code enum} that belongs to the type owning the state, and a bodyless {@code record} is just as
 * often a DTO, a projection, a query result or a transport shape as it is a value. Every criterion
 * available here — "no business method", "not nested" — accepts all of those, so it would report
 * types whose current folder is correct. Under-reporting is the intended failure mode of an
 * advisory, so {@code enum} and {@code record} are classified by their name suffix like every other
 * type and never by their declaration kind.
 *
 * <p><b>Why JAX-RS providers are excluded from {@code mappers}.</b> A {@code mappers} folder holds
 * mapping collaborators — in practice MapStruct mappers. A type implementing {@code
 * jakarta.ws.rs.ext.ExceptionMapper} is not one: it is a JAX-RS provider bound to the inbound REST
 * adapter, and moving it into {@code mappers} would be an incorrect change, which is worse than
 * staying quiet. Knowing one framework name by heart is the same concession the pass-through
 * detector already makes for {@code jakarta.enterprise.inject.Produces}. Two source-only signals
 * exclude such a type, and either one alone is enough:
 *
 * <ol>
 *   <li>the declaration's {@code implements} clause names a type whose simple name is {@code
 *       ExceptionMapper}, which catches {@code class Foo implements ExceptionMapper<Bar>} even when
 *       the type's own name does not end in {@code Mapper};
 *   <li>the type's simple name ends in {@code ExceptionMapper}, which catches the case where the
 *       interface arrives through a hierarchy the source does not show — an abstract provider base,
 *       or a provider interface of the consumer's own.
 * </ol>
 *
 * <p>The exclusion is scoped to the {@code mappers} role. A name ending in {@code ExceptionMapper}
 * also ends in {@code Mapper}, and {@code Mapper} is the longest matching suffix, so no other role
 * can be reached by these names and no other role is affected.
 *
 * <p>Confidence is MEDIUM on purpose: a name suffix is a convention signal, not proof of intent, so
 * a finding means "this type reads like a role the folder next to it already models", not "this
 * type must move".
 */
public final class PlacementAnalyzer {

  /** Name suffix to role folder. Longest matching suffix wins. */
  private static final Map<String, String> ROLES = roles();

  /** The one role whose suffix collides with a framework name, so the only one that is guarded. */
  private static final String MAPPERS = "mappers";

  /**
   * The simple name of {@code jakarta.ws.rs.ext.ExceptionMapper}. Matching is on the simple name
   * because this analysis has no type resolution, so a fully qualified reference in the source and
   * a plain imported one must both be recognized.
   */
  private static final String JAXRS_EXCEPTION_MAPPER = "ExceptionMapper";

  /** The full role vocabulary, used to tell a role folder from an ordinary package. */
  private static final Set<String> ROLE_FOLDERS =
      Set.of(
          "command",
          "query",
          "result",
          "value",
          "exceptions",
          "factory",
          "mappers",
          "dto",
          "entities",
          "projection");

  private PlacementAnalyzer() {}

  private static Map<String, String> roles() {
    var roles = new LinkedHashMap<String, String>();
    roles.put("Factory", "factory");
    roles.put("Reconstruction", "factory");
    roles.put("Exception", "exceptions");
    roles.put("Result", "result");
    roles.put("Projection", "projection");
    roles.put("Command", "command");
    roles.put("Query", "query");
    roles.put("Mapper", "mappers");
    roles.put("Value", "value");
    return Map.copyOf(roles);
  }

  public static List<PlacementFinding> analyze(List<TypeFact> types, Set<String> packages) {
    var findings = new ArrayList<PlacementFinding>();
    for (var type : types) {
      var finding = classify(type, packages);
      if (finding != null) {
        findings.add(finding);
      }
    }
    findings.sort(
        Comparator.comparing(PlacementFinding::file).thenComparingInt(PlacementFinding::line));
    return findings;
  }

  private static PlacementFinding classify(TypeFact type, Set<String> packages) {
    if (isTest(type)) {
      return null;
    }
    var role = roleOf(type.typeName());
    if (role == null || isJaxrsProvider(type, role)) {
      return null;
    }
    var current = type.packageName();
    var folder = lastSegment(current);
    if (folder.equals(role)) {
      return null;
    }
    var expected =
        ROLE_FOLDERS.contains(folder) ? parentOf(current) + "." + role : current + "." + role;
    if (expected.startsWith(".") || !packages.contains(expected)) {
      return null;
    }
    return new PlacementFinding(
        role,
        "MEDIUM",
        type.file(),
        type.line(),
        type.typeName(),
        current,
        expected,
        "misplaced type: "
            + type.typeName()
            + " is declared in "
            + current
            + ", but its name announces the "
            + role
            + " role and "
            + expected
            + " already exists next to it",
        "move it with git mv to "
            + expected.replace('.', '/')
            + " and update the imports of every reference");
  }

  /**
   * The longest name suffix that maps to a role. A type named exactly like a suffix ({@code
   * Factory}) is left alone: the name announces nothing beyond the role folder itself.
   */
  private static String roleOf(String typeName) {
    String matched = null;
    for (var suffix : ROLES.keySet()) {
      if (typeName.length() > suffix.length()
          && typeName.endsWith(suffix)
          && (matched == null || suffix.length() > matched.length())) {
        matched = suffix;
      }
    }
    return matched == null ? null : ROLES.get(matched);
  }

  /**
   * A JAX-RS exception provider wearing the {@code Mapper} suffix. Either signal is conclusive on
   * its own: the {@code implements} clause naming {@code ExceptionMapper}, or a simple name ending
   * in {@code ExceptionMapper}. See the type javadoc for why this one framework name is hardcoded.
   */
  private static boolean isJaxrsProvider(TypeFact type, String role) {
    return MAPPERS.equals(role)
        && (type.implementedNames().contains(JAXRS_EXCEPTION_MAPPER)
            || type.typeName().endsWith(JAXRS_EXCEPTION_MAPPER));
  }

  /**
   * Test code is out of scope. The goal analyzes production roots only, so this is a second
   * defence: a type declared under a test source segment is never reported. A name-based test
   * filter would be unreachable — a name that ends with {@code Test} cannot also end with a role
   * suffix — so there is none.
   */
  private static boolean isTest(TypeFact type) {
    var path = type.file().replace('\\', '/');
    return path.contains("/src/test/") || path.contains("/test/java/");
  }

  private static String lastSegment(String packageName) {
    var separator = packageName.lastIndexOf('.');
    return separator < 0 ? packageName : packageName.substring(separator + 1);
  }

  private static String parentOf(String packageName) {
    var separator = packageName.lastIndexOf('.');
    return separator < 0 ? "" : packageName.substring(0, separator);
  }
}
