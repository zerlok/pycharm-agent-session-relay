<!-- Tasks 2.x are GitHub-settings steps done outside the repo; they stay unchecked here. -->

## 1. Workflow

- [x] 1.1 Add `environment: marketplace` to the `publish` job in `.github/workflows/release.yml`

## 2. GitHub environment setup (outside the repo)

- [ ] 2.1 Create the `marketplace` deployment environment
- [ ] 2.2 Move `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` into it as
      environment secrets and remove the repository-level copies
- [ ] 2.3 Add a required reviewer to the environment
- [ ] 2.4 Limit the environment's deployment branches/tags to the `v*` tag pattern
