## Context

The plugin builds with the IntelliJ Platform Gradle Plugin (2.1.0), which already supplies the
`verifyPlugin`, `signPlugin`, and `publishPlugin` tasks and pulls in `zipSigner()` and
`pluginVerifier()` (see `build.gradle.kts`). The build reads `version` from
`providers.gradleProperty("pluginVersion")`, so the version is already a single Gradle property that
a CI job can override on the command line. What is missing is the orchestration: when to validate,
when to publish, where the version comes from, and how the artifact gets signed without a human at a
keyboard.

Two forces were in tension in the request: "the version is set on GitHub" (tag drives it) and "keep
project and GitHub versions aligned" (two numbers kept equal). This design resolves them by
collapsing to **one** number.

## Goals / Non-Goals

**Goals:**
- Validate every pull request and every `main` push (compile, tests, plugin verifier).
- One-action, deterministic release that publishes a **signed** plugin to the Marketplace.
- Make version misalignment structurally impossible.
- Sign in CI, headless, from secrets.

**Non-Goals:**
- Generic Kotlin style linting (ktlint/detekt) — deferred.
- Release channels (EAP vs stable from a tag suffix) — deferred.
- A `CHANGELOG.md` / `gradle-changelog-plugin` pipeline — explicitly avoided.
- Generating or storing the signing certificate in CI — the cert is created once, manually, and only
  its material is stored as secrets.

## Decisions

- **The git tag of a GitHub Release is the single source of truth for the version.** `gradle.properties`
  holds `pluginVersion = 0.0.0-SNAPSHOT` as a dev/local placeholder; the release job computes
  `ver = ${tag#v}` and passes `-PpluginVersion=$ver`. Because only one real number exists per
  release (on the tag), the repo file and the published version can never disagree.
  - *Trade-off:* `gradle.properties` no longer shows "the current version"; the git tags become that
    record. Accepted deliberately in favor of zero-drift.
  - *Alternative rejected:* keep the version in `gradle.properties` and have CI assert the tag equals
    it. Reviewable in-repo, but keeps two numbers and a whole class of "tag ≠ property" failures.

- **Publish only on a published GitHub Release — never on a bare tag or branch push.** A single
  trigger (`on: release: published`) eliminates the double-fire hazard that arises when a workflow
  listens to both `push: tags` and `release: published` (creating a Release also pushes its tag, so a
  dual-trigger workflow would publish twice). One trigger, one publish, no dedup logic needed.
  - *Consequence:* the release ritual is: draft a GitHub Release, set tag `vX.Y.Z`, write notes,
    publish. There is no supported "bare `git push --tags`" publish path.

- **The GitHub Release body is the Marketplace change-notes.** `build.gradle.kts` wires
  `pluginConfiguration { changeNotes = ... }` from the release body (passed into the build by the
  release job, e.g. via a `-P` property or env var). Because publish only happens via a Release,
  there is always a body to use, so no `CHANGELOG.md` is needed and no "empty notes" case exists.

- **The publish is gated on a green build.** The release job runs `check` + `verifyPlugin` before
  `signPlugin`/`publishPlugin` in the same job, so a failing test or verifier failure aborts the
  release before anything reaches the Marketplace.

- **Signing runs headless from four secrets.** `signPlugin` reads the certificate chain, private key,
  and key password from Gradle properties fed by GitHub secrets `CERTIFICATE_CHAIN`, `PRIVATE_KEY`,
  `PRIVATE_KEY_PASSWORD`; `publishPlugin` reads `PUBLISH_TOKEN`. The certificate itself is generated
  once by a human (per JetBrains' signing guide) and only its material is stored as secrets — CI
  never mints keys.

- **CI runs `check` + `verifyPlugin`; no style linters.** `verifyPlugin` is the high-signal,
  plugin-specific check (API compatibility against the target IDE, deprecated-API usage,
  since/until-build correctness) and is already configured. `check` covers compile + the existing
  unit tests. ktlint/detekt add style noise with low payoff for a solo MVP and are left for a later
  change.

## Risks / Trade-offs

- **Tests need network.** Per the build/test notes, unit tests fetch JUnit4 + opentest4j from Maven
  Central; the fully-offline path was removed for portability. GitHub-hosted runners have network, so
  CI runs tests normally (no `--offline` on the test job). → No mitigation needed beyond *not* forcing
  offline mode in CI.
- **`gradle.properties` no longer states the shipped version.** → Documented; the git tag / Release
  is the version of record. Local `runIde`/`buildPlugin` show `0.0.0-SNAPSHOT`, which is correct for
  a non-release build.
- **Secrets and certificate are one-time manual setup.** → Captured as explicit tasks; the workflow
  is inert until the four secrets exist. A missing/expired certificate fails `signPlugin` loudly
  before publish, not after.
- **Marketplace rejects a re-used or non-increasing version.** → The tag is the version; publishing a
  new version means drafting a Release with a new tag. Human error here fails at `publishPlugin`,
  after the gate, without corrupting an existing listing.
