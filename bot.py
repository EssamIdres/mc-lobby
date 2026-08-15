import json
import os
import subprocess
import sys
import time
import urllib.request
import urllib.parse
import urllib.error
from datetime import datetime, timezone

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(BASE_DIR, "config.json")

CFG = {
    "token": os.environ.get("BOT_TOKEN"),
    "chat_id": os.environ.get("BOT_CHAT_ID"),
    "pat": os.environ.get("BOT_PAT"),
    "owner": os.environ.get("BOT_OWNER", "EssamIdres"),
    "repos": ["mc-lobby", "mc-survival", "mc-minigames", "mc-creative"],
    "primary": "mc-lobby",
    "interval_hours": int(os.environ.get("BOT_INTERVAL_HOURS", "6")),
    "run_minutes": int(os.environ.get("BOT_RUN_MINUTES", "355")),
}

if not CFG["token"] or not CFG["chat_id"] or not CFG["pat"]:
    # Fallback: local config.json (used when running on this PC)
    with open(CONFIG_PATH, encoding="utf-8") as f:
        local = json.load(f)
    for k in ("token", "chat_id", "pat"):
        if not CFG[k]:
            CFG[k] = local[k]

TOKEN = CFG["token"]
CHAT_ID = CFG["chat_id"]
PAT = CFG["pat"]
OWNER = CFG["owner"]
REPOS = CFG["repos"]
PRIMARY = CFG["primary"]
INTERVAL = CFG["interval_hours"] * 3600
RUN_SECONDS = CFG["run_minutes"] * 60

ADDRESS = os.environ.get("BOT_ADDRESS", "mauritania-catching.tun.ply.gg:25565")
API = f"https://api.telegram.org/bot{TOKEN}"


def tg(method, **params):
    url = f"{API}/{method}"
    data = urllib.parse.urlencode(params).encode()
    req = urllib.request.Request(url, data=data)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.loads(r.read().decode())
    except Exception as e:
        print("tg error:", e, file=sys.stderr)
        return None


def send(text):
    for chunk in (text[i:i + 4000] for i in range(0, len(text), 4000)):
        tg("sendMessage", chat_id=CHAT_ID, text=chunk)


def gh(method, path, body=None):
    url = f"https://api.github.com{path}"
    headers = {
        "Authorization": f"token {PAT}",
        "Accept": "application/vnd.github+json",
    }
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return json.loads(raw)
        except Exception:
            return {"_error": raw, "_status": e.code}
    except Exception as e:
        return {"_error": str(e)}


def latest_run(repo):
    data = gh("GET", f"/repos/{OWNER}/{repo}/actions/runs?per_page=5")
    if "_error" in data:
        return None
    for r in data.get("workflow_runs", []):
        if r.get("name") == "mc-server" or r.get("event") == "workflow_dispatch":
            return r
    return None


def cancel_run(repo, run_id):
    return gh("POST", f"/repos/{OWNER}/{repo}/actions/runs/{run_id}/cancel")


def dispatch(repo):
    return gh(
        "POST",
        f"/repos/{OWNER}/{repo}/actions/workflows/server.yml/dispatches",
        {"ref": "main"},
    )


def gh_put(path, body):
    url = f"https://api.github.com{path}"
    headers = {
        "Authorization": f"token {PAT}",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json",
    }
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers=headers, method="PUT")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        return {"_error": e.read().decode(), "_status": e.code}
    except Exception as e:
        return {"_error": str(e)}


def gh_delete(path):
    url = f"https://api.github.com{path}"
    headers = {
        "Authorization": f"token {PAT}",
        "Accept": "application/vnd.github+json",
    }
    req = urllib.request.Request(url, headers=headers, method="DELETE")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return {}
    except Exception as e:
        return {"_error": str(e)}


def cmd_console(args):
    repo = PRIMARY
    target = "lobby"
    if args and args[0].lower() in REPOS:
        target = args[0].lower()
        args = args[1:]
    if not args:
        send("Usage: /console [server] <command>  (e.g. /console survival time set day)")
        return
    cmd = " ".join(args)
    if len(cmd) > 400:
        send("Command too long (max 400 chars).")
        return
    repo = target
    path = f"/repos/{OWNER}/{repo}/contents/console-cmd.txt"
    existing = gh("GET", path)
    sha = existing.get("sha")
    body = {"message": "console cmd", "content": b64(cmd)}
    if sha:
        body["sha"] = sha
    res = gh_put(path, body)
    if "_error" in res:
        send(f"Could not send command: {res['_error'][:200]}")
    else:
        send(f"Sent to {repo}: {cmd}")


def b64(text):
    return __import__("base64").b64encode(text.encode()).decode()


