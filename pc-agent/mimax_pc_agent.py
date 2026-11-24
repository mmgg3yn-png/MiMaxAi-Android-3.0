#!/usr/bin/env python3
"""Mi_Max Ai PC Bridge — local network ADB/Fastboot helper.
Uses only Python standard library. Keep this service on a trusted private LAN.
"""
import argparse
import json
import os
import re
import secrets
import shlex
import shutil
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent / "workspace"
ROOT.mkdir(parents=True, exist_ok=True)
PARTITIONS = {"boot", "vendor_boot", "init_boot", "recovery", "vbmeta", "dtbo", "super"}
GETVARS = {"all", "product", "unlocked", "current-slot", "slot-count", "is-userspace", "secure", "version-bootloader"}

def safe_file(name):
    p = (ROOT / name).resolve()
    if ROOT.resolve() not in p.parents or not p.is_file():
        raise ValueError("File must exist inside the pc-agent/workspace folder.")
    return p

def one_device(tool):
    exe = shutil.which(tool)
    if not exe:
        raise RuntimeError(tool + " is not installed or not in PATH.")
    p = subprocess.run([exe, "devices"], capture_output=True, text=True, timeout=20)
    rows = [x for x in p.stdout.splitlines()[1:] if x.strip() and not x.strip().startswith("*")]
    devices = [x for x in rows if x.split() and x.split()[-1] in ("device", "fastboot")]
    if len(devices) != 1:
        raise RuntimeError("Connect exactly one device. Detected: " + str(len(devices)))
    return exe

def execute(command, confirm):
    try:
        args = shlex.split(command, posix=(os.name != "nt"))
    except Exception as e:
        raise ValueError("Command parse error: " + str(e))
    if not args:
        raise ValueError("Empty command.")
    tool = args[0].lower()
    exe = shutil.which(tool)
    timeout = 120

    if tool in {"python", "python3", "node", "npm", "java", "gradle"}:
        if not exe:
            raise RuntimeError(tool + " is not installed or not in PATH.")
        if args[1:] in (["--version"], ["-version"], ["-v"]):
            pass
        elif tool == "gradle" and args[1:] in (["--no-daemon", ":app:assembleDebug"], ["--no-daemon", "assembleDebug"]):
            if not (ROOT / "settings.gradle").is_file() or not (ROOT / "app").is_dir():
                raise ValueError("Extract an Android project into pc-agent/workspace first.")
            timeout = 900
        else:
            raise ValueError("Only version checks and the approved Gradle Debug build are allowed.")
    elif tool == "git":
        if not exe:
            raise RuntimeError("git is not installed or not in PATH.")
        if args[1:] not in (["--version"], ["status"], ["log", "-5"], ["diff", "--stat"]):
            raise ValueError("Git is read-only here: --version, status, log -5, diff --stat.")
    elif tool in ("ls", "dir", "pwd"):
        if args[1:]:
            raise ValueError("These directory commands show only the workspace root.")
        return {"ok": True, "exit_code": 0, "output": str(ROOT) if tool == "pwd" else "\n".join(sorted(os.listdir(ROOT)))}
    elif tool == "adb":
        if not exe:
            raise RuntimeError("adb is not installed or not in PATH.")
        if args[1:] in (["version"], ["devices"], ["devices", "-l"], ["get-state"]):
            pass
        elif len(args) in (3, 4) and args[1:3] == ["shell", "getprop"]:
            if len(args) == 4 and not re.fullmatch(r"[A-Za-z0-9._-]{1,100}", args[3]):
                raise ValueError("Invalid property name.")
        elif len(args) == 5 and args[1:4] == ["logcat", "-d", "-t"]:
            if not args[4].isdigit() or int(args[4]) > 3000:
                raise ValueError("logcat limit is 3000 lines.")
        elif len(args) == 3 and args[1] == "install" and args[2].lower().endswith(".apk"):
            args[2] = str(safe_file(args[2]))
        else:
            raise ValueError("ADB allowed: version, devices, get-state, shell getprop, logcat -d, install <APK in workspace>.")
    elif tool == "fastboot":
        if not exe:
            raise RuntimeError("fastboot is not installed or not in PATH.")
        if args[1:] == ["devices"]:
            pass
        elif len(args) == 3 and args[1] == "getvar" and args[2] in GETVARS:
            pass
        elif args[1:] == ["reboot"]:
            pass
        elif len(args) == 4 and args[1] == "flash":
            partition = args[2]
            if partition not in PARTITIONS:
                raise ValueError("Partition not in allowed list: " + ", ".join(sorted(PARTITIONS)))
            if confirm != "FLASH":
                raise ValueError("Flash blocked. Type FLASH in the confirmation field to continue.")
            args[3] = str(safe_file(args[3]))
            exe = one_device("fastboot")
        else:
            raise ValueError("Fastboot allowed: devices, getvar, reboot, guarded flash.")
    else:
        raise ValueError("Command not supported by the PC Bridge.")

    p = subprocess.run([exe] + args[1:], cwd=str(ROOT), capture_output=True, text=True, timeout=timeout, shell=False)
    output = (p.stdout or "") + (("\n" + p.stderr) if p.stderr else "")
    return {"ok": p.returncode == 0, "exit_code": p.returncode, "output": output[-20000:]}

class Handler(BaseHTTPRequestHandler):
    server_version = "MiMaxAiPCBridge/1.0"
    def send_json(self, code, obj):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def authorized(self):
        return secrets.compare_digest(self.headers.get("X-MiMax-Token", ""), self.server.token)
    def do_GET(self):
        if self.path != "/status":
            return self.send_json(404, {"ok": False, "error": "Not found"})
        if not self.authorized():
            return self.send_json(401, {"ok": False, "error": "Invalid token"})
        self.send_json(200, {
            "ok": True, "name": "Mi_Max Ai PC Bridge",
            "adb": bool(shutil.which("adb")), "fastboot": bool(shutil.which("fastboot")),
            "workspace": str(ROOT), "platform": os.name
        })
    def do_POST(self):
        if self.path != "/run":
            return self.send_json(404, {"ok": False, "error": "Not found"})
        if not self.authorized():
            return self.send_json(401, {"ok": False, "error": "Invalid token"})
        try:
            n = int(self.headers.get("Content-Length", "0"))
            if n < 1 or n > 100000:
                raise ValueError("Invalid request size.")
            data = json.loads(self.rfile.read(n).decode("utf-8"))
            result = execute(str(data.get("command", "")), str(data.get("confirm", "")))
            self.send_json(200, result)
        except Exception as e:
            self.send_json(400, {"ok": False, "error": str(e)})
    def log_message(self, fmt, *args):
        print("[MiMaxAi]", fmt % args)

def main():
    parser = argparse.ArgumentParser(description="Mi_Max Ai PC Bridge")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--token", default="")
    opt = parser.parse_args()
    token = opt.token or secrets.token_urlsafe(24)
    server = ThreadingHTTPServer((opt.host, opt.port), Handler)
    server.token = token
    print("\nMi_Max Ai PC Bridge is running")
    print("Address: http://<THIS-PC-LAN-IP>:" + str(opt.port))
    print("Token: " + token)
    print("Workspace: " + str(ROOT))
    print("ADB:", shutil.which("adb") or "NOT FOUND")
    print("Fastboot:", shutil.which("fastboot") or "NOT FOUND")
    print("Keep this terminal open. Stop with Ctrl+C.")
    server.serve_forever()

if __name__ == "__main__":
    main()
