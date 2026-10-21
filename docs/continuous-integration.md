# Step 47 — Continuous integration

This package adds GitHub Actions workflows. The ordinary **Code checks** workflow runs on every push and pull request, with four hosted jobs. It does not read the laptop's `.env`, receive provider credentials, start the platform worker, or make live-model requests. Maven provider tests use fake localhost servers or mocks, not a paid provider.

| Job | Runtime | Checks |
|---|---|---|
| Backend build and tests | JDK 21, disposable PostgreSQL 17/pgvector service | Maven clean verify; Spring context/schema; unit tests; enabled AI persistence, queue and team-access PostgreSQL checks |
| Worker build and tests | JDK 21 | Maven clean verify; overlay, queue, permission, evidence and policy tests |
| Frontend types and build | Node 24 | npm ci; npm run build, which runs tsc -b then Vite |
| Plugin build | JDK 25, Gradle 9.5.0 | coreChecks, buildPlugin, verifyPluginStructure against pinned IntelliJ 2026.2.3 |

Other environment-gated PostgreSQL, local benchmark-catalog and real Docker checks remain opt-in; their skipped results must not be described as executed acceptance. The worker's real restricted-Docker environment test is not enabled in ordinary CI. Live IDE acceptance remains separate from plugin compilation/structure checks.

The optional **Live benchmark - proposal only** workflow is manual-only, isolated from ordinary CI and uses a dedicated benchmark machine. It is not a full experiment/patch evaluation runner.

## 1. Ensure CI has your actual source

The installed IntelliJ ZIP and the files committed to Git are separate. Inspection while preparing this package found `intellij-plugin/build.gradle.kts` and plugin.xml still at **0.1.0** in your repository, even though Step 45 supplied 0.2.0 as a ZIP.

If you want CI to build the Step 45 version, install its source update first:

```powershell
cd 'C:\Users\ADMIN\Documents\Codex\2026-10-13\step-1-install-the-initial-tools\outputs\step45-evidence-navigation'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Install-Update.ps1 -CheckOnly
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Install-Update.ps1
```

If the source installer rejects a locally modified file, review the difference; do not overwrite your edits blindly. This CI package does not replace plugin sources or change the installed IDE plugin.

## 2. Install the CI package

```powershell
cd 'C:\Users\ADMIN\Documents\Codex\2026-10-13\step-1-install-the-initial-tools\outputs\step47-continuous-integration'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Install-CI.ps1 -CheckOnly
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Install-CI.ps1
```

It installs the two workflow files, plugin CI init script, workflow policy validator, explicit benchmark script and this guide. All hashes are checked before copying. Differing existing files cause rejection, with nothing copied. It does not modify .env, app configuration, credentials, database or saved investigations.

Your local backend/frontend/worker terminals need not run for ordinary CI. GitHub starts its own fresh jobs and disposable database.

## 3. Check the repository and tracked inputs

```powershell
cd "$env:USERPROFILE\bugtrace-ai"
git status --short
git remote -v
git ls-files -- backend/pom.xml worker/pom.xml frontend/package-lock.json intellij-plugin/build.gradle.kts
```

All four build inputs must be tracked, along with their sources, Maven wrappers and plugin descriptor. If the plugin directory is newly installed and untracked, review it and stage its source:

```powershell
git add -- intellij-plugin
git diff --cached --name-only
```

Its build directories are ignored. Confirm no build output, personal keys, .env, .idea, benchmark private grading folders or unrelated edits are staged. Review previously staged changes too; a commit includes all staged files, not only the last git add command.

If no GitHub remote exists, create a repository in your own GitHub account, then add its actual URL as origin. Do not paste an example account URL or credential into a command. Keep private grading evidence outside the application repository.

## 4. Review and commit the new CI files

```powershell
git add -- .github/workflows/ci.yml .github/workflows/live-benchmark.yml ci/plugin.init.gradle.kts ci/ValidateWorkflows.java scripts/Invoke-BudgetedBenchmark.ps1 docs/continuous-integration.md
git diff --cached --check
git diff --cached --stat
git diff --cached
```

Confirm ordinary CI has `BUGTRACE_AI_PROVIDER: mock`, an empty OPENAI_API_KEY, no secrets references and four hosted jobs. Provider tests point their fake API calls at local test servers. No live benchmark trigger is attached to push or pull_request.

After reviewing the complete staged changes:

```powershell
git commit -m "Add build and test CI with separate budgeted benchmark jobs"
git push
```

If your branch has no upstream, use the upstream command Git prints for the actual current branch. Do not force-push or bypass repository protections to enable CI.

## 5. Watch the first ordinary CI run

