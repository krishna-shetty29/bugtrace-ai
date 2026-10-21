# Step 46 — BugTrace local startup

## Architecture and scope

Use Docker Compose for PostgreSQL and, optionally, Ollama. Run the backend, authenticated worker and frontend directly on Windows in separate terminals. This preserves the existing Windows repository/snapshot paths and the registered worker's Docker execution policy. It does not introduce Docker-in-Docker or mount the Docker socket into an application container.

| Component | Startup | Address / persistence |
|---|---|---|
| PostgreSQL 17 + pgvector | Existing Compose service `postgres` | `127.0.0.1:5432`; existing `bugtrace-ai` named volume |
| Backend, JDK 21 | Maven wrapper / Start-Local | `127.0.0.1:8080`; Flyway migrations, existing workspace |
| Worker, JDK 21 | Registered startup script through Start-Local | `127.0.0.1:8083`; snapshot-bound credential and approved image |
| Frontend | Vite development server | `127.0.0.1:5173`; proxies `/api` to backend |
| Optional Ollama | Existing Compose service `ollama` | `127.0.0.1:11434`; existing model volume |

This is a local development package, not a production deployment. The frontend remains the development server. Existing direct development commands are retained.

## 1. Required tools

- Docker Desktop with Linux containers and Docker Compose supporting `up --wait`.
- JDK 21 for backend and worker. Your known installation is `C:\Program Files\Java\jdk-21.0.12.1`.
- Node.js compatible with the installed Vite: `^20.19.0 || >=22.12.0`. Prefer a supported LTS release. npm is required.
- Git for committed snapshot operations.
- Windows PowerShell 5.1 or later.
- The existing complete `C:\Users\ADMIN\bugtrace-ai` checkout, including Maven wrappers and Step 43 authentication/startup scripts.
- Network access for first-time Maven/npm dependency downloads and container image pulls. Runtime benchmark experiments still use their approved network restrictions.

IntelliJ's own Java runtime is separate. Do not change the IDE runtime just to start the Java 21 backend.

## 2. Install the startup package

Run these from the delivered package directory:

```powershell
cd 'C:\Users\ADMIN\Documents\Codex\2026-10-13\step-1-install-the-initial-tools\outputs\step46-local-startup'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Install-Startup.ps1 -CheckOnly
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Install-Startup.ps1
```

The installer validates every package hash before copying. Existing matching files are retained; differing existing files are rejected. It adds the new launcher scripts, example configuration and this guide. The included Compose file matches your current file; it does not change the Compose project name, images, ports or volume names. No real `.env`, database, credential or saved investigation is replaced. Re-running the same installer is safe when installed files are unchanged.

## 3. Configuration

### Your existing installation

Keep your working root `.env` unchanged. **Do not copy the example over it and do not invent a new database password.** Changing POSTGRES_PASSWORD in a file does not change the role password in an existing PostgreSQL volume.

The launcher validates these required entries without printing their values:

```text
POSTGRES_DB
POSTGRES_USER
POSTGRES_PASSWORD
DB_URL
```

DB_URL must use `localhost` or `127.0.0.1`, port `5432`, and the same database name as POSTGRES_DB. Use one `NAME=value` per line, no duplicate keys or inline comments on required values. A literal dollar sign must be inside single quotes so Compose and direct startup agree. Keep configuration in the root `.env`, which is already ignored by Git.

### A genuinely new installation only

Only if `.env` does not exist:

```powershell
cd "$env:USERPROFILE\bugtrace-ai"
if (Test-Path -LiteralPath .\.env) { throw 'Existing .env found; preserve it.' }
Copy-Item -LiteralPath .\infra\local-startup.env.example -Destination .\.env
notepad.exe .\.env
```

Replace the placeholder password privately and save. Use the same POSTGRES_DB in DB_URL. Do not share the file, copy it into reports, or commit it. Initial PostgreSQL credentials are established only when the data volume is first initialized.

The example uses `mock / mock-v1`. That checks plumbing; it is not a live-model performance result. Your existing provider settings remain in your real `.env`. Installing an embedding model does not select a generation model.

