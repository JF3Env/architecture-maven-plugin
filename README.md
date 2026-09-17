# Architecture Maven plugin

Reusable validation extracted incrementally from [java-ai-template](https://github.com/JF3Env/java-ai-template).
Since `1.0.0` the plugin enforces a **context-first** backend: packages by bounded context, four layers
per context, a small shared platform. It packages the source rules, the aggregate-invariant checker, the
35-rule ArchUnit catalog with its construction policy, and the IOSP structural analysis, executing them
independently of Surefire selection. `0.4.0` is the last version of the previous *layer-first* contract.
The [migration contract](docs/migration.md) records every slice and its evidence.

## Build

Use JDK 24 and the Maven 3.9.16 Wrapper:

```sh
./mvnw test
./mvnw clean verify
./mvnw install
```

`verify` exercises the packaged plugin in real Maven consumers using an isolated local repository.
`install` makes the artifacts available to a sibling checkout without publishing credentials.

## The shape

Every handwritten type lives in the grammar below. Bounded contexts are **derived** from the compiled
inventory as the first-level packages under `basePackage` other than the platform; they are never
configured.

```
<basePackage>
├── Application                 the only type allowed directly in the base package
├── <platform>                  shared kernel: markers, ids, UnitOfWork; depends on no context
│   ├── domain                  AggregateRoot, IntegrationEvent, ...
│   ├── application             UnitOfWork
│   └── infrastructure          its implementation
└── <context>                   one per bounded context; package-info only at the root
    ├── api                     published language: records, interfaces, enums, exceptions, events
    ├── domain                  aggregates, values, ports, domain services
    ├── application             one handler per use case; owns the transaction
    └── infrastructure          inbound/rest, outbound/persistence, wiring (the one producer)
```

Three types live in the consumer's own platform and are referenced by name, with defaults derived from
`basePackage` and `platformPackage` (`platform` unless configured):

| Parameter | Default | Used by |
| --- | --- | --- |
| `aggregateRootAnnotation` | `<base>.<platform>.domain.AggregateRoot` | `check`, `check-bytecode` |
| `unitOfWorkType` | `<base>.<platform>.application.UnitOfWork` | `check-bytecode` |
| `integrationEventType` | `<base>.<platform>.domain.IntegrationEvent` | `check-bytecode` |

The marker annotation must be retained in class files (`CLASS` or `RUNTIME` retention).

## Consume

```xml
<plugin>
    <groupId>io.github.jf3env</groupId>
    <artifactId>architecture-maven-plugin</artifactId>
    <version>1.0.0</version>
    <configuration>
        <basePackage>com.ai.label</basePackage>
    </configuration>
    <executions>
        <execution>
            <id>architecture-source-contracts</id>
            <phase>process-test-classes</phase>
            <goals><goal>check</goal></goals>
        </execution>
        <execution>
            <id>architecture-bytecode-contracts</id>
            <phase>process-test-classes</phase>
            <goals><goal>check-bytecode</goal></goals>
        </execution>
        <execution>
            <id>architecture-iosp-contracts</id>
            <phase>process-test-classes</phase>
            <goals><goal>check-iosp</goal></goals>
        </execution>
    </executions>
</plugin>
```

`<basePackage>` is required by every goal. Optional: `<platformPackage>`, `<aggregateRootAnnotation>`,
`<unitOfWorkType>`, `<integrationEventType>` and `<frameworkPackages>` (ArchUnit package patterns the
domain may never depend on; default `jakarta..`, `io.quarkus..`, `org.hibernate..`, `com.fasterxml..`,
`org.apache.sis..`, `org.mapstruct..`).

Run the lifecycle (`./mvnw test` or `./mvnw verify`) to compile the evidence before analysis.
Direct invocation of a goal requires already compiled production classes. Use an execution-qualified
goal such as `architecture:check-bytecode@architecture-bytecode-contracts` when the policy is configured
inside that execution. The goals do not inherit `skipTests`, `maven.test.skip` or `-Dtest` and do not initialize
application classes. Reports are written to `target/architecture/source-report.txt`,
`target/architecture/bytecode-report.txt` and `target/architecture/iosp-report.txt` on acceptance,
violation or analysis failure.

All handwritten main Java source roots are examined. Generated roots must be declared explicitly;
the default is `target/generated-sources/annotations`. A generated marker on handwritten code does
not exclude it. Every handwritten unit must belong to the shape above: a layer package owns every type,
the base package owns only `Application`, and a context root owns only `package-info`. Test sources and
dependency JARs provide no additional application inputs.

### `check`

Executes `RequireTypeImports`, `AvoidOptionalGet` and `DomainMethodsMustNotReturnNull` (the last two over
every `<context>.domain..` package and the platform's), the `SOURCE_INVENTORY` scope rule, the typed
checker `AggregateInvariantSetter`, which restricts mutable state of classes annotated with the aggregate
root marker to private validating setters with immediate rejecting guards, and the advisory collectors
`PassthroughFactsCollector` and `TypePlacementFactsCollector`. The goal reports six rule identities.
Static factory methods, records and plain construction are idiomatic and not checked. Unknown evidence,
PMD errors and suppressed violations fail the build.

#### Pass-through advisories (WARNING, since `1.1.0`)

`PassthroughFactsCollector` records forwarding facts per method during the same analysis; once the
traversal is over, the facts are classified into three signatures and reported as **advisories** at
`WARNING`. They are written to the report as `advisories=<count>` followed by one
`PASSTHROUGH_<kind>/<confidence> | <file>:<line> | <Class>.<method>: <detail> -> <suggestion>` line each,
and they **never** change the outcome of the goal: `SourceReport.passed()` ignores them by construction.

| Kind | Confidence | Signature |
| --- | --- | --- |
| `S1` | MEDIUM | single-use forwarder: a private, non-`@Override`, non-`@Produces` method whose whole body is one in-class delegating call, with exactly one in-class caller |
| `S2` | MEDIUM | wrap-unwrap round trip: a method that only packages its arguments into a freshly constructed value object for a private, single-use method that reads them straight back through accessors |
| `S3` | MEDIUM | forwarding chain: an intra-class path of at least two pure forwarders whose intermediates are each single-use; only the maximal chain is reported |

Classification is deliberately conservative and reads sources alone: a pure forwarder is exactly one
`return`/expression statement that calls the same class and allocates nothing. Confidence is MEDIUM
because a single-use forwarder can be legitimate — IOSP forces an operation out of a coordination scope —
so a finding means "review before inlining", not "must inline".

#### Type-placement advisories (WARNING, since `1.1.0`)

`TypePlacementFactsCollector` records the declared package of every compilation unit and one fact per
top-level type; once the traversal is over, a type whose name announces a role is checked against the
folder it is declared in. Findings are reported as **advisories** at `WARNING`, one
`PLACEMENT_<ROLE>/MEDIUM | <file>:<line> | <Type>: <detail> -> <suggestion>` line each, and they
**never** change the outcome of the goal: `SourceReport.passed()` ignores them by construction.

The role vocabulary is the folder set `command`, `query`, `result`, `value`, `exceptions`, `factory`,
`mappers`, `dto`, `entities`, `projection`. A name suffix maps to a role — the longest match wins:

| Suffix | Role folder |
| --- | --- |
| `*Factory`, `*Reconstruction` | `factory` |
| `*Exception` | `exceptions` |
| `*Result` | `result` |
| `*Projection` | `projection` |
| `*Command` | `command` |
| `*Query` | `query` |
| `*Mapper` | `mappers` |
| `*Value` | `value` |

**The detector is self-calibrating and imposes nothing.** A finding requires evidence that the
convention is already in use at that exact point of the tree: the expected role package must already
exist, that is, some analyzed source must declare it. If `value` does not exist next to the type, a
`*Value` in the enclosing folder is not a finding; a `value` folder elsewhere in the tree calibrates
nothing. The expected package is a child of the declaring package (`orders` → `orders.factory`) unless
the declaring package is itself a role folder, in which case it is its sibling (`orders.value` →
`orders.factory`). Package metadata, nested types, test sources, a name that is only the role itself,
and a type already in its role folder are never reported.

**JAX-RS exception providers are excluded from `mappers`.** A `mappers` folder holds mapping
collaborators — in practice MapStruct mappers — and a type implementing
`jakarta.ws.rs.ext.ExceptionMapper` is not one, so suggesting it move there would be an incorrect
change. Two source-only signals exclude such a type, and either one alone is enough: the declaration's
`implements` clause names a type whose simple name is `ExceptionMapper`, which catches
`class Foo implements ExceptionMapper<Bar>` even when `Foo` does not end in `Mapper`; or the type's own
simple name ends in `ExceptionMapper`, which catches the case where the interface arrives through a
hierarchy the source does not show. Matching is on the simple name because there is no type resolution
here. The exclusion is scoped to `mappers`: `Mapper` is the longest matching suffix of any
`*ExceptionMapper` name, so no other role is affected. This is the same concession the pass-through
detector makes by knowing `jakarta.enterprise.inject.Produces`.

`enum` and `record` are classified by name suffix like every other type and never by declaration kind:
no source-only criterion separates a value `enum` from a state `enum`, or a bodyless `record` from a
DTO, a projection or a transport shape, so a kind-based rule would report types whose folder is already
correct. Under-reporting is the intended failure mode of an advisory.

### `check-bytecode`

Inventories every application class, including generated implementations; dependencies are only
resolution inputs. It requires complete imports and resolved direct dependencies, rejects empty, corrupt
and duplicate inventories, executes every registered rule, enforces the required identities and runs the
construction policy. Types that appear only as caught throwables are known by name without classpath
resolution; no rule needs more than the name of a caught exception. Package metadata (`package-info`)
may live in any package. Every required identity must select at least one class: a rule that checks nothing is
an analysis error, never an approval. Resolution cannot be disabled or replaced through ArchUnit
configuration, and the analyzer preserves its caller's context classloader and configuration.

The 35 identities, each with a compiled counterexample in the library's tests:

| Group | Identities |
| --- | --- |
| Shape | `CLASSES_RESIDE_IN_CONTEXT_SHAPE`, `CONTEXTS_ONLY_TALK_THROUGH_API`, `CONTEXTS_ARE_FREE_OF_CYCLES`, `LAYERS_POINT_INWARD`, `PLATFORM_DEPENDS_ON_NO_CONTEXT` |
| Published language | `API_IS_A_PUBLISHED_LANGUAGE`, `INTEGRATION_EVENTS_ARE_PUBLIC_RECORDS` |
| Layer ownership | `DOMAIN_IS_FRAMEWORK_FREE`, `TRANSACTIONS_BELONG_TO_APPLICATION`, `PERSISTENCE_IS_THE_ONLY_JPA_USER`, `REST_TALKS_ONLY_TO_APPLICATION`, `OUTBOUND_DOES_NOT_DEPEND_ON_APPLICATION`, `HANDLERS_DO_NOT_RETURN_AGGREGATES`, `ONE_PRODUCER_PER_CONTEXT` |
| Domain | `DOMAIN_REPOSITORIES_ARE_INTERFACES`, `DOMAIN_PACKAGES_ARE_NULL_MARKED`, `AGGREGATE_ROOTS_HAVE_PRIVATE_STATE`, `AGGREGATE_ROOTS_HAVE_NO_PUBLIC_SETTERS`, `DOMAIN_STATE_IS_PRIVATE`, `ONLY_AGGREGATES_REASSIGN_DOMAIN_STATE`, `DOMAIN_TYPES_ARE_CONSTRUCTED_BY_THEIR_DOMAIN` |
| Boundaries | `TRANSFER_OBJECT_PACKAGES_CONTAIN_ONLY_TRANSFER_OBJECTS`, `TRANSFER_OBJECTS_BELONG_TO_DTO_PACKAGES`, `JPA_ENTITIES_FOLLOW_ENTITY_CONVENTIONS`, `ENTITY_PACKAGES_CONTAIN_ONLY_ENTITIES`, `REST_TRANSFER_OBJECTS_DO_NOT_LEAK_INNER_LAYERS`, `ENTITY_REPRESENTATIONS_DO_NOT_LEAK_INNER_LAYERS` |
| MapStruct | `MAPPERS_USE_MAPSTRUCT`, `MAPSTRUCT_MAPPERS_HAVE_A_MAPPER_PACKAGE`, `MAPSTRUCT_MAPPERS_ARE_INTERFACES`, `MAPSTRUCT_MAPPERS_HAVE_GENERATED_IMPLEMENTATIONS`, `MAPSTRUCT_MAPPER_METHODS_ARE_ABSTRACT`, `BOUNDARY_CARRIERS_ARE_ONLY_CONSTRUCTED_BY_MAPSTRUCT`, `BOUNDARY_CARRIER_SETTERS_ARE_ONLY_CALLED_BY_MAPSTRUCT`, `BOUNDARY_CARRIER_STATE_IS_PRIVATE` |

Two deliberate readings of the plan's suite: the platform belongs to no context layer, so `api` may use
`<platform>.domain` (integration events implement the platform marker) and the layer rule ignores
platform dependencies; and `<context>.infrastructure.wiring` is the composition root, so the transaction
and JPA ownership rules exempt it. REST may additionally depend on `jakarta.enterprise..` and, since `1.1.0`, on
`com.fasterxml.jackson.annotation..`: naming a wire property is what a transfer object is for, while the
Jackson runtime (`databind`, `core`) stays outside.

The construction policy (`CONSTRUCTION_POLICY`) requires exactly one constructor and at most one
construction owner per class. Records and enums are carriers constructed where they are consumed and are
exempt, and so are the synthetic classes javac emits on its own (the `Outer$1` holder of an enum
switch map has no constructor and no source). The factory-per-consumer, domain-product and per-domain-producer checks of `0.4.0` are retired.

`DOMAIN_TYPES_ARE_CONSTRUCTED_BY_THEIR_DOMAIN` (since `1.1.0`) keeps the shape of an aggregate inside its
context's domain: a constructor call, a constructor reference or a `builder()` call on a class of
`<context>.domain..` is accepted only from that same domain. Records and enums are carriers and exempt, the
composition root is exempt because it produces a context's long-lived collaborators whichever way they are
built, and a generated MapStruct implementation may rebuild a product through its builder. An outer layer that needs a domain product calls a factory the domain
publishes.

#### Adopting the suite with inherited findings

```xml
<execution>
    <id>bytecode</id><goals><goal>check-bytecode</goal></goals>
    <configuration><baselineFile>${project.basedir}/architecture-baseline.txt</baselineFile></configuration>
</execution>
```

With a `baselineFile` every rule still runs over every class. A finding that is not frozen fails the build,
and so does a frozen entry that no longer occurs, so the file only shrinks. Findings are keyed by rule identity
and detail without source line numbers; rule-evaluation errors (a rule that selects no class) are frozen the
same way, while an incomplete or corrupt inventory always fails. `-Darchitecture.baseline.update=true` creates
a missing file from the current findings and removes resolved entries from an existing one; it never adds an
entry to an existing file. The report starts with `FROZEN` and lists `INTRODUCED` and `RESOLVED` lines.

### `check-iosp`

Runs the Integration Operation Segregation Principle structural analysis over the consumer's primary
handwritten source root (`project.build.sourceDirectory`); additional compile source roots are covered by
`check` but not by the IOSP whole-inventory proof. It is a separate goal because it needs a whole-inventory
source/class provenance proof, a strictly stronger precondition than the per-file rules of `check`. IOSP is
kept in `1.0.0` because it constrains method bodies while the context-first rules constrain the package
graph; none of the new rules replaces it. Its bytecode verification resolves type hierarchies against the
consumer compile classpath, so a caught library exception is resolvable. Its ownership proof follows the shape above; the services whose
constructors must only wire collaborators are the `Service` and `Handler` types of a context's domain and
application layers, and the composition root that may allocate beans is `<context>.infrastructure.wiring`.

## Publishing

CI publishes snapshots to GitHub Packages after verification. Consumers of this private repository
need a GitHub token with package-read permission, configured in Maven settings under server ID `github`.
Declare `https://maven.pkg.github.com/JF3Env/architecture-maven-plugin` in `pluginRepositories` (and
`repositories` when consuming the rules library). Keep credentials outside the project.
For local development, the sibling checkout's `./mvnw install` is sufficient.
