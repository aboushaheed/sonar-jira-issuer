# SonarQube Jira Issuer

An IntelliJ IDEA plugin that bridges SonarQube issues to Jira — load issues from any
SonarQube project and either export batched ticket descriptions to a file, or
**create Jira tickets directly** with a single click.

---

## What's new in 4.0.0

- **Sprint selection** — assign tickets to an active or future sprint, or leave in backlog
- **Dynamic custom fields** — all optional Jira fields for the chosen project/issue-type are loaded
  automatically from the Jira API; fill in any you need (Fix Version, Components, Due Date, …)
- **Auto story-point field detection** — the plugin finds the correct story-points field for your
  Jira setup (Next-Gen `customfield_10016` or Classic `customfield_10028`) and sets it
  automatically; no manual configuration needed
- **Team-safe deduplication via Jira labels** — before creating any ticket the plugin queries Jira
  for `sq-<issueKey>` labels; if another developer already opened a ticket for the same issue it
  is skipped, regardless of which machine created it
- **Bug fixes** — SonarQube token now saves correctly when using the _Test_ button; both tokens
  (SonarQube and Jira) now coexist in Windows Credential Manager; IDE proxy settings are
  honoured for all connections

---

## Features

| Feature | Details |
|---------|---------|
| **Two export modes** | File export (copy-paste) or direct Jira API creation |
| **Secure token storage** | SonarQube and Jira tokens in IntelliJ PasswordSafe (OS keychain / KeePass) |
| **Browse projects** | Discover accessible SonarQube projects without typing keys |
| **Test Connection** | Validate SonarQube and Jira credentials inline |
| **Issue filtering** | Filter by type (Bug, Vulnerability, Code Smell, Security Hotspot) and status |
| **Smart batching** | Group issues into configurable Jira ticket batches |
| **Story Points** | Auto-calculated (1 SP = 1 working day = 8 h, rounded to nearest 0.5); correct field auto-detected |
| **Sprint selection** | Assign to active or future sprint, or leave in backlog |
| **Custom fields** | All optional Jira fields shown automatically after project/issue-type selection |
| **Epic link** | Link created tickets to an existing Epic key |
| **Team deduplication** | `sq-<issueKey>` labels on tickets; JQL search before creation prevents duplicates across the whole team |
| **Incremental export** | Re-runs skip already-exported issues in both file mode and Jira mode |
| **Jira Cloud & Server** | Cloud: Basic auth + API v3 + ADF. Server/DC: Bearer PAT + API v2 + wiki markup |

---

## Requirements

- IntelliJ IDEA 2025.1 or newer (Community or Ultimate)
- A SonarQube instance (SonarCloud or self-managed) with a Personal Access Token
- *(Optional)* A Jira instance with an API token for direct ticket creation

---

## Installation

1. Open IntelliJ IDEA
2. Go to **Settings → Plugins → ⚙ → Install Plugin from Disk…**
3. Select the `.zip` file from `build/distributions/`
4. Restart the IDE

---

## Usage

### 1. Open the tool window

**View → Tool Windows → SonarJira Issuer**   or   **Tools → SonarQube Jira Issuer**

---

### 2. Configure connections — click ⚙ Configure

A dialog opens with two sections:

**SonarQube**

| Field | Description |
|-------|-------------|
| Server URL | Base URL, e.g. `https://sonarcloud.io` or `http://sonar.company.com` |
| Token | Personal Access Token — click **Test** to validate and save |

**Jira**

| Field | Description |
|-------|-------------|
| Server URL | Cloud: `https://company.atlassian.net`   Server: `https://jira.company.com` |
| Email | Atlassian account email — **Cloud only**, leave blank for Server/DC |
| Token | Cloud: API token   Server/DC: Personal Access Token — click **Test** to validate and save |

> Clicking **Test** validates the credentials AND saves them immediately.
> You do not need to click **Save & Close** for the token to persist.

---

### 3. Select a SonarQube project

Click **Browse…** to search accessible projects, or type the key directly in the field.
Check the issue types you want to include (Bug, Code Smell, Vulnerability, Security Hotspot).

---

### 4. Load issues

Click **▶ Load Issues**. A progress bar shows fetched / total counts.
The status bar shows a breakdown by issue type when loading is complete.

---

### 5. Generation Settings