### Java configuration in backend and worker terminals

Set these in each dedicated terminal:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.12.1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& "$env:JAVA_HOME\bin\java.exe" --version
```

Adjust the directory only if your JDK 21 is installed elsewhere. Setting JAVA_HOME avoids the Windows Java launcher shortcut selecting a different runtime. These assignments apply to that terminal and its children.

## 4. Start PostgreSQL with Compose

1. Open Docker Desktop and wait for the engine.
2. Open PowerShell:

```powershell
cd "$env:USERPROFILE\bugtrace-ai"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Local.ps1 -Component Infrastructure -CheckOnly
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Local.ps1 -Component Infrastructure
```

CheckOnly uses `docker compose config --quiet`; it does not start containers or print expanded configuration. Startup explicitly selects only PostgreSQL and waits for health. Existing volume data is reused under the `bugtrace-ai` project name. Do not use a different project name to work around an error; it can select a different empty volume.

Optional Ollama startup:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Local.ps1 -Component Infrastructure -WithOllama
```

Do not download a generation model merely to start the app. Choose it separately based on your available RAM and comparison protocol.

## 5. Start the backend and initialize the schema

In a second terminal, set JAVA_HOME as above, then:

```powershell
cd "$env:USERPROFILE\bugtrace-ai"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Local.ps1 -Component Backend
```

Keep this terminal open. Wait for Spring Boot to start successfully on 8080. On first startup Flyway applies the application's migrations; on later startups it validates and applies only pending migrations. Hibernate validates the resulting schema. The pgvector image supplies the extension needed by migration V7. No separate SQL import is required for this application schema.

Do not manually edit migration history or rerun old migrations. A migration error is a startup failure; inspect the backend terminal and preserve data.

Health check in another terminal:

```powershell
curl.exe --fail --silent --show-error http://127.0.0.1:8080/actuator/health
```

Expected: status UP. This is infrastructure readiness, not permission or model validation.

### Initial sign-in setup on a new database only

Existing users sign in with their saved personal key. Do not rerun bootstrap or recover owners for routine startup.

On a genuinely uninitialized database, stop the normal backend and use the existing `Start-AccessBackend.ps1` instead. It places a temporary setup secret on the clipboard. After starting the frontend, paste it only in **Initialize project access**, save every returned owner key privately, then restart normally with Start-Local Backend. Initialization is one-time and database-controlled. If no project exists, the bootstrap creates My project; a fresh installation will not have your historical UUIDs.

## 6. Start the frontend

In a third terminal:

```powershell
cd "$env:USERPROFILE\bugtrace-ai\frontend"
```

For first setup or after package-lock changes, install exact locked dependencies:

```powershell
npm.cmd ci
```

For normal daily startup:

```powershell
cd "$env:USERPROFILE\bugtrace-ai"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Local.ps1 -Component Frontend
```

Keep the terminal open. Open `http://127.0.0.1:5173` and sign in with your saved personal project key. Both ports are fixed; the launcher rejects an occupied port instead of silently selecting a different one.

## 7. Start an authenticated worker when needed

Worker startup is required for actual queued experiments. Browsing, IDE submissions, snapshot import and saved review navigation do not require it.

### Benchmark worker

Before startup:

1. Sign in as Owner of the intended project.
2. Open the intended benchmark investigation, import and verify it.
3. Download its case import JSON and note its full path.
4. Issue a temporary WORKER key for that exact snapshot and Worker ID `laptop-worker-01`. Save it privately.
5. Confirm the approved image exists locally:

```powershell
docker image inspect 'maven@sha256:5496bf5b90ea4ad5af01d63be191f946f15ef5c375a6dd48fc21482a43108ca5' --format '{{.Id}}'
```

If missing, pull that exact approved digest, then inspect it again:

```powershell
docker pull 'maven@sha256:5496bf5b90ea4ad5af01d63be191f946f15ef5c375a6dd48fc21482a43108ca5'
```

6. If you do not already have the artifact-only cache, prepare it from your existing Maven cache:

