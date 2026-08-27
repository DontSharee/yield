# Plugin versioning

Every plugin has its own independent version (`X.Y.Z`, starting at `0.0.1`),
set as `version = "..."` in that plugin's own `plugins/<name>/build.gradle.kts`
(overriding the `0.0.1` default in the root `build.gradle.kts`'s
`subprojects {}` block). It flows into `plugin.yml` via `${version}`.

Whenever a change is made to a plugin, bump *that plugin's* version before
deploying:
- Minor change: bump `Z` by `0.0.1` (e.g. `0.1.2` -> `0.1.3`).
- Major change: bump `Y` by `0.1` and reset `Z` to `0` (e.g. `0.1.3` -> `0.2.0`).

Only the plugin(s) actually touched get a version bump - unrelated plugins
keep their current version.
