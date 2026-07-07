# release-pipeline Specification

## Purpose
TBD - created by archiving change add-ci-release-pipeline. Update Purpose after archive.
## Requirements
### Requirement: CI validates every pull request and every main push

Continuous integration SHALL run on each pull request and on each push to the `main` branch, and
SHALL compile the sources, run the unit tests, and run the IntelliJ Plugin Verifier. A failure in any
of these SHALL be reported as a failing check.

#### Scenario: Pull request is validated

- **WHEN** a pull request is opened or updated
- **THEN** CI compiles the sources, runs the unit tests, and runs the plugin verifier, and reports
  the combined result on the pull request

#### Scenario: Main is validated

- **WHEN** a commit is pushed to `main`
- **THEN** CI compiles the sources, runs the unit tests, and runs the plugin verifier

#### Scenario: Style linting is not required

- **WHEN** CI runs
- **THEN** it is not required to run generic Kotlin style linters (e.g. ktlint or detekt); the plugin
  verifier is the plugin-specific validation of record

### Requirement: The release tag is the single source of truth for the published version

The published plugin version SHALL be derived from the git tag of the GitHub Release, with the
leading `v` stripped. The in-repo `gradle.properties` `pluginVersion` SHALL be a non-release
placeholder and SHALL NOT determine the published version.

#### Scenario: Version comes from the tag

- **WHEN** a GitHub Release with tag `vX.Y.Z` is published
- **THEN** the plugin is built and published as version `X.Y.Z`, regardless of the placeholder value
  in `gradle.properties`

#### Scenario: The repository file cannot disagree with the release

- **WHEN** the release is built
- **THEN** the version is taken from the tag alone, so there is no second version value that could
  contradict it

### Requirement: A Marketplace publish happens only on a published GitHub Release

Publishing a new version to the JetBrains Marketplace SHALL occur only in response to a published
GitHub Release. No other event — including a bare tag push or a branch push — SHALL publish to the
Marketplace.

#### Scenario: Release triggers a publish

- **WHEN** a GitHub Release is published
- **THEN** the pipeline builds, signs, and publishes that version to the Marketplace

#### Scenario: A non-release push does not publish

- **WHEN** a commit or a tag is pushed without a corresponding published GitHub Release
- **THEN** no Marketplace publish occurs

### Requirement: The published plugin is signed

The plugin artifact published to the Marketplace SHALL be signed, and the signing SHALL be performed
in CI from stored signing material without a manual signing step.

#### Scenario: Signing runs before publish

- **WHEN** the release pipeline runs
- **THEN** it signs the plugin using the configured certificate chain, private key, and key password
  before it publishes, and publishes the signed artifact

#### Scenario: Missing signing material fails the release

- **WHEN** the signing material is absent or invalid
- **THEN** the release fails at the signing step and nothing is published

### Requirement: A publish is gated on a passing build

The release pipeline SHALL run the compile, unit tests, and plugin verifier before signing and
publishing, and SHALL NOT publish if any of them fail.

#### Scenario: A failing gate blocks the publish

- **WHEN** the compile, the unit tests, or the plugin verifier fails during a release
- **THEN** the pipeline stops and does not sign or publish

### Requirement: Marketplace change-notes come from the GitHub Release body

The change-notes shown on the Marketplace listing for a release SHALL be the body of the GitHub
Release that triggered it. No separate changelog file SHALL be required.

#### Scenario: Release notes become change-notes

- **WHEN** a GitHub Release with a written body is published
- **THEN** that body is used as the Marketplace change-notes for the published version

### Requirement: Publishing runs in a protected, manually-approved environment

The Marketplace publish job SHALL execute within a protected deployment environment whose secrets —
the Marketplace token and the signing material — are scoped to that environment and are NOT available
to continuous-integration or pull-request runs. The environment SHALL require approval by a
designated reviewer before the job signs or publishes, and SHALL be usable only from release version
tags.

#### Scenario: Publish waits for a reviewer

- **WHEN** a GitHub Release is published
- **THEN** the publish job does not sign or publish until a designated reviewer approves the
  deployment

#### Scenario: Publish secrets are not exposed to CI or pull requests

- **WHEN** CI runs on a pull request or on a push to `main`
- **THEN** the Marketplace token and signing material are unavailable to it, because they are scoped
  to the publish environment

#### Scenario: Environment is restricted to release tags

- **WHEN** the publish environment's deployment targets are configured
- **THEN** it is usable only from version tags (`v*`) and not from arbitrary branches

