## Why

The `release-pipeline` capability publishes a signed plugin using a Marketplace token and signing
material. As first shipped, those four secrets are repository-wide, and the publish job runs
unattended the moment a Release is published. That is more standing authority than the release path
needs: any workflow could reference the secrets, and anyone able to create a `v*` tag could trigger a
publish with no human in the loop. Because GitHub runs the workflow file *from the released tag*, a
tampered `release.yml` on a malicious tag would run with those secrets.

## What Changes

- Bind the publish job to a protected GitHub **deployment environment** (`marketplace`) so the
  Marketplace token and signing material are scoped to that environment only — not exposed to CI or
  pull-request runs.
- Require **manual approval** by a designated reviewer before the publish job signs or publishes.
  GitHub enforces this gate before the job starts, independent of the workflow file's contents.
- Restrict the environment to version tags (`v*`), so it is not usable from arbitrary branches.

## Capabilities

### Modified Capabilities
- `release-pipeline`: adds the requirement that publishing runs in a protected, manually-approved
  environment with release-scoped secrets. The existing triggers, tag-as-version-source, signing, and
  gating requirements are unchanged.

## Impact

- `.github/workflows/release.yml` — the `publish` job gains `environment: marketplace`.
- Repository settings (outside the repo) — create the `marketplace` environment, move the four
  secrets into it, add a required reviewer, and limit its deployment targets to `v*` tags.
- No runtime code or end-user behavior changes.
