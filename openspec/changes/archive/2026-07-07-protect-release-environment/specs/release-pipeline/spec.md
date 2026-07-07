## ADDED Requirements

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
