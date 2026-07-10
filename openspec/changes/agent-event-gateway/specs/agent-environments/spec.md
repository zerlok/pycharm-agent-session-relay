# agent-environments — delta spec

## ADDED Requirements

### Requirement: Named start-script configs
The plugin SHALL let the user define, in a Relay Settings page, named **start-script
configs**: `{ name, command, environment: local | docker | ssh | custom, capabilities? }`,
where `command` is a literal shell command that MAY reference the placeholders
`${AGENT_SESSION_RELAY_URL}`, `${AGENT_SESSION_RELAY_ID}`, `${AGENT_SESSION_RELAY_PORT}`,
and `${AGENT_SESSION_RELAY_PROJECT_DIR}`. Configs SHALL persist across restarts. A config is
the only source of anything the plugin executes; a config MUST NOT be derivable from, or
mutated by, a registration or a received event.

#### Scenario: Config persists and is editable
- **WHEN** the user adds a start-script config in Settings and reopens the IDE
- **THEN** the config is present and editable, unchanged

#### Scenario: Docker config references the project dir
- **WHEN** a config's command is `docker run -it --rm -v ${AGENT_SESSION_RELAY_PROJECT_DIR}:/project img`
- **THEN** launching it substitutes the open project's base path for the placeholder before
  running the command

### Requirement: Launch a session into a terminal
When the user launches a start-script config for the open project, the plugin SHALL
register the session in-process, open a terminal in the Agent Sessions tool window, export
the environment contract (`AGENT_SESSION_RELAY_URL`, `AGENT_SESSION_RELAY_ID`,
`AGENT_SESSION_RELAY_PORT`, `AGENT_SESSION_RELAY_PROJECT_DIR`) into that terminal, and run
the (placeholder-substituted) command there. The plugin SHALL NOT ship any script, template,
or binary into the agent's environment beyond those exported variables, and SHALL NOT
establish reachability itself (tunnels, host-gateway wiring) — that is the command's / the
user's connection tooling's responsibility.

#### Scenario: Launch runs the command in a session terminal
- **WHEN** the user launches a `local` config `claude`
- **THEN** a terminal opens in the Agent Sessions tool window running `claude` with the four
  `AGENT_SESSION_RELAY_*` variables present in its environment

#### Scenario: Only the env contract crosses to the agent
- **WHEN** the user launches any config
- **THEN** the only Relay-originated values the launched command receives are the four
  `AGENT_SESSION_RELAY_*` variables — no other local path, identifier, script, or file

### Requirement: The plugin never modifies user agent settings
Relay SHALL NOT write to, patch, or delete any user, global, or project agent configuration
file (e.g. `~/.claude/settings.json`, `~/.codex/config.toml`, `.claude/settings.json`). Hook
wiring is performed entirely by the user's start script per-invocation; the plugin performs
no automatic hook injection.

#### Scenario: No config mutation on launch
- **WHEN** the plugin launches a start-script config
- **THEN** no user, global, or project agent configuration file is created or modified