1. Open the GitHub repository.
2. Click **Actions**.
3. Open **Code checks** for your pushed commit.
4. Wait for all four jobs.
5. Open each job and inspect its build/test output.
6. Download backend-results, worker-results, frontend-dist and plugin-results from the workflow summary as available.

The plugin job may take longer on its first run because it downloads the pinned IntelliJ SDK and Gradle dependencies. It builds the version in Git; installing a newer ZIP into IntelliJ alone does not update the repository source.

Required outcome: all four jobs succeed for the same commit. A skipped, canceled, queued or failed job is not a pass. Artifact uploads do not override a failed build.

If no run appears, confirm the workflow files were pushed, GitHub Actions is enabled under repository Settings → Actions, and your repository's policy allows the listed actions. Manual Run workflow appears once workflow_dispatch is present on the default branch.

## 6. Check failure detection

Use a disposable branch/PR if you want to verify enforcement:

1. Make a small deliberate compile error in a disposable source change.
2. Push it.
3. Confirm the corresponding job fails.
4. Revert only your deliberate change through a normal commit.
5. Confirm all jobs pass again.

Do not leave the deliberate failure on your working main branch or hide it with continue-on-error, skipTests, or ignored exit codes.

## 7. Require CI before merging

After the first successful run, configure your default branch's protection/ruleset in GitHub Settings:

- Require pull requests as appropriate for your project.
- Require the four displayed status checks: Backend build and tests, Worker build and tests, Frontend types and build, Plugin build.
- Save the rule for the actual default branch.

Status checks become selectable after they have run. Feature availability depends on your repository and GitHub plan. If required checks cannot be configured, record that limitation; do not claim they block merging.

## 8. Live benchmarks are a separate explicit operation

Do not add OPENAI_API_KEY or your laptop's .env to ordinary CI. The generation provider stays in backend configuration. The manual runner calls the existing localhost backend, which validates registered public case evidence and enforces model-call/output reservations and deadlines.

This supplied job is **PROPOSAL_ONLY**:

- Fresh backend run UUID for every invocation.
- 1–8 model requests, explicitly chosen.
- Configured per-call output-token limit and total output reservation.
- Server wall deadline of 120–1800 seconds.
- Zero experiment dispatches and zero compiler repairs from this runner.
- No automatic retries after a failed or uncertain request.
- Saved run IDs, step IDs, model-call records, timestamps, counters and file hashes.
- No private grading, approval or patch application.

It can stop at TEST_READY, FINISHED_NO_PATCH, or a declared budget/termination state. TEST_READY means proposed source, not compiled or executed source. PLAN_READY at a call limit is a partial result, not a completed reproduction. Full workflow experiment/patch grading still uses its separate explicitly budgeted evaluation procedure.

There is **no monetary cost cap or input-token cap enforced by this script**. Do not claim one. Read actual usage in saved model-call records and provider billing. Output reservation is a limit, not measured output usage. The job records unavailable cost as UNKNOWN.

### Option A: run explicitly on your laptop

Start the configured live backend intentionally, import/verify the public case, and prepare its public evidence through the platform. Mock is rejected for this live job. The currently implemented live adapter is OpenAI; no Ollama generation adapter is added by this CI package.

In a dedicated PowerShell terminal:

```powershell
cd "$env:USERPROFILE\bugtrace-ai"
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
& .\scripts\Set-BugTraceAccess.ps1 -Kind Human
$importRecord=(Read-Host 'Paste the full public case import JSON path').Trim().Trim('"')
$model=(Read-Host 'Enter the exact generation model configured on the backend').Trim()

& .\scripts\Invoke-BudgetedBenchmark.ps1 -ImportRecord $importRecord -ExpectedModel $model -MaxCalls 2 -MaxOutputReservation 3000 -WallSeconds 600 -AllowLiveProvider
```

The default 3000 reservation covers two calls only when the backend output limit is at most 1500. Use the actual enforced per-call setting when choosing your total reservation. The script rejects inconsistent limits or a different model before sending a generation request. It never chooses a paid model for you.

At the hidden prompt, use an Owner/Investigator personal project key, not a worker key. Do not paste keys into chat, reports or commands. Provider credentials remain on the backend.

The script prints a new run UUID and evidence folder. Inspect job.json, final-run.json, step results, model-call records and answer-hashes.csv. A failure or timeout may leave a reserved/in-flight backend call; inspect that UUID before manually trying a new run.

### Option B: manual GitHub benchmark job on a dedicated trusted machine

Ordinary CI stays on hosted Ubuntu runners. Only this optional job uses a dedicated trusted Windows benchmark runner with the existing backend/public repository configuration.

