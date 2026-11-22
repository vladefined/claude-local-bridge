#!/usr/bin/env python3
"""Client for claude-local-bridge. Standard library only.

Signature: hex(HMAC-SHA256(secret, f"{ts}\n{nonce}\n{METHOD}\n{path}\n" + body)).
"""
import argparse
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid


def _env(name):
    v = os.environ.get(name)
    if not v:
        sys.exit(f"{name} is not set")
    return v


def call(method, path, body=b""):
    base = _env("BRIDGE_URL").rstrip("/")
    secret = _env("BRIDGE_HMAC_SECRET").encode()
    ts = str(int(time.time()))
    nonce = uuid.uuid4().hex
    msg = f"{ts}\n{nonce}\n{method}\n{path}\n".encode() + body
    sig = hmac.new(secret, msg, hashlib.sha256).hexdigest()
    # Cloudflare refuses the default Python-urllib User-Agent (error 1010), so send our own.
    req = urllib.request.Request(base + path, data=body if method == "POST" else None, method=method,
                                 headers={"Content-Type": "application/json", "User-Agent": "claude-local-bridge-client/1.0",
                                          "X-Bridge-Timestamp": ts,
                                          "X-Bridge-Nonce": nonce, "X-Bridge-Signature": sig})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            data = r.read()
            if r.headers.get_content_type() == "application/json":
                return json.loads(data)
            return data.decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        sys.exit(f"HTTP {e.code}: {e.read().decode(errors='replace')}")


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("send")
    s.add_argument("--folder", required=True)
    s.add_argument("--mode", choices=["new", "continue", "resume"], default="continue")
    s.add_argument("--session")
    s.add_argument("--sender", default="cloud Claude")
    s.add_argument("--text-file", help="file with the task; stdin if omitted")
    s.add_argument("--extra-tools", nargs="+", default=[], metavar="TOOL",
                   help='tools for this task only, approved with it, e.g. "Bash(npm test:*)"')
    sub.add_parser("folders", help="list the folders tasks may use and their tools")
    for kind in ("add", "change", "remove"):
        f = sub.add_parser("request-" + kind, help=f"ask the owner to {kind} a folder")
        f.add_argument("--name", required=True)
        f.add_argument("--reason", required=True, help="why; the owner reads this")
        f.add_argument("--sender", default="cloud Claude")
        if kind == "add":
            f.add_argument("--path", required=True, help="absolute path on the PC")
            f.add_argument("--tools", nargs="+", required=True, metavar="TOOL")
            f.add_argument("--timeout-minutes", type=int)
        if kind == "change":
            f.add_argument("--add-tools", nargs="+", default=[], metavar="TOOL")
            f.add_argument("--remove-tools", nargs="+", default=[], metavar="TOOL")
            f.add_argument("--timeout-minutes", type=int)
    r_ = sub.add_parser("request-status")
    r_.add_argument("id")
    m = sub.add_parser("add", help="add a message to a RUNNING task (read progress first)")
    m.add_argument("id")
    m.add_argument("--cursor", type=int, required=True,
                   help="the cursor printed by `progress`; refused if the agent has moved on since")
    m.add_argument("--sender", default="cloud Claude")
    m.add_argument("--text-file", help="file with the message; stdin if omitted")
    for name in ("status", "wait", "output", "progress", "log"):
        q = sub.add_parser(name)
        q.add_argument("id")
        if name == "wait":
            q.add_argument("--max-minutes", type=float, default=40)
    a = p.parse_args()

    if a.cmd == "send":
        text = open(a.text_file, encoding="utf-8").read() if a.text_file else sys.stdin.read()
        payload = {"folder": a.folder, "text": text.strip(), "mode": a.mode, "sender": a.sender}
        if a.session:
            payload["sessionId"] = a.session
        if a.extra_tools:
            payload["extraTools"] = a.extra_tools
        r = call("POST", "/tasks", json.dumps(payload, ensure_ascii=False).encode("utf-8"))
        print(json.dumps(r, ensure_ascii=False))
    elif a.cmd == "folders":
        for f in call("GET", "/folders")["folders"]:
            limit = f" ({f['timeoutMinutes']} min)" if f.get("timeoutMinutes") else ""
            print(f"{f['name']}: {f['path']}{limit}\n  tools: {', '.join(f['allowedTools']) or '-'}")
    elif a.cmd.startswith("request-") and a.cmd != "request-status":
        kind = a.cmd.split("-", 1)[1]
        payload = {"kind": kind, "name": a.name, "reason": a.reason, "sender": a.sender}
        if kind == "add":
            payload.update(path=a.path, addTools=a.tools)
        if kind == "change":
            payload.update(addTools=a.add_tools, removeTools=a.remove_tools)
        if kind != "remove" and a.timeout_minutes:
            payload["timeoutMinutes"] = a.timeout_minutes
        r = call("POST", "/folders/requests", json.dumps(payload, ensure_ascii=False).encode("utf-8"))
        print(json.dumps(r, ensure_ascii=False))
    elif a.cmd == "request-status":
        print(json.dumps(call("GET", f"/folders/requests/{a.id}"), ensure_ascii=False, indent=2))
    elif a.cmd == "add":
        text = open(a.text_file, encoding="utf-8").read() if a.text_file else sys.stdin.read()
        payload = {"text": text.strip(), "cursor": a.cursor, "sender": a.sender}
        r = call("POST", f"/tasks/{a.id}/messages", json.dumps(payload, ensure_ascii=False).encode("utf-8"))
        print(json.dumps(r, ensure_ascii=False))
    elif a.cmd == "output":
        sys.stdout.write(call("GET", f"/tasks/{a.id}/output"))
    elif a.cmd == "log":
        sys.stdout.write(call("GET", f"/tasks/{a.id}/log"))
    elif a.cmd == "progress":
        r = call("GET", f"/tasks/{a.id}/progress")
        print(f"status: {r['status']}" + (f" (decided via {r['decidedBy']})" if r.get("decidedBy") else ""))
        if r.get("lastActivity"):
            print(f"last activity: {r['lastActivity']}")
        if r.get("status") == "RUNNING":
            print(f"cursor: {r.get('cursor')}")
        for s in r.get("steps") or []:
            print(s)
        for msg in r.get("messages") or []:
            print(f"message {msg['id']}: {msg['status']}")
    elif a.cmd == "status":
        print(json.dumps(call("GET", f"/tasks/{a.id}"), ensure_ascii=False, indent=2))
    else:
        deadline, delay = time.time() + a.max_minutes * 60, 10
        while True:
            r = call("GET", f"/tasks/{a.id}")
            if r["status"] in ("DONE", "FAILED", "REJECTED", "EXPIRED") or time.time() > deadline:
                print(json.dumps(r, ensure_ascii=False, indent=2))
                return
            time.sleep(delay)
            delay = min(delay * 1.5, 120)


if __name__ == "__main__":
    main()
