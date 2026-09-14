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
with `AvoidOptionalGet` on `Workspace.java`, before Surefire.

Slice 2 adds `check-bytecode`, with the original 43 required identities and the allocation/factory/
producer policy. Its typed catalog is independent of JUnit. Required identities, duplicate/null
registration rejection and automatic execution of additions replace reflection over `@ArchTest`
fields. The per-rule empty-selection guard is explicit. Authority identities are constructor inputs,
not constants or global system properties. The classloader is scoped to one request and always restored;
weakened/custom ArchUnit resolution is rejected without mutating the caller's configuration.

114 existing compiled counterexamples moved into the library. Five inventory tests preserve the
registration guarantees and ten additional tests cover consumer policies and class evidence. The
library currently runs 167 tests. Eleven Maven consumers include a complete `orders` example and
intended rejections for multiple owners, generated domain construction, misplaced factories and
corrupt bytecode. Consumer processes resolve installed artifacts in the isolated Invoker repository;
pre/post-build helpers only prepare and inspect their worktrees.

The reference initially ran both bytecode executors: both approved 35 classes and the 43-rule catalog.
It then removed the legacy implementation and retained its six typed source checkers in a separate
`SourceArchitectureGate`. Its existing Maven lifecycle negatives now assert the exact failing goal.
Slice 4 remains pending.

ARCH-4 replaces the six configured authority values with authorities derived from the compiled
inventory. Every `domain.<X>` may declare at most one aggregate root in `domain.<X>.aggregate` and
at most one repository interface, which must reside at `domain.<X>` when present and then requires
the aggregate root; services in `domain.<X>.services..` may depend only on their own domain's root
repository, and using it requires using the aggregate root. The identities
`DOMAINS_HAVE_A_SINGLE_PERSISTENCE_AUTHORITY`, `SERVICES_USE_THEIR_DOMAIN_REPOSITORY` and
`SERVICES_USE_THEIR_DOMAIN_AGGREGATE` replace the three configured authority rules, the catalog
keeps its 43 entries, and the report now prints one `authority[<X>]=aggregate=... repository=...`
line per domain. This is a breaking change for bytecode execution configuration: `authorityDomain`,
`forbiddenDomain`, `authorityAggregate`, `authorityRepository`, `authorityServices` and
`forbiddenRepository` are no longer accepted.

Slice 3 moves the reference's `SourceArchitectureGate` into the library. Six typed checkers become
`io.github.jf3env.architecture.source`: `LombokConstructorRule` (`LombokSimpleConstructor`),
`LombokAccessorRule` (`LombokSimpleAccessor`), `StaticExceptionFactoryRule` (`StaticExceptionFactory`),
`AggregateMutationRule` (`AggregateInvariantSetter`), `ExceptionConstructionRule` (`ExceptionSelfFactory`)
and `PersistenceBoundaryRule` (`PersistenceBoundary`). They run in the existing `check` goal, in the same
PMD session as the four packaged XML rules and the `SOURCE_INVENTORY` scope rule, so `check` reports ten
rule identities instead of four. The IOSP structural analysis moves into
`io.github.jf3env.architecture.iosp` (`IospAnalysis`, `IospRule`, `IospSources`, `IospCallPolicy`,
`CompiledInvocations`, `VerifiedAccessors`, `VerifiedConstruction`, `VerifiedExceptionFactories`) and runs
as its own goal `check-iosp`, not as part of `check`: IOSP requires a complete whole-inventory
source/class provenance proof — nest membership, generated-mapper origin and freshness, complete
backend-package coverage — which is a strictly stronger precondition than the per-file typed and XML
rules. Separate goals keep the two contracts independently verifiable.

The substance of this slice is parameterization of what the reference hardcoded.
`ExceptionConstructionRule` replaces the fixed `com.ai.label.` prefix with the consumer `basePackage`.
`PersistenceBoundaryRule` replaces the fixed
`com.ai.label.persistence.workspace.WorkspaceTransactions` with the required plugin parameter
`persistenceBoundary`, a fully-qualified class name. That value is supplied explicitly rather than derived
from the compiled inventory because it names a single application-wide contract, not a per-domain
authority as in ARCH-4. `IospRule` derives `com.ai.label.`, `com.ai.label.domain.` and
`com.ai.label.infra.domains.producers` from `basePackage`, and `IospSources` derives its
`com/ai/label/(domain|persistence|infra)/...` package regex from the same value.
`LombokConstructorRule`, `LombokAccessorRule`, `AggregateMutationRule`, `StaticExceptionFactoryRule` and
the `Verified*`/`CompiledInvocations` proof classes were already name- and package-independent and moved
unchanged apart from their package declaration. Every moved checker keeps its identity, severity, scope,
positive controls and intended negative diagnostics; the library and its Maven consumers preserve the
existing source and bytecode proofs. TODO(evidence): record the verified library test count, consumer
count and reference-project totals for this slice once observed.

This is a breaking change for consumers of `check`: `<persistenceBoundary>` is now required. The new goal
`check-iosp` binds to `process-test-classes` by default and requires `<basePackage>`. IOSP structural
analysis inspects only the consumer's primary handwritten source root
(`project.build.sourceDirectory`); additional compile source roots remain covered by the typed source
checkers and the XML rulesets, but not by the IOSP whole-inventory proof. This matches the reference
project's pre-extraction behaviour, which also passed a single source root to its IOSP gate.

| Slice | Contracts | Owner after migration | Required evidence |
| --- | --- | --- | --- |
| 1 — Source rules | ARCH-09 `NoStaticMethods`, `RequireTypeImports`, `AvoidOptionalGet`, `DomainMethodsMustNotReturnNull` | Packaged rulesets and Maven `check` | Original controls, another base package, real Maven, restricted tests, suppression/error/empty-input rejection |
| 2 — Bytecode architecture | 43 required ArchUnit identities, construction/factory/producer checks | Analysis library and Maven adapter | Identity inventory, all existing compiled counterexamples, generated construction, complete class inventory |
| 3 — Typed source checks | Six custom source rules and IOSP, including source/class provenance | Analysis library and Maven adapter, goals `check` and `check-iosp` | Existing source/bytecode proofs, classloader isolation, generation, workspace policy equivalence |
| 4 — Final reference adoption | Complete mandatory architecture gate | Reference project's pinned plugin | Full reference `test` and `clean verify`, lifecycle negatives, current evidence |

Each slice is an independently verifiable replacement. The report names only the rules actually run.
The source slice does not replace the reference's complete class/source provenance checks, behavioral
tests, Error Prone/NullAway, coverage, mutation, metrics or formatting. Those gates retain their scope.

The initial compatibility target is Maven 3.9.16 and JDK 24. The full architecture policy targets a
complete domain/persistence/infra application in one module. Cross-module ownership and additional
Java versions require separate integration proofs. A parent POM is not required for consumption.
