# claude-local-bridge

A remote AI agent proposes a task. **You read the exact text and press Approve** —
in Telegram or in a Windows notification. Only then does Claude Code run it on your
machine, in a folder from a fixed list, with a fixed set of tools. The answer comes
back to Telegram and to the agent that asked.

```
cloud agent ──signed POST──▶ bridge (your PC)   ──▶ Telegram: [Approve] [Reject]
                                    │                ──▶ Windows toast: [Approve] [Reject]
                                    │  first press wins, the other channel is updated
                                    ▼
                          claude -p in E:\project  ──▶ result to Telegram + GET /tasks/{id}, /progress, /log
```

## Why

Cloud agents can't touch your local files, and handing them a shell is a bad idea.
Existing bridges to local Claude Code either trust the caller (an API key is enough
to run anything) or approve individual tool calls mid-run. Here the unit of trust is
**the task text itself**: the remote side is treated as untrusted input, and nothing
runs until a human has seen exactly what will be sent to Claude Code.

## Guarantees and how they are enforced

| Guarantee | Mechanism |
|---|---|
| Only someone holding the key can submit | HMAC-SHA256 over timestamp, nonce, method, path and raw body; 5-min window; nonces remembered (no replay); constant-time compare |
| What you see is what runs | Plain-text messages only (no Markdown/HTML parse mode); text with invisible or reordering characters (bidi overrides, zero-width, control chars) is **refused**, not shown; SHA-256 prefix shown in every channel |
| The remote side picks from a menu | A task names a folder, never a path; allowed tools are per folder; unknown JSON fields are rejected; a resume session id must look like an id (it can't start with `-` and become a CLI flag). New folders and tools exist only after the owner approved a request for them, and requests that grant a whole shell, a drive, system or profile folders, secrets or the bridge itself are refused before anyone is asked |
| No command-line injection on Windows | Task text goes to `claude -p` via **stdin**, never as an argument (`.cmd` shims re-parse arguments through cmd.exe) |
| Exactly one decision | One atomic `UPDATE ... WHERE status='PENDING'` in SQLite; whichever channel is first wins, the other is edited/removed. Tested with 8 concurrent presses |
| Approval page can't be reached from outside | It runs on a **separate** loopback port the tunnel never points to, needs a per-task 256-bit token (good for one decision), checks the Host header (DNS rebinding) |
| Telegram buttons only work for you | Callbacks accepted only from the configured chat/user id |
| Big outputs can't flood anything | Claude's stdout/stderr stream to files, never held in memory; DB, Telegram and API get the first 3500 chars, the full text is saved as `outputs/<folder>/<session id>/<task id>.txt` (deleted after `output-retention`, 30 days by default), attached to Telegram as a file and available at `GET /tasks/{id}/output` |
| The agent can't approve its own task | The key keeps outsiders out, but the agent that sends tasks usually has a shell and can read its own key, so the signature is not what stops it. The human approval is: decisions only come from your Telegram account or from the local page on your PC, and the agent can reach neither |
| No silent runs | Pending tasks expire after 30 min; tasks interrupted by a restart are marked failed, never re-run; every task and decision is kept in SQLite |

## Run

Requirements: Java 21, Claude Code CLI, a Telegram bot token.

```powershell
$env:BRIDGE_HMAC_SECRET = "<64 random hex chars>"
$env:BRIDGE_TELEGRAM_TOKEN = "<bot token>"
$env:BRIDGE_TELEGRAM_CHAT_ID = "<your user id>"
mvn package
java -jar target/claude-local-bridge-0.1.0.jar
```

Folders, tools and timeouts are in `src/main/resources/application.yml`.
If `claude` is not found, set `bridge.runner.command` to the full path of `claude.exe`.
Messages, toasts and the local page are in English; set `$env:BRIDGE_LANGUAGE = "ru"`
(or `bridge.ui.language`) for Russian. Texts live in `src/main/resources/ui/<language>.properties`.

### In the background on Windows

`scripts/windows/` starts the bridge and the Cloudflare tunnel at logon, without
windows and without admin rights:

```powershell
mvn package
powershell -ExecutionPolicy Bypass -File scripts\windows\install-autostart.ps1
```

This creates `%LOCALAPPDATA%\claude-local-bridge\` with a copy of the jar, the start/stop
scripts and `bridge.env` (KEY=VALUE settings with a freshly generated
`BRIDGE_HMAC_SECRET`; existing settings are never overwritten), and a shortcut in the
Startup folder. Fill `BRIDGE_TELEGRAM_TOKEN` and `BRIDGE_TELEGRAM_CHAT_ID` in
`bridge.env`, then run `start-bridge.ps1` from that folder (or log in again).
The bridge runs as `javaw` with logs in `<data dir>\logs\bridge.log`; the tunnel uses
`%USERPROFILE%\.cloudflared\config.yml` and logs to `logs\cloudflared.log`.
`stop-bridge.ps1` stops both. After rebuilding, stop the bridge and run
`install-autostart.ps1` again to update the jar copy.

On Windows the toast buttons decide in the background: at startup the bridge
registers a `claude-bridge:` URL handler for the current user (HKCU, no admin) that
POSTs the decision to the local page, so no browser tab opens. Clicking the toast
body still opens the page. Set `bridge.desktop.protocol-buttons: false` to have the
buttons open the page instead.

Claude is asked (via `--append-system-prompt`, configurable as
`bridge.runner.summary-prompt`) to end with a `SUMMARY:` line. It is shown first in
Telegram and in a toast when the task finishes, and returned as `summary` by the API.

Claude runs with `--output-format stream-json --verbose`, so every message and tool
call is written to `<task id>.stdout.json` as it happens. The caller can ask what the
agent is doing with `GET /tasks/{id}/progress` (status, `decidedBy`, last activity and
the last 15 steps such as `→ Bash(mvn test)`), and after the run read the whole thing
as a readable log with `GET /tasks/{id}/log` (`<task id>.log.txt`). The log is also
attached to the Telegram result (`bridge.telegram.send-log: false` turns that off).

### Adding to a running task

Input is `--input-format stream-json` as well: the task is the first user message and
stdin stays open while the agent works. The caller can send
`POST /tasks/{id}/messages` with `{"text", "cursor", "sender"}`; the owner approves it
in Telegram or a toast like a task, and the approved text is written to the running
session, which picks it up between tool calls.

- The `cursor` comes from `GET /tasks/{id}/progress`. A message without it, or written
  more than 3 agent steps ago, is refused (409), so the caller has to look at what the
  agent is doing before adding to it. The approval request shows the agent's latest
  steps next to the text.
- The same text rules as for tasks (length, no invisible characters); the message goes
  through stdin as a JSON string, never on the command line.
- The bridge closes stdin as soon as a final answer (`result` event) appears that was
  written after the last message it sent, i.e. the agent has answered everything it
  was given. After that nothing more can be added: messages still waiting are cancelled
  (`UNDELIVERED`, their Telegram message is edited), and the caller should send a new
  task with mode `continue`. Undecided messages expire after 10 minutes.
- The cursor is a byte position in the agent's output, not a step number (progress
  reads only the tail of the stream). From it the bridge counts the steps made since
  and lists them in the approval request.
- Delivered messages appear in progress and in the log as `✉ Message from the caller`.

`bridge.runner.command` must therefore accept stream-json input; Claude Code does.

### Folders on request

`bridge.folders` in the configuration is the starting set. The remote side can see the
current set (`GET /folders`) and ask for changes (`POST /folders/requests`, kind `add`,
`change` or `remove`, always with a reason). The owner approves each request in Telegram
or a toast; it then applies at once and is kept in `bridge.folders-file` (default
`%LOCALAPPDATA%\claude-local-bridge\folders.json`), which overrides configured folders of
the same name. A task can also carry `extraTools`: shown in its approval request,
valid for that task (and messages added to it) only.

Checked before the owner is asked, and again before applying:
- tool rules are `Name` or `Name(prefix)`, no commas, quotes or `& | < > ^ % !`; a shell
  (`Bash`, `PowerShell`) only with a concrete command prefix, never bare or `(*)`;
- the path must exist and is resolved to its real location (symlinks, `..`, 8.3 short
  names), which is what the owner sees; drive roots, `Windows`, `Program Files`,
  `ProgramData`, the user profile itself and anything containing it, `AppData`, `.ssh`,
  `.claude`, `.aws` and similar, and the bridge's own folders are refused;
- a new folder must be one of `bridge.access.roots` (env `BRIDGE_ACCESS_ROOTS`,
  comma-separated, e.g. `E:\,D:\code`) or inside one; with no roots configured, requests
  for new folders are refused (changing or removing existing ones still works).
  `GET /folders` returns the roots too;
- tools that change files or run commands are marked ⚠️; rules that start an
  interpreter or build tool (`Bash(python:*)`, `Bash(node:*)`, `Bash(npm:*)`,
  `Bash(mvn:*)`, `Bash(powershell:*)`, ...) are marked ‼️ with a separate line saying
  that in practice they allow running any code; a folder whose own
  `.claude/settings.json` allows more is flagged, because Claude Code adds those rules.

Requests expire after 30 minutes; at most 5 wait at a time.

Expose port 8765 (API only) with a tunnel, e.g. a named Cloudflare Tunnel on your
domain. Do **not** expose 8766 (local approval page).

## The remote side

`skill/send-task-to-pc/` is a skill for Claude: how to write a task a human can
approve on a phone, and a stdlib-only Python client (`send`, `status`, `wait`).
The caller needs `BRIDGE_URL` and `BRIDGE_HMAC_SECRET` as environment variables,
and the bridge host must be allowed by its network policy.

## Status

30 unit/integration tests, plus an end-to-end run with a fake `claude` binary through
the HTTP API, the local approval page and the client. Verified on a real Windows 11
machine: signed request, Telegram approval with message edit, Windows toast approval,
real `claude -p` run and the result sent back. Not yet used from a cloud agent
through a tunnel.
Planned: a remote MCP endpoint so any Claude chat can submit tasks.

## Authorship

Idea, requirements and security design: Vladislav Iablotskii. Implementation written
with Claude Code.
