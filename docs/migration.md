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

## 1.0.0 — context-first

`0.4.0` is the last version of the layer-first contract (`<base>.(domain|persistence|infra).<domain>`).
`1.0.0` enforces the context-first shape of the sat-label plan (`plano-context-first.md`, Fase 7):
`<base>.<context>.{api,domain,application,infrastructure}` plus a shared `<base>.<platform>`. Bounded
contexts are derived from the compiled inventory, the way the persistence authority was derived in
ARCH-4; only the platform's marker types are named, with defaults below the platform package.

Retirements split by whether a rule *conflicts* with the target or is *orthogonal* to it. Retired because
they conflict: `DOMAIN_TYPES_ARE_NOT_RECORDS`, `LombokSimpleAccessor`, `LombokSimpleConstructor`,
`NoStaticMethods`, `StaticExceptionFactory`, `ExceptionSelfFactory`,
`SUBPACKAGES_DO_NOT_ACCESS_ANCESTOR_PACKAGES`, the factory-per-consumer and domain-product parts of the
construction policy, the per-domain producer, and every role-package rule (`DOMAIN_TYPES_DECLARE_THEIR_ROLE`,
`DOMAIN_SERVICES_RESIDE_IN_OWN_SERVICE_PACKAGES`, `SERVICE_CAPABILITIES_DECLARE_TYPE_ROLES`,
`DOMAIN_COMPONENTS_FOLLOW_STRUCTURAL_OWNERS`, `DOMAIN_INTERFACES_RESIDE_AT_THE_DOMAIN_ROOT`,
`DOMAIN_ROOT_CLASSES_ARE_INTERFACES`, `DOMAIN_EXCEPTIONS_LIVE_IN_EXCEPTIONS_PACKAGES`,
`APPLICATION_LAYER_IS_ABSENT`, the derived-authority trio). Kept because they are orthogonal: IOSP
(adapted, not deleted), one-constructor/one-construction-owner, `AggregateInvariantSetter`, and the
structural rules the plan marks MANTER. Rewritten over the new tree: `LAYER_COMMUNICATION` becomes
`LAYERS_POINT_INWARD`, `CLASSES_ARE_GROUPED_BY_LAYER_AND_DOMAIN` becomes `CLASSES_RESIDE_IN_CONTEXT_SHAPE`,
`DOMAINS_ARE_FREE_OF_CYCLES` becomes `CONTEXTS_ARE_FREE_OF_CYCLES`, the REST rules become
`REST_TALKS_ONLY_TO_APPLICATION`, `DOMAIN_SERVICES_DO_NOT_EXPOSE_AGGREGATES` becomes
`HANDLERS_DO_NOT_RETURN_AGGREGATES`, `TRANSACTION_ANNOTATIONS_BELONG_TO_PERSISTENCE` and the typed
`PersistenceBoundary` become `TRANSACTIONS_BELONG_TO_APPLICATION` and `PERSISTENCE_IS_THE_ONLY_JPA_USER`,
`PERSISTENCE_DOES_NOT_DEPEND_ON_DOMAIN_SERVICE_GATES` becomes `OUTBOUND_DOES_NOT_DEPEND_ON_APPLICATION`,
`DOMAIN_SERVICES_ARE_CONSTRUCTED_BY_INFRASTRUCTURE` becomes `ONE_PRODUCER_PER_CONTEXT`. Added from the plan:
`CONTEXTS_ONLY_TALK_THROUGH_API`, `API_IS_A_PUBLISHED_LANGUAGE`, `INTEGRATION_EVENTS_ARE_PUBLIC_RECORDS`,
`PLATFORM_DEPENDS_ON_NO_CONTEXT`. Aggregate roots are recognised by the consumer's marker annotation, not
by an `aggregate` package. `check-bytecode` executes 34 identities plus the construction policy;
`check` executes four identities plus `SOURCE_INVENTORY`.

Deliberate readings of the plan's literal suite, each recorded in the README: the platform belongs to no
context layer (so `api` may implement `<platform>.domain.IntegrationEvent`), `<context>.infrastructure.wiring`
is exempt from the transaction and JPA ownership rules because it is the composition root, REST may depend
on `jakarta.enterprise..`, `api` may depend on `org.jspecify..`, records and enums are exempt from the
construction policy, and IOSP treats `Handler` types of the application layer as services.

A finding worth keeping in view: IOSP's creator-location contract (allocations only in `*Factory`,
`*Mapper`, `*Builder` or the context producer) collides with principle P11 of the plan, under which
records are constructed where they are consumed and forwarding factories are deleted. The consumer
fixture satisfies both by routing every allocation through a factory or a MapStruct mapper, exactly as the
reference project does today. Whether that is contorted code or acceptable discipline is the measurement
the handover asks for before any further IOSP decision; the plugin does not pre-empt it.

This is a breaking change for every consumer: the package grammar, the `persistenceBoundary` parameter
(removed), the rule identities and the report layout all change. New optional parameters:
`platformPackage`, `aggregateRootAnnotation`, `unitOfWorkType`, `integrationEventType`,
`frameworkPackages`. The library runs its 34 identities against compiled counterexamples in
`ContextFirstRulesTest`; the Maven consumers carry a two-context fixture (`orders` and `billing` over a
`platform`) so the context-boundary and cycle rules are exercised rather than vacuously true.
Adopting the shape in the reference project surfaced two corrections: `check-bytecode` treats types that
appear only as caught throwables as known by name (ArchUnit does not resolve them from the classpath),
`package-info` classes are exempt from the DTO, entity and mapper suffix rules, and `check-iosp` verifies
bytecode against the consumer compile classpath so a caught library exception resolves.
Verified on 2026-09-15 with `./mvnw clean verify`: 592 library tests and 14 Maven consumers, no failures.

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

