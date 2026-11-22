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
    req = urllib.request.Request(base + path, data=body if method == "POST" else None, method=method,
                                 headers={"Content-Type": "application/json", "X-Bridge-Timestamp": ts,
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
        r = call("POST", "/tasks", json.dumps(payload, ensure_ascii=False).encode("utf-8"))
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
        for s in r.get("steps") or []:
            print(s)
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
