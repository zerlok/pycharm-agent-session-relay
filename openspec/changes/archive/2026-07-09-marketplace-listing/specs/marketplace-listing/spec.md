## ADDED Requirements

### Requirement: Listing describes only shipped, user-reachable behavior

The `plugin.xml` `<description>` SHALL describe only behavior that is implemented and reachable by an
end user of the current release. It SHALL NOT present a modeled-but-unauthored scope or a
deferred/planned feature as if it were available, and SHALL NOT claim an action the plugin does not
perform.

#### Scenario: Unauthored scope is not advertised

- **WHEN** a comment subject scope is modeled but has no authoring entry point (per `review-batch`,
  e.g. whole-file or project-level comments)
- **THEN** the listing does not describe the user leaving comments at that scope

#### Scenario: Deferred capability is not stated as present

- **WHEN** delivery only writes `REVIEW.md` and notifies the user, without messaging the agent (per
  `review-delivery`)
- **THEN** the listing describes the user handing `REVIEW.md` to the agent, not the plugin telling
  or typing to the agent

#### Scenario: Removed behavior is not still advertised

- **WHEN** a behavior spec removes a user-visible element (e.g. `review-annotation` removing the
  stored-comment gutter icon in favor of the inline card)
- **THEN** the listing no longer describes that element

### Requirement: Listing is written at end-user altitude

The `<description>` SHALL be written for users deciding whether to use the plugin — what it does and
how to use it — and SHALL NOT carry contributor-facing content (build steps, the spec-driven
workflow, project organization); that content belongs in `README.md`.

#### Scenario: Usage over internals

- **WHEN** the listing is authored
- **THEN** it covers user-facing usage and omits build and development-workflow detail, which the
  README carries instead

### Requirement: Behavior claims stay consistent with the behavior specs

Behavior described in the listing SHALL be consistent with the behavior capability specs
(`review-annotation`, `review-batch`, `review-export`, `review-delivery`) as the single source of
truth, and SHALL reuse them rather than restating requirement-level detail. A change that alters a
behavior spec in a way that affects a user-visible claim SHALL update the listing within the same
change so the claim matches.

#### Scenario: A behavior change updates the listing in the same change

- **WHEN** a change modifies a behavior spec such that a user-visible claim in the listing no longer
  holds
- **THEN** the same change updates the listing so the claim matches the revised behavior

#### Scenario: A contradiction with the specs is a defect

- **WHEN** the listing states a user-visible behavior that contradicts the current behavior specs
- **THEN** the listing is out of compliance and is corrected to match the specs

### Requirement: Required listing metadata is present

The listing SHALL include vendor identification, a link to the GitHub source repository, a link to
the issue tracker, and a screenshot set for the Marketplace gallery.

#### Scenario: Source and issue links are reachable

- **WHEN** a user views the listing
- **THEN** it exposes a GitHub source-repository link and an issue-tracker link the user can follow

#### Scenario: A screenshot set is provided

- **WHEN** the listing is published to the Marketplace
- **THEN** a screenshot set is supplied for the gallery, sourced from the in-repo images
