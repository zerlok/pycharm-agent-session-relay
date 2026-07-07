## Why

The plugin has no CI. Nothing compiles the sources, runs the tests, or runs the IntelliJ Plugin
Verifier on a pull request or on `main`, so regressions and IDE-compatibility breaks surface only by
hand. There is also no release path: publishing to the JetBrains Marketplace would be a manual,
unsigned, easy-to-mis-version `./gradlew publishPlugin` from a laptop. And the version lives in two
places waiting to disagree — `gradle.properties` (`pluginVersion`) and whatever tag a human types —
with nothing keeping them aligned.

## What Changes

- Introduce a `release-pipeline` capability that governs how the plugin is validated and published:
  what CI runs and when, where the version comes from, when a Marketplace publish happens, and that
  the published artifact is signed.
- Add a **CI workflow** (`.github/workflows/ci.yml`) triggered on `pull_request` and on push to
  `main`, running `./gradlew check` (compile + unit tests) and `./gradlew verifyPlugin` (the IntelliJ
  Plugin Verifier — the plugin-domain "linter"). Generic Kotlin style linters (ktlint/detekt) are
  **out of scope**.
- Add a **release workflow** (`.github/workflows/release.yml`) triggered **only** on a published
  GitHub Release. It derives the version from the release tag, re-runs `check` + `verifyPlugin` as a
  gate, **signs** the plugin, and publishes it to the Marketplace — so "publish" means a new signed
  version appears on the Marketplace.
- Make the **git tag the single source of truth for the published version**. `gradle.properties`
  keeps a non-release placeholder (`0.0.0-SNAPSHOT`); the build overrides it at release time via
  `-PpluginVersion=<tag without leading v>`. The two can no longer disagree because only one number
  exists per release.
- Use the **GitHub Release body as the Marketplace change-notes**; no `CHANGELOG.md` file is
  introduced.
- Publish a **signed** artifact using signing material supplied from GitHub secrets, so signing runs
  headless in CI.

## Capabilities

### New Capabilities
- `release-pipeline`: the rules for validating the plugin in CI and for publishing a signed release
  to the Marketplace — CI triggers and gates, the tag-as-version-source rule, the
  publish-only-on-GitHub-Release rule, signing, and change-notes provenance.

### Modified Capabilities
<!-- none: no end-user behavior spec (review-annotation/-batch/-export/-delivery) changes. -->

## Impact

- `.github/workflows/ci.yml` — new: PR + `main` validation (`check`, `verifyPlugin`).
- `.github/workflows/release.yml` — new: publish-on-Release (`check`, `verifyPlugin`, `signPlugin`,
  `publishPlugin`).
- `gradle.properties` — `pluginVersion` becomes the `0.0.0-SNAPSHOT` placeholder.
- `build.gradle.kts` — wire `signing` (certificate chain / private key / password) and
  `publishPlugin` (token), and `changeNotes` sourced from the release body; accept the
  `-PpluginVersion` override already implied by `providers.gradleProperty("pluginVersion")`.
- Repository settings — four GitHub Actions secrets: `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`,
  `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`. A signing certificate must be generated once (manual,
  outside CI).
- No runtime code, domain, or end-user behavior-spec changes.
