# BugTrace AI IntelliJ plugin 0.2.0

Project connection settings, committed bug submission, investigation status, source evidence navigation and saved reproduction/verification previews. Patch decisions and detailed diff review open in the existing dashboard.

Build against the pinned IntelliJ IDEA 2026.2.3 SDK with `Build-Local.ps1`. The build compiles Kotlin, executes core checks in disposable directories and packages with the bundled Java ZIP writer. It prints the plugin ZIP and build record paths.

The full installation and acceptance guide is `outputs/step45-evidence-navigation/README.md` in the delivered package workspace. Keep all HTTP requests, Password Safe reads and Git/filesystem validation in background tasks. IDE previews are read-only and do not install, run or approve artifacts. Local source paths are constrained to the configured module, and stale or changed local source falls back to the saved excerpt.

Keys remain in the IDE Password Safe. Dashboard links never contain credentials. Per-project backend permissions remain authoritative.
