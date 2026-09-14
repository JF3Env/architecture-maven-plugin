# Development contracts

- Keep the Maven adapter separate from the analysis library. Analysis must not require JUnit or Maven to run.
- This repository implements analysis tooling. The domain/persistence/infra policy describes the consumer application;
  it is not a claim that PMD rules or Maven Mojos are domain application objects.
- Preserve every extracted rule's identity, severity, scope, positive controls and intended negative diagnostics.
- Never approve empty, corrupt, incomplete or unprocessed analysis evidence. Suppression is not approval.
- Keep consumer configuration instance-local. Never configure another module through global system properties.
- Read class resources without initializing consumer classes. Dependencies for resolution are not application inputs.
- Migrate one executable slice at a time. Prove the packaged artifact in a real Maven consumer before removing its
  predecessor from the reference project. Preserve the reference project's existing quality thresholds and tests.
- Run `./mvnw test` and `./mvnw clean verify`; integration tests use an isolated Maven repository.
- Use Spotless for formatting and review its changes. Keep documentation in English.
- Version the rule library and Maven plugin together. Document exactly which contracts each version executes.