## 1.1.0 — pass-through advisories

`1.1.0` adds one contract to `check` and changes no outcome. `PassthroughFactsCollector` is a fact
collector, not a gate: it runs inside the same PMD analysis, records per-method forwarding facts and
in-class call sites, and its state is read once the traversal is over — the same post-execution pattern
as the `SOURCE_INVENTORY` scope rule's visited count. `PassthroughAnalyzer` then classifies the facts
into the S1 (single-use forwarder), S2 (wrap-unwrap round trip) and S3 (forwarding chain) signatures
described in the README.

The findings are carried by a new `SourceReport.advisories()` component and printed by `check` at
`WARNING`, preceded by their count. `SourceReport.passed()` deliberately ignores the component, and a
four-argument constructor keeps every producer that has no advisories — `IospRules` — unchanged. The
consumer fixture `passthrough` proves the end-to-end contract: an S1 candidate is logged at `WARNING`,
written to `source-report.txt`, and the goal still approves the module.

The detector is imported from the reference project's standalone `PassthroughGate`, whose `main()` ran
its own PMD analysis and printed a ranking. That role now belongs to the Mojo, so only the rule, the
analyzer and the finding record crossed over; identity, severity, scope and the S1/S2/S3 classification
semantics are unchanged. Two adaptations were required to leave the reference project's context:
`ASTVariableAccess.getImage()` became the equivalent non-deprecated `getName()` so the library compiles
under `failOnWarning`, and the per-file grouping key is the file identifier's absolute path rather than
its `toString()`, so an advisory reads exactly like a violation diagnostic. The collector's rule name is
published as `PassthroughRule.NAME` so the goal can report the executed identity. No package name was
hard-coded in the original heuristic, so nothing had to be generalized: the only qualified name it knows
is `jakarta.enterprise.inject.Produces`.

## 1.1.0 — type-placement advisories

`1.1.0` adds a second advisory contract to `check` and, again, changes no outcome.
`TypePlacementFactsCollector` follows the same shape as the pass-through collector: it runs inside the
same PMD analysis, records the declared package of every compilation unit plus one fact per top-level
type, and its state is read once the traversal is over. `PlacementAnalyzer` then reports a type whose
name announces a role folder — `factory`, `exceptions`, `result`, `projection`, `command`, `query`,
`mappers`, `value` — while being declared outside it. The goal now reports six rule identities, and the
findings join `SourceReport.advisories()`, which `passed()` still ignores by construction.

The contract comes from the consumer template's role vocabulary, so the interesting migration decision
was how to execute a convention this library must not impose. A consumer that never adopted the role
folders would otherwise be flooded by a rule it never agreed to. The detector is therefore
self-calibrating: a finding requires the expected role package to already exist at that point of the
tree, proved by an analyzed source declaring it. Existence is read from the analyzed sources rather than
from the filesystem, so an empty directory nobody adopted calibrates nothing, and a role folder in
another part of the tree calibrates nothing either.

Running the detector against a 897-file consumer exposed the other way a name-suffix rule can be wrong:
of ten `PLACEMENT_MAPPERS` findings, eight were JAX-RS providers — `DomainExceptionMapper`,
`LabelNotFoundExceptionMapper` and friends — implementing `jakarta.ws.rs.ext.ExceptionMapper` while the
`mappers` folder of that code base holds MapStruct mappers. The suffix was right and the conclusion was
wrong: the suggested move would have broken the inbound REST adapter. Suggesting an incorrect change is
strictly worse than reporting nothing, so `1.1.0` excludes those providers from the `mappers` role using
the two signals a source-only analysis has, either of which is conclusive on its own: the `implements`
clause naming a type whose simple name is `ExceptionMapper`, and a type name ending in
`ExceptionMapper`. The first catches the provider whose own name does not end in `Mapper`; the second
catches the provider whose contract arrives through a hierarchy the source does not show. Hardcoding one
framework name is the concession the pass-through detector already made for
`jakarta.enterprise.inject.Produces`. Because `Mapper` is the longest matching suffix of any
`*ExceptionMapper` name, no other role could be reached by these names and none is affected.

Only the name-suffix mapping crossed over. Classifying `enum` and `record` by declaration kind was
considered and rejected: no source-only criterion separates a value `enum` from a state `enum`, or a
bodyless `record` from a DTO, a projection or a transport shape, so a kind-based rule would report types
whose folder is already correct. An advisory that cries wolf is worse than one that stays quiet, so both
kinds are classified by their name suffix like every other type. Package metadata, nested types, test
sources, a name that is only the role itself, and a type already in its role folder are never reported.

The consumer fixture `placement` proves the end-to-end contract: a `*Reconstruction` port declared beside
an adopted `factory` folder is logged at `WARNING` and written to `source-report.txt` with its `git mv`
target, a `*Value` type with no `value` folder next to it is not reported at all, an
`*ExceptionMapper` provider declared next to an adopted `mappers` folder is not reported either, and the
goal still approves the module.
