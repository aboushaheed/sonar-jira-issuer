# SonarQube Jira Issuer

An IntelliJ IDEA plugin that bridges SonarQube issues to Jira — load issues from
any SonarQube project and export batched, ready-to-paste Jira ticket descriptions
with Jira wiki markup tables, story points, and direct issue links.

---

## Features

- **Secure token storage** — SonarQube token is stored in IntelliJ PasswordSafe
  (OS keychain or KeePass), never in plain text
- **Browse projects** — discover accessible SonarQube projects directly from the
  IDE with a searchable dialog (no manual key entry needed)
- **Test Connection** — validate your token before loading issues
- **Issue filtering** — filter by type (Bug, Vulnerability, Code Smell,
  Security Hotspot) and status (Open by default)
- **Smart batching** — group issues into configurable Jira ticket batches
- **Story Points** — automatically calculated (1 SP = 1 working day = 8h,
  rounded up to nearest 0.5)
- **Incremental export** — re-runs skip already-exported issues (deduplication
  by issue key)
- **Jira wiki markup** — `||tables||`, `*bold*`, `[links|url]` render natively
  in Jira Server, Data Center, and Jira Cloud (legacy editor)

---

## Requirements

- IntelliJ IDEA 2025.2 or newer (Community or Ultimate)
- A SonarQube instance (SonarCloud or self-managed) with a Personal Access Token

---

## Installation

1. Open IntelliJ IDEA
2. Go to **Settings → Plugins → ⚙ → Install Plugin from Disk…**
3. Select the `.zip` file from `build/distributions/`
4. Restart the IDE

---

## Usage

### 1. Open the tool window

Go to **View → Tool Windows → SonarJira Issuer** (or **Tools → SonarQube Jira Issuer**).

### 2. Configure connection

| Field | Description |
|-------|-------------|
| **Server URL** | Base URL of your SonarQube instance, e.g. `https://sonarcloud.io` |
| **Token** | SonarQube Personal Access Token — click **Test** to validate and save |
| **Project Key** | Click **Browse…** to pick a project from the list |

### 3. Load issues

Select the issue types you want to include, then click **▶ Load Issues**.
A progress bar shows how many issues have been fetched.

### 4. Configure generation

| Field | Default | Description |
|-------|---------|-------------|
| Issues per ticket | 10 | Max issues grouped into one Jira ticket |
| Title prefix | `[PROJECT][TECH][QUALITY]` | Prepended to each ticket summary |
| Jira Issue Type | Task | |
| Priority | Medium | |
| Labels | `quality,sonar,technical-debt` | Comma-separated |
| Output folder | Project root | Where `jira-tickets_<key>.txt` is written |

### 5. Generate

Click **⬇ Generate Jira Ticket File**. The file is written (or appended) to the
output folder. Issues already present in the file are automatically skipped.

### 6. Import into Jira

Open the generated `.txt` file and copy each **DESCRIPTION** block into the
corresponding Jira ticket's description field. The Jira wiki markup renders
natively in both Server/Data Center and Jira Cloud (legacy editor).

---

## Building from source

```bash
# Build and run in IntelliJ sandbox
./gradlew runIde

# Build distributable ZIP
./gradlew buildPlugin

# Run unit tests
./gradlew test

# Verify plugin compatibility
./gradlew verifyPlugin
```

---

## Project structure

```
src/main/kotlin/com/sonarjiraissuer/plugin/
├── actions/            Menu action to open the tool window
├── api/                SonarQube HTTP client + DTOs
│   └── dto/
├── model/              Domain models (IssueGroup, IssueType, …)
├── service/            Business logic (grouping, formatting, export)
├── settings/           Persistent settings + PasswordSafe integration
├── toolwindow/         Swing UI panel + project browser dialog
└── util/               EffortParser, NotificationHelper
```

---

## License

Copyright @MIT

---

## Contributing

This is a personal project. Bug reports and suggestions are welcome via GitHub Issues.

