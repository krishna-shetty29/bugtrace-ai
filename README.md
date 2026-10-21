\# BugTrace AI



BugTrace AI helps developers investigate reported bugs using committed source snapshots, retrieved evidence, generated reproduction tests, isolated experiments, and human patch review.



Developers can submit reports through the browser or IntelliJ plugin, inspect saved evidence, and review proposed changes. AI-generated explanations and patches remain proposals until supported by execution and verification evidence.



\## Project status



\- Local application and worker: implemented; local acceptance checks reported as passed.

\- Hosted CI: backend, worker, frontend and plugin jobs passed for commit `752a0a1`.

\- Hosting providers: not selected.

\- Deployment and remote HTTPS validation: pending.

\- Controlled baseline-versus-full-workflow performance results: not reported.



\[Successful CI run](https://github.com/krishna-shetty29/bugtrace-ai/actions/runs/39577914586)



That run validates its specific commit. Check GitHub Actions for the status of later commits.



\## Problem and objectives



Bug reports often omit reproducible steps, the exact source revision, or evidence supporting a diagnosis. BugTrace AI brings these records together.



The project aims to:



\- Capture reports against an identified Git commit.

\- Retrieve relevant source, relationships, documentation and execution evidence.

\- Generate and inspect reproduction tests.

\- Execute approved trusted samples under enforced limits.

\- Preserve results, source hashes, diagnoses and review decisions.

\- Support project-scoped collaboration and IDE navigation.

\- Evaluate the full workflow against a controlled single-request baseline.



\## Main features



\- Browser investigation dashboard.

\- IntelliJ submission and investigation navigation.

\- Committed snapshot import and verification.

\- Lexical, semantic and relationship-based evidence retrieval.

\- Bounded agent roles and tool access.

\- Registered benchmark test execution.

\- Durable execution queue with leases and cancellation.

\- Worker availability and result-upload recovery.

\- Evidence-linked diagnosis and patch review.

\- Owner, Investigator, Reviewer and Viewer permissions.

\- Snapshot-bound worker credentials and audit records.



A queued job is not proof of execution. A proposed patch or approved review is not proof that a change was applied or deployed.



\## Architecture



```mermaid

flowchart TD

&#x20;   IDE\[IntelliJ plugin] --> API\[Backend API]

&#x20;   UI\[Browser dashboard] --> API

&#x20;   API --> DB\[(PostgreSQL and pgvector)]

&#x20;   API --> FILES\[Snapshots and artifacts]

&#x20;   API --> MODEL\[Configured generation provider]

&#x20;   API --> EMB\[Embedding service]

&#x20;   WORKER\[Laptop worker] -->|Outbound polling and result uploads| API

&#x20;   WORKER --> BASE\[Verified local snapshot]

&#x20;   WORKER --> DOCKER\[Restricted Docker experiment]

```



The worker initiates outbound connections. Docker and its socket stay local to the laptop.



The planned hosted backend must use persistent database and artifact storage. It must not depend on laptop filesystem paths.



\## Technology stack



| Technology | Purpose |

|---|---|

| Java 21 and Spring Boot | Backend API and worker services |

| PostgreSQL | Investigations, queue state, permissions and audit records |

| pgvector | Semantic evidence retrieval |

| Flyway | Versioned database migrations |

| JavaParser | Java syntax, symbols and relationships |

| React and TypeScript | Browser interface |

| Vite | Frontend development and production build |

| Kotlin and IntelliJ Platform | IDE plugin |

| Git and SHA-256 | Revision capture and content verification |

| Docker and Docker Compose | Local infrastructure and restricted experiments |

| Maven, Gradle and npm | Builds, dependencies and tests |

| GitHub Actions | Continuous integration |

| PowerShell | Startup and acceptance-check tooling |



The installed embedding model supports evidence retrieval. It does not replace a separately configured generation model.



\## Data structures and algorithms



\- Syntax trees support Java source analysis.

\- Relationship graphs support neighboring-source navigation.

\- Hash maps and sets support candidate lookup and deduplication.

\- Reciprocal rank fusion combines retrieval rankings.

\- Sorting selects the highest-ranked evidence.

\- Database-backed queue states and leases coordinate execution.

\- Bounded exponential backoff handles temporary connection failures.

\- Content hashing detects changes to snapshots and generated tests.



See the \[retrieval and DSA explanation](docs/final-documentation/final-documentation.md) for implementation details and limitations.



\## Repository layout



```text

backend/          Backend API, database migrations and tests

worker/           Authenticated execution worker and tests

frontend/         Browser dashboard

intellij-plugin/  Kotlin IDE plugin

infra/            Local infrastructure configuration

scripts/          Startup, validation and evidence tools

fixtures/         Local sample applications

benchmarks/       Benchmark-related project material

docs/             Setup, CI and technical documentation

```



\## Local setup



Required tools:



\- Git.

\- JDK 21 for the backend and worker.

\- Docker Desktop with Linux containers and Docker Compose.

\- Node.js and npm compatible with the frontend lockfile.

\- PowerShell.



Preserve an existing `.env` and PostgreSQL volume. For a new installation, follow the configuration instructions in the \[setup guide](docs/final-documentation/setup-guide.md). Never commit credentials.



Run the following from the repository root in separate PowerShell windows.



\### 1. Start infrastructure



Start Docker Desktop, then run:



```powershell

powershell.exe -NoProfile -ExecutionPolicy Bypass `

&#x20;   -File .\\scripts\\Start-Local.ps1 `

&#x20;   -Component Infrastructure

```



\### 2. Start the backend



Set `JAVA\_HOME` to your installed JDK 21 directory, then run:



```powershell

powershell.exe -NoProfile -ExecutionPolicy Bypass `

&#x20;   -File .\\scripts\\Start-Local.ps1 `

&#x20;   -Component Backend

```



Flyway applies pending database migrations during startup.



\### 3. Start the frontend



Install locked dependencies on first setup or after lockfile changes:



```powershell

Set-Location frontend

npm.cmd ci

Set-Location ..

```



Start the frontend:



```powershell

powershell.exe -NoProfile -ExecutionPolicy Bypass `

&#x20;   -File .\\scripts\\Start-Local.ps1 `

&#x20;   -Component Frontend

```



Open:



```text

http://127.0.0.1:5173

```



Existing users sign in with their saved project access key. New databases require the documented one-time access initialization.



\### 4. Start the worker when experiments are needed



Worker startup requires:



\- A registered, verified benchmark snapshot.

\- Its downloaded case import JSON.

\- A matching worker credential and worker ID.

\- A verified local snapshot copy.

\- The approved execution image.

\- A prepared artifact-only dependency cache.



Follow the \[worker setup instructions](docs/final-documentation/setup-guide.md). Submission and saved-evidence browsing do not require a running worker.



\## Permissions and execution policy



Human roles are:



\- Project Owner.

\- Investigator.

\- Reviewer.

\- Viewer.



Workers use separate credentials bound to their project, snapshot and worker ID.



The demonstration executes explicitly registered trusted samples only. Repository release, case module, content hashes, generated-test identity, execution environment and budgets are validated.



Credentials belong in private storage and supported authorization headers. Do not put access keys in URLs, Git commits, screenshots or reports.



\## Continuous integration



Ordinary CI checks:



1\. Backend build and tests.

2\. Worker build and tests.

3\. Frontend type checking and build.

4\. Plugin build.



Paid AI calls remain outside ordinary CI. Live-model evaluations require explicit execution and configured limits.



\[GitHub Actions](https://github.com/krishna-shetty29/bugtrace-ai/actions)



\## Evaluation and results



The comparison protocol uses fresh investigations, identical public inputs, the same exact generation model, alternating run order and explicit budgets.



Frozen outputs must be independently evaluated using their exact response files and hashes. Invalid outputs, timeouts and infrastructure failures remain recorded.



No quantitative performance advantage is claimed here. CI success, mock outputs and individual approved reviews are not benchmark comparison results.



See:



\- \[Evaluation methodology](docs/final-documentation/final-documentation.md)

\- \[Benchmark results ledger](docs/final-documentation/benchmark-results.md)



\## Known limitations



\- Snapshot submission initially includes committed source only.

\- Uncommitted and unsaved changes are excluded.

\- Mock responses validate workflow behavior, not model quality.

\- Retrieval and static analysis can miss relevant evidence.

\- Snapshot verification alone does not establish Docker readiness.

\- Patch review does not automatically apply, merge or deploy changes.

\- Database persistence does not replace persistent artifact storage.

\- Remote HTTPS, hosted snapshot handling and hosted restart behavior require deployment validation.

\- Hosting providers and zero-fee eligibility remain unverified.



\## Documentation



\- \[Final technical documentation](docs/final-documentation/final-documentation.md)

\- \[Database migration reference](docs/final-documentation/database-reference.md)

\- \[API source and permission reference](docs/final-documentation/api-reference.md)

\- \[Setup guide](docs/final-documentation/setup-guide.md)

\- \[CI guide](docs/final-documentation/continuous-integration.md)

\- \[Benchmark results](docs/final-documentation/benchmark-results.md)

\- \[Inspected source hashes](docs/final-documentation/source-manifest.json)



The API reference is derived from implementation source; it is not an OpenAPI specification. Source changes require corresponding documentation review.