```powershell
cd "$env:USERPROFILE\bugtrace-ai"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Prepare-BenchmarkCache.ps1
```

This copies already cached artifacts only. It neither downloads missing dependencies nor copies host settings.xml. If the destination already exists, reuse it; do not overwrite a cache in use. Missing artifacts can still cause an offline experiment failure. Retain the restricted environment and prepare dependencies separately through the existing approved process.

In a fourth dedicated PowerShell terminal, set JAVA_HOME, then:

```powershell
cd "$env:USERPROFILE\bugtrace-ai"
$importRecord = (Read-Host 'Paste the full downloaded import JSON path').Trim().Trim('"')
if (-not (Test-Path -LiteralPath $importRecord -PathType Leaf)) { throw 'Import JSON file not found.' }

& .\scripts\Start-Local.ps1 -Component Worker -WorkerMode Benchmark -ImportRecord $importRecord -WorkerId laptop-worker-01
```

If PowerShell blocks the local script, run `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass` in this dedicated window and retry.

At the hidden prompt paste the **worker key**, not a person key. The launcher temporarily clears any human key that could override it. The existing worker startup verifies registration/snapshot hashes, checks the image/cache, then starts polling that registered snapshot. Worker credentials are scoped to the snapshot and worker ID. They expire and can be revoked. The key is not printed or saved by the launcher.

The worker does not create a TEST_READY run or queue an experiment automatically. Start an approved experiment explicitly from its investigation when needed. Record actual execution results separately from worker health.

### Original checkout-fixture worker

If you intentionally need the existing checkout fixture instead of a benchmark:

```powershell
& .\scripts\Start-Local.ps1 -Component Worker -WorkerMode Manual -WorkerId laptop-worker-01
```

The original manual workflow must already have one configured, verified fixture and its approved local Maven image. Use a worker key bound to that fixture snapshot. On a fresh database, import/configure the fixture first; an old hard-coded snapshot UUID is not a valid new registration. This launcher does not manufacture or replace fixture identities.

## 8. Status and smoke checks

```powershell
cd "$env:USERPROFILE\bugtrace-ai"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Get-LocalStatus.ps1
```

Record actual observations:

- PostgreSQL healthy in Compose.
- Backend health UP.
- Frontend HTTP 200 and sign-in works.
- Existing investigation/review UUIDs reload.
- Worker health UP when intentionally started; its terminal confirms the selected snapshot and worker identity. Health alone does not prove that a job was claimed or succeeded.
- Anonymous `/api/projects` returns 401, as before.
- No experiment starts just because you start services.

## 9. Keep direct development startup available

These existing commands still work:

```powershell
# Infrastructure (from repository root)
docker compose --project-name bugtrace-ai --env-file .\.env -f .\infra\compose.yaml up -d --wait postgres

# Backend, in its own terminal with JAVA_HOME configured
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-ManualBackend.ps1

# Frontend, in frontend directory
npm.cmd run dev -- --host 127.0.0.1 --port 5173 --strictPort

# Benchmark worker, in a dedicated terminal
& .\scripts\Set-BugTraceAccess.ps1 -Kind Worker
& .\scripts\Start-BenchmarkWorker.ps1 -ImportRecord $importRecord -WorkerId laptop-worker-01
```

Do not launch both the packaged and direct command for the same component at once.

## 10. Stop and resume without deleting data

1. Wait for active experiments to finish, or cancel through the application's supported controls and record the actual state.
2. Press Ctrl+C in the worker terminal.
3. Press Ctrl+C in the frontend terminal.
4. Press Ctrl+C in the backend terminal.
5. Stop PostgreSQL:

```powershell
cd "$env:USERPROFILE\bugtrace-ai"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Stop-LocalInfrastructure.ps1
```

Add `-WithOllama` if you also want to stop Ollama. The stop command retains containers and named volumes. It does not delete the workspace, saved investigations, artifacts, credentials or models.

To resume, follow Infrastructure → Backend → Frontend → Worker. Sign in using the same saved key and reload the same investigation UUID to confirm persistence.

