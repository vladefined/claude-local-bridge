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
                          claude -p in E:\project  ──▶ result to Telegram + GET /tasks/{id}
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
| The remote side picks from a menu | Folder is a name from config, never a path; allowed tools are per folder in config; unknown JSON fields are rejected; a resume session id must look like an id (it can't start with `-` and become a CLI flag) |
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