| Field | Default | Description |
|-------|---------|-------------|
| Issues per ticket | 10 | Max SonarQube issues grouped into one Jira ticket |
| Title prefix | `[PROJECT][TECH][QUALITY]` | Prepended to every ticket summary |
| Priority | `Medium` | Jira priority for created tickets |
| Labels (CSV) | `quality,sonar,technical-debt` | Comma-separated Jira labels added to every ticket |
| Output folder | Project root | Where the export file is written (file mode only) |

---

### 6. Jira Target

| Field | Description |
|-------|-------------|
| **Project** | Select the Jira project from the dropdown (loaded automatically when connected) |
| **Issue Type** | Select the issue type; triggers automatic field loading |
| **Fields** | All optional fields for the chosen project/type appear here — fill in any you need (Fix Version, Components, Due Date, etc.); leave blank to skip |
| **Sprint** | `▶ active` sprints and `○ future` sprints are listed; select one or leave on **Backlog (no sprint)** |
| **Epic Link** | Optional — issue key of an existing Epic to link tickets to (e.g. `PROJ-42`) |

> **Story Points** are calculated automatically from issue effort data and assigned to the
> correct field for your Jira setup — no manual configuration needed.

> **Sprint list** is loaded from the Jira Agile API. If the Agile API is unavailable (some
> Jira Server / DC instances) the combo shows only **Backlog (no sprint)**, which is always safe.

---

### 7a. Export to file

Click **⬇ Generate File**.

The plugin writes (or appends) a formatted Jira wiki-markup file to the output folder.
Issues already present in the file are automatically skipped on subsequent runs.

---

### 7b. Create tickets directly in Jira

Click **⬆ Create in Jira**.

The plugin first queries Jira to find any tickets already created for these SonarQube issues
(by the current user or a teammate). A selection dialog then shows:

- Groups with a **grey `PROJ-XX`** tag — already have a Jira ticket, deselected by default
- Groups with a **green ✓** — new, selected by default

Review the selection and click **Create N Ticket(s)**. Tickets are created in the background
with a progress indicator.

**What gets set on each ticket:**
- Summary, ADF/wiki-markup description with issue details and SonarQube links
- Priority, labels (including `sonar-jira-issuer` sync label and per-issue `sq-<key>` labels)
- Story points (auto-detected field)
- Sprint (if selected)
- Epic link (if provided)
- Any additional fields filled in the **Fields** editor

---

### Jira authentication reference

| Instance type | Email field | Token field |
|---------------|-------------|-------------|
| **Jira Cloud** (`*.atlassian.net`) | Your Atlassian email | API token from [id.atlassian.com](https://id.atlassian.com/manage-profile/security/api-tokens) |
| **Jira Server / Data Center** | Leave blank | Personal Access Token (PAT) |

---

## Team deduplication

Every ticket created by this plugin receives two types of labels:

1. **`sonar-jira-issuer`** — marks the ticket as created by this plugin (findable via JQL)
2. **`sq-<issueKey>`** — one label per SonarQube issue covered by the ticket

Before creating any new tickets the plugin runs a JQL query:

```
project = "PROJ" AND labels in ("sq-AY1234", "sq-AY5678", …)
```

Any groups where all issues already have a Jira ticket are shown as pre-skipped in the
selection dialog. This works across the whole team — no shared file or shared settings needed.

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
├── actions/         Menu action to open the tool window
├── api/             SonarQube + Jira HTTP clients, DTOs (SonarProject,
│   │                JiraProject, JiraIssueType, JiraSprint, …)
│   └── dto/         SonarIssue, SonarIssuesResponse
├── model/           Domain models (IssueGroup, IssueType, JiraFormatterConfig)
├── service/         Business logic — grouping, text formatting, file export,
│                    Jira description builder (ADF + wiki), issue mapping /
│                    deduplication, story-point calculation
├── settings/        Persistent settings (XML) + PasswordSafe token storage
├── toolwindow/      Main panel (SonarJiraPanel), connection dialog,
│                    project browser dialog, ticket selection dialog
└── util/            EffortParser, NotificationHelper
```

---

## License

Copyright © 2025 **Abdelmoula Souidi**. All rights reserved.

---

## Contributing

This is a personal project. Bug reports and suggestions are welcome via GitHub Issues.
