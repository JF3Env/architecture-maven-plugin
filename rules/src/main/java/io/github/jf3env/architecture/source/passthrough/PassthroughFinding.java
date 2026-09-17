package io.github.jf3env.architecture.source.passthrough;

/**
 * A single pass-through smell finding.
 *
 * @param kind S1 (single-use forwarder), S2 (wrap-unwrap round-trip), S3 (forwarding chain)
 * @param confidence HIGH (fan-in exact / provably useless), MEDIUM (heuristic, review required)
 * @param file absolute source path
 * @param line 1-based line of the flagged method
 * @param className declaring class simple name
 * @param method method name with arity, e.g. {@code create/2}
 * @param detail human-readable explanation
 * @param suggestion the collapse/inline action, when applicable
 */
public record PassthroughFinding(
    String kind,
    String confidence,
    String file,
    int line,
    String className,
    String method,
    String detail,
    String suggestion) {}
