# Architecture Maven plugin

Reusable validation extracted incrementally from [java-ai-template](https://github.com/JF3Env/java-ai-template).
Since `1.0.0` the plugin enforces a **context-first** backend: packages by bounded context, four layers
per context, a small shared platform. It packages the source rules, the aggregate-invariant checker, the
34-rule ArchUnit catalog with its construction policy, and the IOSP structural analysis, executing them
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
every `<context>.domain..` package and the platform's), the `SOURCE_INVENTORY` scope rule, and the typed
checker `AggregateInvariantSetter`, which restricts mutable state of classes annotated with the aggregate
root marker to private validating setters with immediate rejecting guards. The goal reports four rule
identities. Static factory methods, records and plain construction are idiomatic and not checked. Unknown
evidence, PMD errors and suppressed violations fail the build.

### `check-bytecode`

Inventories every application class, including generated implementations; dependencies are only
resolution inputs. It requires complete imports and resolved direct dependencies, rejects empty, corrupt
and duplicate inventories, executes every registered rule, enforces the required identities and runs the
construction policy. Every required identity must select at least one class: a rule that checks nothing is
an analysis error, never an approval. Resolution cannot be disabled or replaced through ArchUnit
configuration, and the analyzer preserves its caller's context classloader and configuration.

The 34 identities, each with a compiled counterexample in the library's tests:

| Group | Identities |
| --- | --- |
| Shape | `CLASSES_RESIDE_IN_CONTEXT_SHAPE`, `CONTEXTS_ONLY_TALK_THROUGH_API`, `CONTEXTS_ARE_FREE_OF_CYCLES`, `LAYERS_POINT_INWARD`, `PLATFORM_DEPENDS_ON_NO_CONTEXT` |
| Published language | `API_IS_A_PUBLISHED_LANGUAGE`, `INTEGRATION_EVENTS_ARE_PUBLIC_RECORDS` |
| Layer ownership | `DOMAIN_IS_FRAMEWORK_FREE`, `TRANSACTIONS_BELONG_TO_APPLICATION`, `PERSISTENCE_IS_THE_ONLY_JPA_USER`, `REST_TALKS_ONLY_TO_APPLICATION`, `OUTBOUND_DOES_NOT_DEPEND_ON_APPLICATION`, `HANDLERS_DO_NOT_RETURN_AGGREGATES`, `ONE_PRODUCER_PER_CONTEXT` |
| Domain | `DOMAIN_REPOSITORIES_ARE_INTERFACES`, `DOMAIN_PACKAGES_ARE_NULL_MARKED`, `AGGREGATE_ROOTS_HAVE_PRIVATE_STATE`, `AGGREGATE_ROOTS_HAVE_NO_PUBLIC_SETTERS`, `DOMAIN_STATE_IS_PRIVATE`, `ONLY_AGGREGATES_REASSIGN_DOMAIN_STATE` |
| Boundaries | `TRANSFER_OBJECT_PACKAGES_CONTAIN_ONLY_TRANSFER_OBJECTS`, `TRANSFER_OBJECTS_BELONG_TO_DTO_PACKAGES`, `JPA_ENTITIES_FOLLOW_ENTITY_CONVENTIONS`, `ENTITY_PACKAGES_CONTAIN_ONLY_ENTITIES`, `REST_TRANSFER_OBJECTS_DO_NOT_LEAK_INNER_LAYERS`, `ENTITY_REPRESENTATIONS_DO_NOT_LEAK_INNER_LAYERS` |
| MapStruct | `MAPPERS_USE_MAPSTRUCT`, `MAPSTRUCT_MAPPERS_HAVE_A_MAPPER_PACKAGE`, `MAPSTRUCT_MAPPERS_ARE_INTERFACES`, `MAPSTRUCT_MAPPERS_HAVE_GENERATED_IMPLEMENTATIONS`, `MAPSTRUCT_MAPPER_METHODS_ARE_ABSTRACT`, `BOUNDARY_CARRIERS_ARE_ONLY_CONSTRUCTED_BY_MAPSTRUCT`, `BOUNDARY_CARRIER_SETTERS_ARE_ONLY_CALLED_BY_MAPSTRUCT`, `BOUNDARY_CARRIER_STATE_IS_PRIVATE` |

Two deliberate readings of the plan's suite: the platform belongs to no context layer, so `api` may use
`<platform>.domain` (integration events implement the platform marker) and the layer rule ignores
platform dependencies; and `<context>.infrastructure.wiring` is the composition root, so the transaction
and JPA ownership rules exempt it. REST may additionally depend on `jakarta.enterprise..`.

The construction policy (`CONSTRUCTION_POLICY`) requires exactly one constructor and at most one
construction owner per class. Records and enums are carriers constructed where they are consumed and are
exempt. The factory-per-consumer, domain-product and per-domain-producer checks of `0.4.0` are retired.

### `check-iosp`

Runs the Integration Operation Segregation Principle structural analysis over the consumer's primary
handwritten source root (`project.build.sourceDirectory`); additional compile source roots are covered by
`check` but not by the IOSP whole-inventory proof. It is a separate goal because it needs a whole-inventory
source/class provenance proof, a strictly stronger precondition than the per-file rules of `check`. IOSP is
kept in `1.0.0` because it constrains method bodies while the context-first rules constrain the package
graph; none of the new rules replaces it. Its ownership proof follows the shape above; the services whose
constructors must only wire collaborators are the `Service` and `Handler` types of a context's domain and
application layers, and the composition root that may allocate beans is `<context>.infrastructure.wiring`.

## Publishing

CI publishes snapshots to GitHub Packages after verification. Consumers of this private repository
need a GitHub token with package-read permission, configured in Maven settings under server ID `github`.
Declare `https://maven.pkg.github.com/JF3Env/architecture-maven-plugin` in `pluginRepositories` (and
`repositories` when consuming the rules library). Keep credentials outside the project.
For local development, the sibling checkout's `./mvnw install` is sufficient.
