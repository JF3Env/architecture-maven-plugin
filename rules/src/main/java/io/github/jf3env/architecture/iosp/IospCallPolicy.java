package io.github.jf3env.architecture.iosp;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorCall;
import net.sourceforge.pmd.lang.java.ast.ASTMethodCall;
import net.sourceforge.pmd.lang.java.types.JMethodSig;
import net.sourceforge.pmd.lang.java.types.TypeTestUtil;

/** Resolved calls, not identifier spellings, determine the method's abstraction evidence. */
final class IospCallPolicy {
  private static final Set<String> STREAM_OWNERS =
      Set.of(
          "java.util.stream.Stream",
          "java.util.stream.BaseStream",
          "java.util.stream.IntStream",
          "java.util.stream.LongStream",
          "java.util.stream.DoubleStream");
  private static final Set<String> STREAM_GLUE =
      Set.of(
          "map",
          "mapToInt",
          "mapToLong",
          "mapToDouble",
          "mapToObj",
          "boxed",
          "flatMap",
          "flatMapToInt",
          "flatMapToLong",
          "flatMapToDouble",
          "filter",
          "toList",
          "collect",
          "forEach",
          "forEachOrdered",
          "findFirst",
          "findAny",
          "anyMatch",
          "allMatch",
          "noneMatch",
          "sequential",
          "parallel",
          "onClose",
          "close");
  private static final Set<String> OPTIONAL_OWNERS =
      Set.of(
          "java.util.Optional",
          "java.util.OptionalInt",
          "java.util.OptionalLong",
          "java.util.OptionalDouble");
  private static final Set<String> OPTIONAL_GLUE =
      Set.of(
          "empty",
          "of",
          "ofNullable",
          "map",
          "flatMap",
          "filter",
          "or",
          "orElse",
          "orElseGet",
          "orElseThrow",
          "ifPresent",
          "ifPresentOrElse",
          "isEmpty",
          "isPresent",
          "stream",
          "getAsInt",
          "getAsLong",
          "getAsDouble");
  private static final Map<String, Set<String>> DATA_GLUE =
      Map.of(
          "java.util.List",
          Set.of("of", "copyOf"),
          "java.util.Set",
          Set.of("of", "copyOf"),
          "java.util.Map",
          Set.of("of", "copyOf", "entry", "ofEntries"),
          "java.util.Collections",
          Set.of("emptyList", "emptySet", "emptyMap", "singletonList", "singleton", "singletonMap"),
          "java.util.Objects",
          Set.of("requireNonNull", "requireNonNullElse", "requireNonNullElseGet"),
          "java.lang.Enum",
          Set.of("name"),
          "java.lang.Throwable",
          Set.of("getMessage", "getCause", "getSuppressed"));
  private static final Set<String> COLLECTION_OWNERS =
      Set.of(
          "java.util.Collection",
          "java.util.SequencedCollection",
          "java.util.List",
          "java.util.Set",
          "java.util.SortedSet",
          "java.util.NavigableSet",
          "java.util.AbstractCollection",
          "java.util.AbstractList",
          "java.util.AbstractSequentialList",
          "java.util.AbstractSet",
          "java.util.ArrayList",
          "java.util.LinkedList",
          "java.util.HashSet",
          "java.util.LinkedHashSet",
          "java.util.TreeSet",
          "java.util.EnumSet",
          "java.util.Vector");
  // Dependency ownership does not make an API primitive: unknown external calls are delegation.
  private static final Set<String> PRIMITIVE_OWNERS =
      Set.of(
          "java.lang.Object",
          "java.lang.String",
          "java.lang.StringBuilder",
          "java.lang.StringBuffer",
          "java.lang.Math",
          "java.lang.StrictMath",
          "java.lang.Number",
          "java.lang.Byte",
          "java.lang.Short",
          "java.lang.Integer",
          "java.lang.Long",
          "java.lang.Float",
          "java.lang.Double",
          "java.lang.Boolean",
          "java.lang.Character",
          "java.lang.Enum",
          "java.math.BigDecimal",
          "java.math.BigInteger",
          "java.math.MathContext",
          "java.util.Arrays",
          "java.util.Collections",
          "java.util.Objects",
          "java.util.UUID",
          "java.util.Map",
          "java.util.HashMap",
          "java.util.LinkedHashMap",
          "java.util.TreeMap",
          "java.util.EnumMap",
          "java.util.Iterator",
          "java.util.ListIterator",
          "java.util.Comparator",
          "java.util.stream.Collectors",
          "java.util.regex.Pattern",
          "java.util.regex.Matcher",
          "java.util.HexFormat",
          "java.util.Base64",
          "java.util.Base64$Encoder",
          "java.util.Base64$Decoder",
          "java.nio.Buffer",
          "java.nio.ByteBuffer",
          "java.nio.CharBuffer",
          "java.nio.ShortBuffer",
          "java.nio.IntBuffer",
          "java.nio.LongBuffer",
          "java.nio.FloatBuffer",
          "java.nio.DoubleBuffer",
          "java.nio.ByteOrder",
          "java.nio.charset.Charset",
          "java.util.zip.CRC32",
          "java.security.MessageDigest",
          "java.time.LocalDate",
          "java.time.LocalTime",
          "java.time.LocalDateTime",
          "java.time.OffsetDateTime",
          "java.time.ZonedDateTime",
          "java.time.Duration",
          "java.time.Period",
          "java.time.ZoneId",
          "java.time.ZoneOffset",
          "java.time.format.DateTimeFormatter");
  private final VerifiedAccessors accessors;
  private final VerifiedConstruction construction;
  private final CompiledInvocations invocations;
  private final VerifiedExceptionFactories exceptionFactories;

