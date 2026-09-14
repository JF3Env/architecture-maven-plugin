# Architecture Maven plugin

Reusable validation extracted incrementally from [java-ai-template](https://github.com/JF3Env/java-ai-template).
The plugin packages the ARCH-09 source rules, the 43-rule ArchUnit catalog and construction/factory/
producer checks, executing them independently of Surefire selection.
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
    <version>0.2.0-SNAPSHOT</version>
    <configuration>
        <basePackage>com.company.product</basePackage>
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
            <configuration>
                <authorityDomain>orders</authorityDomain>
                <forbiddenDomain>assets</forbiddenDomain>
                <authorityAggregate>com.company.product.domain.orders.aggregate.Order</authorityAggregate>
                <authorityRepository>com.company.product.domain.orders.OrderRepository</authorityRepository>
                <authorityServices><service>OrderService</service></authorityServices>
                <forbiddenRepository>AssetRepository</forbiddenRepository>
            </configuration>
        </execution>
    </executions>
</plugin>
```

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
`DomainMethodsMustNotReturnNull`. Unknown evidence, PMD errors and suppressed violations fail the build.

`check-bytecode` inventories every application class, including generated implementations; dependencies
are only resolution inputs. It requires complete imports and resolved direct dependencies, and rejects
empty, corrupt and duplicate inventories. It executes every registered rule, enforces required identities
and runs the construction/producer policy. Reports include exact configured authority identities and
rule descriptions. Resolution cannot be disabled or replaced through ArchUnit configuration, and the
analyzer preserves its caller's context classloader and configuration.

The bytecode policy targets one complete domain/persistence/infra application per module. All three
layers and the types selected by mandatory rules must be present; aggregator POMs and empty selections
are not silently accepted. Each domain needs its `<Domain>Producer` in `infra.domains.producers`.

The reference's six typed source checkers and IOSP remain in the template pending their separate
extraction. They establish additional contracts, including handwritten-vs-generated provenance and
the exact `WorkspaceTransactions.execute` boundary. Bytecode mapper identity alone does not prove
source-generation provenance. Compiler, behavioral, coverage and mutation gates also retain their
own responsibilities; running these two plugin goals is not approval for the entire development standard.

## Publishing

CI publishes snapshots to GitHub Packages after verification. Consumers of this private repository
need a GitHub token with package-read permission, configured in Maven settings under server ID `github`.
Declare `https://maven.pkg.github.com/JF3Env/architecture-maven-plugin` in `pluginRepositories` (and
`repositories` when consuming the rules library). Keep credentials outside the project.
For local development, the sibling checkout's `./mvnw install` is sufficient.
