# Optional event and transaction capabilities

Date: 2026-09-29
Candidate: `1.2.1-SNAPSHOT` (library, plugin and parent together)
Branch: `fix/RHESPOCK-000-optional-bytecode-capabilities`
Base: `8d69485d45ee98e0471073ed203f8d39b231aefc`

## Contract

Removing the final integration event and application transaction consumer from a real consumer
caused plugin 1.2.0 to fail two rules with `failed to check any classes`. Its inventory was
complete and contained 1840 application classes. The failure was in the rule applicability
model, not in class discovery or the consumer's transport adapter.

`INTEGRATION_EVENTS_ARE_PUBLIC_RECORDS` and `TRANSACTIONS_BELONG_TO_APPLICATION` now select
all application classes and check implications. Non-events need not satisfy event constraints;
non-transactional classes need not reside in application. Event record/visibility/location
requirements and the existing platform/wiring transaction exceptions remain enforced.
No empty-selection override, rule exclusion, baseline change or global configuration is added.

## Evidence

All plugin commands ran at this repository's root using JDK 24.0.2. Logs and SHA-256 source
snapshot (`plugin-snapshot.json`, base above plus these edits) are retained under
`/private/var/folders/wv/_tnzl8112n16jg6klvk2ffk00000gn/T/opencode/intent-004-step-002/`.

| Command | Exit | Observed result | Log |
| --- | --- | --- | --- |
| `./mvnw -o compile` | 0 | BUILD SUCCESS; production formatting and compilation passed before test changes | `plugin-compile.log` |
| `./mvnw test` | 0 | 645 tests, zero failures/errors/skips; BUILD SUCCESS | `plugin-test.log` |
| `./mvnw clean verify` | 0 | 645 tests and 17 isolated-repository Maven fixtures passed; BUILD SUCCESS | `plugin-verify.log` |
| `git diff --check` | 0 | No whitespace errors | terminal output |

The added controls accept absence of both capabilities and only exempt transaction consumers,
accept application transaction annotations, and independently reject a non-record, non-public,
or misplaced event. Existing tests still reject transaction consumers in domain and REST.
Existing empty/corrupt/duplicate/unresolved inventory controls remain in the executed suite.
The new `bytecode-no-optional-capabilities` Maven fixture removes the event and transaction
usage from the valid consumer and requires both rule identities plus the construction policy
in the successful report. These checks establish applicability and retained constraints, not
functional correctness of an application using the plugin.

## Packaged consumer check

Direct `./mvnw -o install:install` exited 1: a fresh Maven invocation did not attach the
previously packaged JARs. The parent POM was installed. Both verified JARs were then installed
with `./mvnw -o -N org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file`, passing
`-Dfile=<module>/target/<artifact>-1.2.1-SNAPSHOT.jar` and `-DpomFile=<module>/pom.xml`.
Both commands exited 0 (`plugin-install-rules.log`, `plugin-install-maven-plugin.log`).

The real consumer's previously failing `check-bytecode@architecture-bytecode-contracts` was
repeated with the explicit `1.2.1-SNAPSHOT` goal and property override. It exited 0, executing
all 35 rules and the construction policy on 1840 application classes with zero frozen findings
(`plugin-consumer-bytecode.log`). Java target 24 and the Maven goal/configuration API remain
unchanged; no CLI or programmatic interface was removed.

This candidate is installed locally only. Remote publication and consumer dependency adoption
remain pending; the override is candidate validation, not evidence for a consumer still pinned
to 1.2.0.
