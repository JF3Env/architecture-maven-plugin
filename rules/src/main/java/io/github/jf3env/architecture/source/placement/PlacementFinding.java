package io.github.jf3env.architecture.source.placement;

/**
 * A single misplaced-type finding.
 *
 * @param role the role folder the type's name maps to, e.g. {@code factory}
 * @param confidence MEDIUM (a naming convention observed in use, never proof of intent)
 * @param file absolute source path
 * @param line 1-based line of the flagged type declaration
 * @param typeName declared top-level type simple name
 * @param currentPackage the package the type is declared in
 * @param expectedPackage the existing role package the type belongs to
 * @param detail human-readable explanation
 * @param suggestion the move action
 */
public record PlacementFinding(
    String role,
    String confidence,
    String file,
    int line,
    String typeName,
    String currentPackage,
    String expectedPackage,
    String detail,
    String suggestion) {}