1. Restrict this runner to trusted repository workflows/contributors. Do not use it for untrusted public pull-request code.
2. In repository Settings → Actions → Runners, follow GitHub's New self-hosted runner instructions for Windows. Keep its registration token private. Assign custom label **bugtrace-benchmark**.
3. Run it interactively under the same operator whose backend/public snapshot paths are configured. GitHub-hosted localhost cannot reach your laptop's localhost; the benchmark runner and backend must be on the same machine.
4. Create environment **live-benchmarks** under repository Settings → Environments.
5. Restrict it to the default branch and enable approval protection where your plan supports it. If the required protection is unavailable, use Option A rather than treating an unprotected workflow as approval-gated.
6. Add environment secret **BENCHMARK_PROJECT_KEY** containing a scoped personal project credential for this benchmark project. This is not the provider API key.
7. Keep the public benchmark snapshot verified, evidence indexed and live provider intentionally configured. Keep private grading evidence outside searchable snapshots.
8. On the default branch, open Actions → **Live benchmark - proposal only** → Run workflow.
9. Enter the actual local import-record path, exact model and explicit call/output/wall limits. This dispatch intentionally authorizes live requests and can incur provider charges.
10. Approve the environment job if required. Inspect the saved artifacts and actual backend state afterward.

There is no schedule, push trigger or PR trigger for live benchmarks. Non-default branches are rejected by the job gate. Inputs are passed as environment data, not interpolated into shell code. Canceling the workflow does not prove an already submitted provider request was canceled; use the backend records and actual provider usage.

## 9. Troubleshooting

| Failure | Check |
|---|---|
| No workflow run | Files pushed, Actions enabled, valid workflow and repository policy. |
| Missing source/build file | Ensure actual component sources and lockfiles are committed; local installation is not a Git commit. |
| Backend PostgreSQL error | Disposable service health and migration output; CI never uses the laptop database. |
| Backend test HTTP error | Provider tests require localhost fake servers; these are not live-model calls. |
| Worker Docker test skipped | Expected unless its explicit environment flag is enabled; not a real Docker acceptance pass. |
| Plugin coreChecks missing argument | Ensure CI passes the included init script with -I. |
| Plugin SDK/dependency download failure | Preserve the job failure and inspect repository connectivity; do not silently substitute an IDE version. |
| Plugin version differs from installed ZIP | Commit the desired plugin source/descriptor. CI builds committed source only. |
| Node build failure | Node 24, package-lock.json and npm ci; do not ignore TypeScript failures. |
| Live job queued indefinitely | Dedicated runner online with all labels, environment approval and default branch selected. |
| Live job rejects mock/model/limits | Configure the intended provider separately, enter its exact model and valid reservation. No generation request should be sent on these failures. |
| Cannot configure protection | Record plan/repository limitation; use explicit local benchmark execution if needed. |

## 10. Save acceptance results

```powershell
$folder=Join-Path $env:USERPROFILE 'benchmark-measurements\step47-ci'
New-Item -ItemType Directory -Path $folder -Force | Out-Null
notepad.exe (Join-Path $folder 'acceptance-results.txt')
```

```text
STEP 47 ACCEPTANCE
Repository:
Commit SHA:
Workflow run URL:
Backend build and tests:
Backend test counts / skipped integration coverage:
Worker build and tests:
Worker test counts / real Docker coverage:
Frontend type checking and build:
Plugin source version:
Plugin core checks, build and structure:
Artifacts downloaded:
Ordinary CI uses mock and no provider credential:
Ordinary push/PR triggers verified:
Failure detection:
Required branch checks configured, or limitation:
Live benchmarks isolated from ordinary CI:
Explicit benchmark mode: PROPOSAL_ONLY
Live benchmark executed: NOT RUN unless intentionally performed
Live benchmark run UUID / actual usage, if run:
Budget limits actually enforced:
Missing checks / infrastructure failures:
Final status: INCOMPLETE until all four hosted jobs pass and evidence is recorded.
```

Do not mark this step complete from workflow-file creation or local parsing alone. At least one real hosted run must establish all four jobs for the committed application source. A paid benchmark is optional; its isolation and enforced limits can be reviewed without spending on a model call.

## Validation during preparation

See validation.json for actual local results. Frontend type checks and a production bundle were executed with outputs in a disposable directory. Java test attempts encountered sandbox loopback, Git temporary-directory and atomic-file-move restrictions; they are not reported as clean passes. A local Gradle-init probe could not connect to its daemon in this sandbox, so the complete Gradle plugin job is pending its hosted run. No live provider request was made, no user database was modified and no workflow was pushed while preparing this package.

Official references: [GitHub manual workflows](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow), [GitHub environments](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments), [IntelliJ Gradle requirements](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html), [Kotlin/Gradle compatibility](https://kotlinlang.org/docs/gradle-configure-project.html).
