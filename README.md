# Architecture Maven plugin

Reusable validation extracted incrementally from [java-ai-template](https://github.com/JF3Env/java-ai-template).
The first slice packages the ARCH-09 source rules and executes them independently of Surefire selection.
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
    <version>0.1.0-SNAPSHOT</version>
    <configuration>
        <basePackage>com.company.product</basePackage>
    </configuration>
    <executions>
        <execution>
            <id>architecture-source-contracts</id>
            <phase>process-test-classes</phase>
            <goals><goal>check</goal></goals>
        </execution>
    </executions>
</plugin>
```

Run the lifecycle (`./mvnw test` or `./mvnw verify`) to compile the evidence before analysis.
Direct invocation of `check` requires already compiled production classes.
The goal does not inherit `skipTests` or `-Dtest` and does not initialize application classes.
Its report is written to `target/architecture/source-report.txt` on both acceptance and rejection.

All handwritten main Java source roots are examined. Generated roots must be declared explicitly;
the default is `target/generated-sources/annotations`. A generated marker on handwritten code does
not exclude it. Every handwritten unit must belong to the configured `<basePackage>.<layer>.<domain>`
grammar. Test sources and dependency JARs provide no additional application inputs.

The goal executes `NoStaticMethods`, `RequireTypeImports`, `AvoidOptionalGet` and
`DomainMethodsMustNotReturnNull`. Existing architectural, compiler and behavioral contracts remain
necessary for full approval. Unknown evidence, PMD errors and suppressed violations fail the build.

## Publishing

CI publishes snapshots to GitHub Packages after verification. Consumers of this private repository
need a GitHub token with package-read permission, configured in Maven settings under server ID `github`.
Declare `https://maven.pkg.github.com/JF3Env/architecture-maven-plugin` in `pluginRepositories` (and
`repositories` when consuming the rules library). Keep credentials outside the project.
For local development, the sibling checkout's `./mvnw install` is sufficient.
