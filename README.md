# Architecture Maven plugin

Reusable validation extracted incrementally from [java-ai-template](https://github.com/JF3Env/java-ai-template).
The plugin packages the ARCH-09 source rules, the six typed source checkers, the 43-rule ArchUnit catalog
with its construction/factory/producer checks, and the IOSP structural analysis, executing them
independently of Surefire selection.
The [migration contract](docs/migration.md) identifies the complete extraction scope and its required evidence.

## Build

Use JDK 24 and the Maven 3.9.16 Wrapper:

```sh
./mvnw test
./mvnw clean verify
./mvnw install
```

`verify` exercises the packaged plugin in real Maven consumers using an isolated local repository.
`install` makes the artifacts available to a sibling checkout without publishing credentials.

## Consume

```xml
<plugin>
    <groupId>io.github.jf3env</groupId>
    <artifactId>architecture-maven-plugin</artifactId>
    <version>0.4.0-SNAPSHOT</version>
    <configuration>
        <basePackage>com.ai.label</basePackage>
        <persistenceBoundary>com.ai.label.persistence.workspace.WorkspaceTransactions</persistenceBoundary>
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

`<basePackage>` is required by every goal. `<persistenceBoundary>` is required by `check` and names the
fully-qualified transaction boundary class whose `execute` method delimits permitted EntityManager/JPA use.
It is configured explicitly because it names a single application-wide contract, unlike the per-domain
persistence authority, which is derived from the compiled inventory.

Run the lifecycle (`./mvnw test` or `./mvnw verify`) to compile the evidence before analysis.
Direct invocation of either goal requires already compiled production classes. Use an execution-qualified
goal such as `architecture:check-bytecode@architecture-bytecode-contracts` when the policy is configured
inside that execution. The goals do not inherit `skipTests`, `maven.test.skip` or `-Dtest` and do not initialize
application classes. Reports are written to `target/architecture/source-report.txt` and
`target/architecture/bytecode-report.txt` on acceptance, violation or analysis failure.

All handwritten main Java source roots are examined. Generated roots must be declared explicitly;
the default is `target/generated-sources/annotations`. A generated marker on handwritten code does
not exclude it. Every handwritten unit must belong to the configured `<basePackage>.<layer>.<domain>`
grammar. Test sources and dependency JARs provide no additional application inputs.

`check` executes `NoStaticMethods`, `RequireTypeImports`, `AvoidOptionalGet` and
`DomainMethodsMustNotReturnNull`, and, in the same PMD session, the six typed source checkers
`LombokSimpleConstructor`, `LombokSimpleAccessor`, `StaticExceptionFactory`, `AggregateInvariantSetter`,
`ExceptionSelfFactory` and `PersistenceBoundary`, alongside the `SOURCE_INVENTORY` scope rule. The goal
therefore reports ten rule identities. `LombokSimpleConstructor` and `LombokSimpleAccessor` require simple
constructors and mechanical accessors to be Lombok-generated. `StaticExceptionFactory` admits handwritten
production static methods only as direct exception self-factories. `AggregateInvariantSetter` restricts
mutable aggregate state to private validating setters with immediate rejecting guards. `ExceptionSelfFactory`
routes failures through custom exception self-factories inside `<basePackage>`. `PersistenceBoundary`
confines EntityManager/JPA capabilities to the `execute` method of the configured `<persistenceBoundary>`.
Unknown evidence, PMD errors and suppressed violations fail the build.

`check-iosp` runs the Integration Operation Segregation Principle structural analysis and binds to
`process-test-classes` by default. It requires `<basePackage>` and derives the domain and producer packages
from it. It is a separate goal because it needs a whole-inventory source/class provenance proof — nest
membership, generated-mapper origin and freshness, and complete backend-package coverage — which is a
strictly stronger precondition than the per-file typed and XML rules of `check`. Keeping the goals separate
keeps the two contracts independently verifiable.

IOSP analysis inspects only the consumer's primary handwritten source root
(`project.build.sourceDirectory`). Additional compile source roots are covered by the typed source checkers
and the XML rulesets, but not by the IOSP whole-inventory proof.

`check-bytecode` inventories every application class, including generated implementations; dependencies
are only resolution inputs. It requires complete imports and resolved direct dependencies, and rejects
empty, corrupt and duplicate inventories. It executes every registered rule, enforces required identities
and runs the construction/producer policy. Reports include the derived per-domain authority and rule
descriptions. Resolution cannot be disabled or replaced through ArchUnit configuration, and the
analyzer preserves its caller's context classloader and configuration.

Persistence authority is derived from the inventory, not configured: every `<basePackage>.domain.<X>`
may declare at most one aggregate root in `<basePackage>.domain.<X>.aggregate` and at most one
repository interface in the whole domain, which must reside at `<basePackage>.domain.<X>` when
present and then requires the aggregate root. Services in `domain.<X>.services..` may depend only on
their own domain's root repository, and using that repository requires using the aggregate root.
Domains without a repository remain computational. The bytecode policy still targets one complete
domain/persistence/infra application per module: all three layers and the types selected by mandatory
rules must be present, aggregator POMs and empty selections are not silently accepted, and each domain
needs its `<Domain>Producer` in `infra.domains.producers`.

The six typed source checkers and IOSP are no longer held in the reference project; they are executed by
`check` and `check-iosp`. They establish additional contracts, including handwritten-vs-generated
provenance and the configured transaction boundary's `execute` method. Bytecode mapper identity alone does
not prove source-generation provenance. Compiler, behavioral, coverage and mutation gates also retain their
own responsibilities; running these three plugin goals is not approval for the entire development standard.

## Publishing

CI publishes snapshots to GitHub Packages after verification. Consumers of this private repository
need a GitHub token with package-read permission, configured in Maven settings under server ID `github`.
Declare `https://maven.pkg.github.com/JF3Env/architecture-maven-plugin` in `pluginRepositories` (and
`repositories` when consuming the rules library). Keep credentials outside the project.
For local development, the sibling checkout's `./mvnw install` is sufficient.
