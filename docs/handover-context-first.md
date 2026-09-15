# Handover — context-first migration

Written 2026-09-14, refreshed 2026-09-15 after the 1.0.0 work. Covers the state of
`architecture-maven-plugin`, `java-ai-template` and `sat-label`, the decisions taken, and what remains.

Read this before touching any of the three repositories.

## 1. TL;DR

`0.4.0` of the plugin is released and tagged: the last *layer-first* version. `1.0.0-SNAPSHOT` is
implemented, verified locally and waiting in a pull request; it enforces the context-first shape and
retires only the rules that genuinely conflict with it. **IOSP is kept**, adapted to the new tree.
The template has adopted context-first against the local snapshot and is also waiting in a pull request.

| Repository | Branch / ref | Version | State |
| --- | --- | --- | --- |
| `architecture-maven-plugin` | `feat/ARCH-5-context-first` @ `a05b648`, PR #10 | `1.0.0-SNAPSHOT` | `clean verify` green: 592 tests, 14 ITs. `main` still at `0.5.0-SNAPSHOT` / `v0.4.0` |
| `java-ai-template` | `feat/ARCH-5-context-first` @ `8d461f3`, PR #5 | `0.1.0-SNAPSHOT` | consumes plugin `1.0.0-SNAPSHOT` (locally installed); `clean verify` green: 87 tests, 16/16 mutations. PR #4 (consume released `0.4.0`) also open |
| `sat-label` | `feat/intent-001-…` | — | does NOT consume the plugin; layer-first; working tree dirty (611 files). Untouched |

## 2. Pending human actions, in order

1. **Merge `java-ai-template` PR #4** (pins the released `0.4.0`). Trivial, verified.
2. **Merge `architecture-maven-plugin` PR #10** (1.0.0 context-first). Then release `1.0.0`: CI deploys on
   push to `main`, so it takes two PRs — `build/ARCH-5-release` with version `1.0.0`, merged and deployed,
   then `build/ARCH-5-next-iteration` with `1.1.0-SNAPSHOT`. Direct pushes to `main` are rejected.
3. **Point `java-ai-template` PR #5 at the released `1.0.0`** (`architecture-plugin.version`), re-run
   `./mvnw clean verify`, merge. Until then it builds only where the snapshot is installed.
4. Drop `stash@{0}` in the plugin (`WIP ARCH-5 context-first (abortado…)`): the reusable parts of its
   design were re-implemented; nothing else in it is needed.

Merging was attempted from this session and refused by the auto-mode policy ("merge without review");
that is why the PRs are open rather than merged.

## 3. What 1.0.0 does

- `check-bytecode`: 34 context-first identities (the Fase 7 suite of the sat-label plan plus the structural
  rules the plan marks MANTER) over `<base>.<context>.{api,domain,application,infrastructure}` and a shared
  `<base>.<platform>`. Contexts are derived from the compiled inventory; only the platform marker types are
  configurable (`aggregateRootAnnotation`, `unitOfWorkType`, `integrationEventType`, defaults below
  `platformPackage`). Construction policy: one constructor and one construction owner per class, records
  and enums exempt. Every identity has a compiled positive control and counterexample.
- `check`: `RequireTypeImports`, `AvoidOptionalGet`, `DomainMethodsMustNotReturnNull`, `SOURCE_INVENTORY`,
  `AggregateInvariantSetter` (aggregates by marker annotation). `persistenceBoundary` removed.
- `check-iosp`: kept; ownership grammar, service detection (`Service`/`Handler` in domain and application)
  and producer location (`infrastructure.wiring`) adapted. Bytecode verification now resolves against the
  consumer compile classpath.
- Deliberate readings of the plan's literal suite are listed in the plugin README (platform outside the
  layer rule, wiring exempt from transaction/JPA ownership, REST may use `jakarta.enterprise..`, api may
  use `org.jspecify..`).

## 4. Findings that change the picture

- **IOSP vs P11.** IOSP's creator-location contract (allocation only in `*Factory`/`*Mapper`/`*Builder` or the
  producer) collides with principle P11 (records constructed where consumed, forwarding factories deleted).
  Both the plugin fixture and the template satisfy both by routing every allocation through a factory or a
  MapStruct mapper. That is the cost the plan's P9 talks about; measure it on sat-label before any further
  IOSP decision. Section 5 of the original handover claimed no overlap; that was too strong.
- **Empty selections fail.** A required identity that selects no class is an analysis error. Every
  consumer must therefore exercise every contract (an integration event, an aggregate with a setter, an
  entity, a DTO, MapStruct mappers, one producer per context…). The template does; sat-label will have to.
- **Corrections found only by adopting the shape in a real Quarkus app:** ArchUnit does not resolve
  catch-handler types from the classpath (now known by name); `package-info` classes tripped the DTO,
  entity and mapper suffix rules (now exempt); IOSP bytecode verification could not resolve a caught
  `QuarkusTransactionException` (now uses the consumer classpath).

## 5. The template after adoption

`platform` (`AggregateRoot`, `IntegrationEvent`, `StorageException`, `UnitOfWork`, `QuarkusUnitOfWork`)
plus one context `workspace`: `api` (`WorkspaceRejection`, `events.WorkspaceIncremented`), `domain`
(`Workspace` with `@AggregateRoot`, `WorkspaceFactory`, `WorkspaceRepository`, three rejections),
`application` (`WorkspaceHandler` owning the unit of work and publishing the event, command and result
records, `WorkspaceNotFoundException`, a MapStruct result mapper), `infrastructure` (`inbound.rest` with one
JAX-RS mapper per failure kind, `outbound.persistence`, `wiring.WorkspaceProducer`). Docs were rewritten
around CTX-01…CTX-08. Lifecycle negatives cover the source rule, the aggregate setter, a second
construction owner via a generated mapper, transaction and JPA ownership, and a context reaching into
another context's domain.

## 6. sat-label

Nothing was done there, deliberately: Fase 0 of the plan requires a clean tree and the tree has 611
modified files from an incomplete `CLEAN-001` migration. Making sat-label a consumer of the plugin is what
turns the P9 cost argument from "delete IOSP" into "depend on IOSP"; do that on a clean tree, after
`1.0.0` is released.

## 7. Execution guidance (unchanged, still true)

- Global git hooks: branches `<type>/<slug>` with a ticket, no dots in slugs, no `BREAKING CHANGE:` footer,
  no AI attribution, and commits must use the JF3Env noreply identity (set `user.name`/`user.email`
  locally; the work identity is rejected).
- Spotless rewrites files during `validate`; re-read before editing.
- CI publishes on push to `main` only; a release is two separate pushes.
- Do not fold IOSP into `check`.
- Verify locally rather than waiting on CI.

## 8. Reference

| Thing | Path |
| --- | --- |
| Context-first plan | `sat-label/docs/plano-context-first.md` (Fase 7 suite: lines 738-801) |
| Plugin contracts and parameters | `architecture-maven-plugin/README.md` |
| Plugin slice history | `architecture-maven-plugin/docs/migration.md` (section "1.0.0 — context-first") |
| Rule catalog | `rules/src/main/java/io/github/jf3env/architecture/bytecode/BytecodeRuleCatalog.java` |
| Shape grammar | `rules/src/main/java/io/github/jf3env/architecture/ContextShape.java` |
| Rule counterexamples | `rules/src/test/java/io/github/jf3env/architecture/bytecode/ContextFirstRulesTest.java` |
| Two-context consumer fixture | `maven-plugin/src/it/bytecode-valid/` |
| Template contracts | `java-ai-template/AGENTS.md`, `docs/architecture.md`, `docs/plugin-migration.md` |
