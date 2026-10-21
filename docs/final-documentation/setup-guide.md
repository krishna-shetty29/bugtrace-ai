# Setup and operations

Use a full checkout, Git, Docker Desktop (Linux containers), JDK 21, Node/npm compatible with the lockfile, and PowerShell. Plugin compilation uses its Gradle toolchain/CI settings; do not change the backend JDK to match IntelliJ's runtime.

Preserve an existing root .env. On a new installation only, privately configure database credentials using infra/local-startup.env.example. Never copy an example over working credentials. Flyway initializes/updates the database when the backend starts. Use the existing Compose project/volume; never delete volumes to resolve an authentication error.

In dedicated PowerShell terminals, from the repository root:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.12.1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Local.ps1 -Component Infrastructure
```
Then run Backend and Frontend in separate terminals:
```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Local.ps1 -Component Backend
```
```powershell
Set-Location frontend
npm.cmd ci
Set-Location ..
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Local.ps1 -Component Frontend
```
Open http://127.0.0.1:5173. Existing users use saved project credentials. For a genuinely new database follow the one-time bootstrap in local-startup.md; privately save all owner keys and restart normally afterward.

Worker prerequisites: verified registered benchmark snapshot, import JSON, matching worker key and ID, approved local image and artifact-only cache. Prepare-WorkerSnapshot.ps1 creates a matching local base for cross-machine configuration. See its parameter help and the Step 48 package guide; do not substitute a containing folder for the JSON file.

Worker terminal:
```powershell
$importRecord = (Read-Host 'Full import JSON path').Trim().Trim('"')
$localBase = (Read-Host 'Verified local base path').Trim().Trim('"')
if (-not (Test-Path -LiteralPath $importRecord -PathType Leaf)) { throw 'Import file missing' }
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Local.ps1 -Component Worker -WorkerMode Benchmark -ImportRecord $importRecord -LocalBase $localBase -WorkerId laptop-worker-01 -BackendUrl 'http://127.0.0.1:8080'
```
Paste the worker key only at the hidden prompt. For eventual remote use substitute an actual tested HTTPS backend origin. Do not use a placeholder or disable certificate validation. Worker startup does not enqueue experiments.

Validation commands, separately from the repo root:
```powershell
& .\scripts\Test-TrustedSampleRestrictions.ps1 -IncludeDocker
& .\scripts\Test-WorkerPresence.ps1
& .\scripts\Test-WorkerDashboard.ps1
& .\scripts\Test-WorkerReconnect.ps1
```
Save each printed evidence folder. Hosted Code checks must pass backend, worker, frontend and plugin jobs for the same final commit. Paid/live generation stays out of ordinary CI.

Troubleshooting: 401 means authentication needs inspection; 403 means permission/binding denial; an invalid import path requires the actual downloaded file; missing cache requires approved cache preparation; missing dependencies do not justify enabling experiment networking; stale/expired leases need preserved evidence rather than blind experiment reruns. Restart from the repository root, not an installer payload folder.

Shutdown: stop direct foreground services with Ctrl+C. Use the supplied infrastructure stop script after checking its help. Preserve named volumes, snapshots and outbox files needed for recovery. Cleanup of retained evidence is a separate deliberate action.

Deployment is deferred. Select providers, persistent storage and HTTPS configuration later, then test authenticated remote worker communication, project isolation, result recovery and hosted restarts. Local Windows paths must never be sent as executable hosted paths.
