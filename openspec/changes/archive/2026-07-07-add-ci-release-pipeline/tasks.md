<!-- Tasks 5.x and 6.1-6.3 are done outside the repo (certificate generation, secret setup, and
     GitHub/Marketplace runtime verification during the first real release); they stay unchecked. -->

## 1. Version source (tag as single source of truth)

- [x] 1.1 Set `pluginVersion = 0.0.0-SNAPSHOT` in `gradle.properties` as the dev/local placeholder
- [x] 1.2 Confirm `build.gradle.kts` takes `version` from `providers.gradleProperty("pluginVersion")`
      so a `-PpluginVersion=<ver>` override wins at release time (no code change expected)

## 2. CI workflow (`.github/workflows/ci.yml`)

- [x] 2.1 Trigger on `pull_request` and on `push` to `main`
- [x] 2.2 Set up JDK 21 and the Gradle build cache
- [x] 2.3 Run `./gradlew check` (compile + unit tests; do NOT pass `--offline` — tests need network)
- [x] 2.4 Run `./gradlew verifyPlugin` (the IntelliJ Plugin Verifier)

## 3. Release workflow (`.github/workflows/release.yml`)

- [x] 3.1 Trigger **only** on `release: { types: [published] }`
- [x] 3.2 Derive the version from the release tag: `ver = ${tag#v}`
- [x] 3.3 Gate: run `./gradlew check verifyPlugin -PpluginVersion=$ver` before publishing
- [x] 3.4 Run `signPlugin` with signing secrets in the environment (via `publishPlugin`, which
      depends on it)
- [x] 3.5 Run `./gradlew publishPlugin -PpluginVersion=$ver` with `PUBLISH_TOKEN` in the environment
- [x] 3.6 Pass the GitHub Release body into the build as the change-notes source (`CHANGE_NOTES`)

## 4. Build wiring (`build.gradle.kts`)

- [x] 4.1 Wire `signing { certificateChain / privateKey / password }` from Gradle properties backed by
      env vars `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`
- [x] 4.2 Wire `publishing { token }` from an env var backed by `PUBLISH_TOKEN`
- [x] 4.3 Wire `pluginConfiguration { changeNotes }` from the release-body value passed by the release
      job (empty/unset for local builds is acceptable)

## 5. One-time setup (manual, outside CI)

- [ ] 5.1 Generate a signing certificate chain + private key per JetBrains' plugin-signing guide
- [ ] 5.2 Obtain a JetBrains Marketplace permanent token
- [ ] 5.3 Add repository secrets: `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY`,
      `PRIVATE_KEY_PASSWORD`

## 6. Verification

- [x] 6.0 Offline compile gate (`./gradlew compileKotlin --offline`) — build config evaluates and the
      signing/publishing/changeNotes DSL is accepted
- [ ] 6.1 Open a throwaway PR and confirm `ci.yml` runs `check` + `verifyPlugin` and reports status
- [ ] 6.2 Confirm a branch/tag push that is NOT a published Release does not trigger a publish
- [ ] 6.3 Draft a pre-release GitHub Release and confirm the release job derives the version from the
      tag, signs, and publishes; verify the signed version and the release notes appear on the
      Marketplace listing
- [x] 6.4 Document the release ritual (draft Release → tag `vX.Y.Z` → notes → publish) in `README.md`