  IospCallPolicy(ClassLoader loader) {
    this.accessors = new VerifiedAccessors(loader);
    this.construction = new VerifiedConstruction(loader);
    this.invocations = new CompiledInvocations(loader);
    this.exceptionFactories = new VerifiedExceptionFactories(loader);
  }

  private static boolean isPrimitive(String owner, String name) {
    switch (owner) {
      case "java.lang.System":
        return Set.of("arraycopy", "identityHashCode", "nanoTime", "currentTimeMillis")
            .contains(name);
      case "java.time.Instant":
        return !name.equals("now");
      case "java.nio.file.Path":
        return Set.of(
                "of",
                "resolve",
                "resolveSibling",
                "normalize",
                "relativize",
                "getParent",
                "getFileName",
                "getRoot",
                "getName",
                "getNameCount",
                "toString",
                "startsWith",
                "endsWith",
                "isAbsolute",
                "toAbsolutePath",
                "toUri")
            .contains(name);
      case null:
      default:
        break;
    }
    return PRIMITIVE_OWNERS.contains(owner)
        || COLLECTION_OWNERS.contains(owner)
        || STREAM_OWNERS.contains(owner)
        || OPTIONAL_OWNERS.contains(owner);
  }

  private static boolean isPlumbing(JMethodSig method, String owner, String name) {
    if ((STREAM_OWNERS.contains(owner) && STREAM_GLUE.contains(name))
        || (OPTIONAL_OWNERS.contains(owner) && OPTIONAL_GLUE.contains(name))) {
      return true;
    }
    if (DATA_GLUE.getOrDefault(owner, Set.of()).contains(name)
        || (owner.equals("java.lang.String")
            && Set.of("equals", "equalsIgnoreCase").contains(name))) {
      return true;
    }
    return Set.of("stream", "parallelStream", "size", "isEmpty", "add", "addAll").contains(name)
        && COLLECTION_OWNERS.contains(owner)
        && TypeTestUtil.isA(Collection.class, method.getDeclaringType());
  }

  Kind classify(JMethodSig method, Node node) {
    var owner = method.getSymbol().getEnclosingClass().getBinaryName();
    var name = method.getName();
    if (this.exceptionFactories.isFactory(method)) {
      return Kind.PLUMBING;
    }
    if (node instanceof ASTMethodCall call && this.construction.isConstructionStep(method)) {
      if (this.construction.hasExactReceiver(call, this.invocations::resolve)) {
        return Kind.CONSTRUCTION;
      }
      if (call.getOverloadSelectionInfo().isFailed()) {
        // A recovered signature does not silently exempt unproved virtual construction.
        return Kind.UNPROVEN;
      }
    }
    if (this.accessors.isAccessor(method) || isPlumbing(method, owner, name)) {
      return Kind.PLUMBING;
    }
    if (isPrimitive(owner, name)) {
      return Kind.IMPLEMENTATION;
    }
    return Kind.DELEGATION;
  }

  boolean isBuilderAllocation(ASTConstructorCall call) {
    return this.construction.isBuilderAllocation(call, this.invocations::resolve);
  }

  enum Kind {
    DELEGATION,
    CONSTRUCTION,
    IMPLEMENTATION,
    PLUMBING,
    UNPROVEN
  }
}