def status_line(repo):
    r = latest_run(repo)
    if not r:
        return f"{repo}: no runs"
    status = r.get("status")
    conclusion = r.get("conclusion")
    created = r.get("created_at", "")
    if status == "in_progress":
        try:
            start = datetime.fromisoformat(created.replace("Z", "+00:00"))
            elapsed = (datetime.now(timezone.utc) - start).total_seconds()
            left = max(0, RUN_SECONDS - elapsed)
            h, m = int(left // 3600), int((left % 3600) // 60)
            return f"{repo}: RUNNING, {h}h {m}m left (#{r.get('run_number')})"
        except Exception:
            return f"{repo}: RUNNING (#{r.get('run_number')})"
    return f"{repo}: {status}/{conclusion} (#{r.get('run_number')})"


def cmd_start(args):
    repo = args[0].strip().lower() if args else PRIMARY
    if repo not in REPOS:
        send(f"Unknown server '{repo}'. Options: {', '.join(REPOS)}")
        return
    r = latest_run(repo)
    if r and r.get("status") == "in_progress":
        send(f"{repo} is already running (#{r.get('run_number')}).")
        return
    res = dispatch(repo)
    if res.get("message") == "Workflow dispatched!":
        send(f"Started {repo}. It takes ~3-4 min to be joinable.")
    else:
        send(f"Dispatch failed: {json.dumps(res)}")


def cmd_stop(args):
    repo = args[0].strip().lower() if args else PRIMARY
    if repo not in REPOS:
        send(f"Unknown server '{repo}'.")
        return
    r = latest_run(repo)
    if r and r.get("status") == "in_progress":
        cancel_run(repo, r["id"])
        send(f"Cancelling {repo} run #{r.get('run_number')}...")
    else:
        send(f"{repo} is not running.")


def cmd_status(args):
    lines = [status_line(repo) for repo in REPOS]
    send("\n".join(lines))


def cmd_address(args):
    send(f"Join address (Velocity proxy):\n{ADDRESS}\n\nServers: lobby, survival, minigames, creative\nIn-game: /server <name>")


def cmd_help(args):
    send(
        "Commands:\n"
        "/start [server]  - start a server (default lobby)\n"
        "/stop [server]   - stop a server\n"
        "/status          - status + time left of all servers\n"
        "/console [srv] <cmd> - send a command to a server console (default lobby)\n"
        "/address         - join address + server list\n"
        "/help            - this message"
    )


COMMANDS = {
    "start": cmd_start,
    "stop": cmd_stop,
    "status": cmd_status,
    "console": cmd_console,
    "address": cmd_address,
    "help": cmd_help,
}


def handle_update(update):
    msg = update.get("message") or update.get("channel_post")
    if not msg:
        return
    chat = msg.get("chat", {}).get("id")
    if str(chat) != str(CHAT_ID):
        return
    text = msg.get("text", "").strip()
    if not text or not text.startswith("/"):
        return
    parts = text[1:].split()
    name = parts[0].split("@")[0].lower()
    args = parts[1:]
    handler = COMMANDS.get(name)
    if handler:
        handler(args)


def scheduler_once():
    # 1) Restart any server that is about to hit its 6h cap (keeps it 24/7)
    for repo in REPOS:
        r = latest_run(repo)
        if r and r.get("status") == "in_progress":
            start = datetime.fromisoformat(
                r["created_at"].replace("Z", "+00:00")
            ).replace(tzinfo=timezone.utc)
            age = (datetime.now(timezone.utc) - start).total_seconds()
            if age >= RUN_SECONDS - 120:
                cancel_run(repo, r["id"])
                time.sleep(2)
                dispatch(repo)
                send(f"Run hit the 6h cap — restarting {repo}.")
                return True

    # 2) Every interval, make sure the primary server is up (auto-start if stopped)
    global _last_scheduled
    now = time.time()
    if now - _last_scheduled >= INTERVAL:
        _last_scheduled = now
        r = latest_run(PRIMARY)
        if not (r and r.get("status") == "in_progress"):
            dispatch(PRIMARY)
            send(f"Auto-start every {CFG['interval_hours']}h: starting {PRIMARY}.")
            return True

    return False


_last_scheduled = time.time()


def main():
    send("MC Bot online. Servers: " + ", ".join(REPOS) + ". Auto-restart every 6h.")
    offset = 0
    while True:
        try:
            if scheduler_once():
                time.sleep(30)
                continue
            url = f"{API}/getUpdates?timeout=25&offset={offset + 1}"
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req, timeout=40) as r:
                data = json.loads(r.read().decode())
            for upd in data.get("result", []):
                offset = max(offset, upd["update_id"])
                handle_update(upd)
        except Exception as e:
            print("loop error:", e, file=sys.stderr)
            time.sleep(5)


if __name__ == "__main__":
    main()