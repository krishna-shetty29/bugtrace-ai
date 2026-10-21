# BugTrace AI IntelliJ plugin

Local Kotlin client for the existing BugTrace authenticated backend. Target: IntelliJ IDEA 2026.2.3 (262.10968.63), Kotlin 2.4.0 and its Java 25 IDE runtime.

## Local build

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Build-Local.ps1
```

Uses the installed IDE SDK/compiler, runs the executable core checks, then prints a fresh installable ZIP and build-record path. The backend's Java 21 configuration is unchanged. Install the resulting ZIP through Settings → Plugins → Install Plugin from Disk. Do not install the source-package ZIP.

An optional Gradle development build is also provided. It requires Gradle 9+ and Java 25. No Gradle wrapper is bundled. Relevant tasks: `coreChecks`, `buildPlugin`, `verifyPluginStructure`, and `verifyPlugin` after resolving its verifier dependencies. The local PowerShell build is the validated path for this delivery.

## Configure and submit

Settings → Tools → BugTrace AI:

- Backend: `http://127.0.0.1:8080`
- Dashboard: `http://127.0.0.1:5173`
- Project UUID: the project bound to your personal key
- Module: for example `fixtures/checkout-timeout`
- Backend repository path: blank for the same local Git root
- Key: personal Owner/Investigator key, stored through IDE Password Safe

Open a local source file inside the configured module. Use Tools → Submit to BugTrace AI or the editor context menu. Inspect the captured SHA, file and optional committed selection, fill the bug form and explicitly submit. The backend allowlist and project roles remain authoritative. Snapshot import errors preserve the saved investigation ID; do not blindly resubmit after partial/uncertain completion.

Only committed HEAD contents are imported. Staged, unstaged, untracked and unsaved changes are excluded. Optional selected code is off by default and can be included only when the editor document matches its committed file after CRLF normalization. Its hash describes the included UTF-8 text. The plugin creates no Git commits, pushes, model calls, worker jobs or patch approvals.

View → Tool Windows → BugTrace AI provides Refresh, Verify snapshot and Open in dashboard. Browser authentication is separate. Runtime metadata is stored in local workspace settings; personal keys are not serialized there. Password Safe persistence follows the user's IDE storage policy.

## Backend contracts

- `GET /api/projects`: verify personal credential/project match
- `POST /api/investigations`: save report with labeled IDE context
- `POST /api/investigations/{id}/snapshot/import`: import captured SHA/module
- `GET /api/investigations/{id}/snapshot/verify`: verify snapshot
- `GET /api/investigations`: current project status list

Requests use explicit Bearer authentication and JSON for POST. No redirects, automatic POST retries, arbitrary model-chosen commands or credentials in dashboard URLs. A snapshot import is optional and does not execute repository code through this plugin.

## Validation boundaries

Core checks cover temporary Git repositories, selection exclusions, project scope, API request shapes, denied responses and payload limits. IDE extension loading, dialog behavior, actual Password Safe operations and live backend integration require manual acceptance after installation. Compilation alone does not establish those outcomes.

SDK references: [Settings](https://plugins.jetbrains.com/docs/intellij/settings-guide.html), [Actions](https://plugins.jetbrains.com/docs/intellij/action-system.html), [Tool windows](https://plugins.jetbrains.com/docs/intellij/tool-windows.html), [Password Safe](https://plugins.jetbrains.com/docs/intellij/persisting-sensitive-data.html).