## 11. Cleanup

Routine cleanup is the stop sequence above. Close dedicated terminals afterward to discard their temporary environment credentials.

For container removal while retaining named volumes, `docker compose ... down` is available after stopping application processes; it also removes the project's containers/network. Do not add `--volumes` or use volume/system prune as routine cleanup. Named-volume deletion destroys the saved PostgreSQL database, including credentials and audit records. Removing the model volume requires downloading models again.

Workspace bases, execution artifacts and acceptance reports are persistent evidence, not startup scratch files. Preserve them unless intentionally archiving that evidence. Use PostgreSQL backups before planned destructive maintenance; do not pipe binary pg_dump output through Windows PowerShell. Existing migration/backup procedures remain separate from daily startup.

## 12. Troubleshooting

| Symptom | Action |
|---|---|
| Docker engine unavailable | Start Docker Desktop and wait for Linux engine readiness. |
| PostgreSQL not healthy | Inspect `docker compose ... logs --tail 80 postgres` locally; verify port 5432 and existing credentials. Do not reset its volume. |
| Password authentication failed | Preserve the existing password/volume. Editing .env does not rotate a database role password. |
| JAVA_HOME / Java mismatch | Set JAVA_HOME to the JDK 21 directory in that terminal. IntelliJ's Java 25 runtime need not change. |
| npm.ps1 blocked | Use npm.cmd and the supplied launcher. |
| Node/Vite engine error | Install a compatible Node LTS release, reopen PowerShell, then run npm.cmd ci. |
| Port already occupied | Reuse the service or stop its own terminal. Do not kill unrelated processes or change ports without updating clients. |
| Backend migration failure | Preserve logs and data; resolve the actual Flyway/PostgreSQL error. Do not edit migration history or mark a failed startup successful. |
| HTTP 401/403 | Use a valid key for the correct project/role. Worker keys and personal keys have different purposes. |
| Import path denied / illegal characters | Supply only the full JSON file path, without a PowerShell prompt or another command. A folder is not a JSON file. |
| Worker cache missing | Prepare/reuse the artifact-only cache; use -CacheSeed only for an approved prepared cache. |
| Worker image missing | Pull/inspect the exact approved digest. Do not substitute another image. |
| Worker running but no results | Confirm a real TEST_READY run was explicitly queued, identity/snapshot bindings match, budgets remain and worker credentials are valid. |
| Existing fixture snapshot missing | Import/configure it through the existing workflow. Do not copy historical UUIDs into a new database. |
| UI opens but API fails | Check backend health and the frontend proxy target 127.0.0.1:8080. |
| Mock mode / no generation model | Local startup can succeed in mock mode. It does not establish live-model performance. |

Do not print or share expanded `docker compose config`, .env contents, access keys or API keys. Use `config --quiet` for syntax validation.

## 13. Acceptance record

```powershell
$folder=Join-Path $env:USERPROFILE 'benchmark-measurements\step46-startup'
New-Item -ItemType Directory -Path $folder -Force | Out-Null
notepad.exe (Join-Path $folder 'acceptance-results.txt')
```

```text
STEP 46 ACCEPTANCE
Tools and versions:
Startup package installed:
Compose configuration valid:
Existing .env preserved:
PostgreSQL health:
Database migrations / schema validation:
Backend health:
Frontend startup and sign-in:
Worker mode:
Worker ID:
Registered snapshot UUID:
Worker registration and polling:
Direct development startup retained:
Normal stop retains database and artifacts:
Restart reloads same investigation UUID:
Restart reloads same review UUID:
No automatic model calls or experiments:
Evidence locations:
Failures or unperformed checks:
Final status: INCOMPLETE until local startup acceptance passes.
```

Record failures and missing data. Startup-script unit checks do not prove Docker readiness, real worker access or restart persistence. No real services or saved data were changed while preparing this package.

References: [Compose readiness](https://docs.docker.com/compose/how-tos/startup-order/), [Compose down and volume behavior](https://docs.docker.com/reference/cli/docker/compose/down/).
