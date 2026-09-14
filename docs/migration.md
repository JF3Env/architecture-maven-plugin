# Incremental extraction

Reference: `JF3Env/java-ai-template` at `b07dd55`.

## Baseline

On 2026-09-14 the unmodified architecture and application contracts passed `./mvnw test` and
`./mvnw clean verify`: 834 tests, no failures/errors/skips, 27/27 domain mutations killed.
The required coverage checks passed without changing thresholds or scopes.
Before this baseline, the documented Spotless fork was rebuilt and the offline Wrapper test's
Linux-only `/bin` executable lookup was corrected to use the host PATH. Neither correction
changes an architecture rule or the example application.

## Slices and acceptance

Slice 1 is implemented and consumed by the reference project's migration branch. Local validation:
38 library tests and six real Maven consumers passed. After replacing the local XML copies,
the reference passed both complete commands with 835 tests and 27/27 domain mutations killed.
The new lifecycle negative compiled successfully and failed specifically at `architecture:check`
with `AvoidOptionalGet` on `Workspace.java`, before Surefire. Slices 2–4 remain pending.

| Slice | Contracts | Owner after migration | Required evidence |
| --- | --- | --- | --- |
| 1 — Source rules | ARCH-09 `NoStaticMethods`, `RequireTypeImports`, `AvoidOptionalGet`, `DomainMethodsMustNotReturnNull` | Packaged rulesets and Maven `check` | Original controls, another base package, real Maven, restricted tests, suppression/error/empty-input rejection |
| 2 — Bytecode architecture | 43 required ArchUnit identities, construction/factory/producer checks | Analysis library and Maven adapter | Identity inventory, all existing compiled counterexamples, generated construction, complete class inventory |
| 3 — Typed source checks | Six custom source rules and IOSP, including source/class provenance | Analysis library and Maven adapter | Existing source/bytecode proofs, classloader isolation, generation, workspace policy equivalence |
| 4 — Final reference adoption | Complete mandatory architecture gate | Reference project's pinned plugin | Full reference `test` and `clean verify`, lifecycle negatives, current evidence |

Each slice is an independently verifiable replacement. The report names only the rules actually run.
The source slice does not replace the reference's complete class/source provenance checks, behavioral
tests, Error Prone/NullAway, coverage, mutation, metrics or formatting. Those gates retain their scope.

The initial compatibility target is Maven 3.9.16 and JDK 24. The full architecture policy targets a
complete domain/persistence/infra application in one module. Cross-module ownership and additional
Java versions require separate integration proofs. A parent POM is not required for consumption.
