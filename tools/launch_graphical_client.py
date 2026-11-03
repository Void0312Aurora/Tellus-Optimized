#!/usr/bin/env python3
"""Launch a Prism instance on the current X11/XWayland desktop, including from SSH.

The current validated route uses the live XWayland server's display and
authority instead of retaining session-specific paths from a previous login.
The older Wayland icon failure is recorded in the validation evidence. No
instance is edited.
"""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


def local_xwayland_servers():
    """Read only the caller's XWayland command lines, never account files."""
    servers = []
    for entry in Path("/proc").iterdir():
        if not entry.name.isdigit():
            continue
        try:
            if entry.stat().st_uid != os.getuid():
                continue
            if (entry / "comm").read_text().strip() != "Xwayland":
                continue
            argv = (entry / "cmdline").read_bytes().decode().rstrip("\0").split("\0")
            display = next((arg for arg in argv[1:] if arg.startswith(":") and arg[1:].replace(".", "").isdigit()), None)
            authority = argv[argv.index("-auth") + 1] if "-auth" in argv else None
            if display and authority and os.access(authority, os.R_OK):
                servers.append((display, authority))
        except (OSError, UnicodeError, IndexError):
            # A desktop process may exit while /proc is being read.
            continue
    return sorted(set(servers))


def desktop_environment(display=None, authority=None):
    env = os.environ.copy()
    wanted = display or env.get("DISPLAY")
    servers = local_xwayland_servers()
    matching = [server for server in servers if server[0] == wanted] if wanted else servers
    if len(matching) == 1:
        env["DISPLAY"], env["XAUTHORITY"] = matching[0]
    elif wanted:
        env["DISPLAY"] = wanted
    else:
        raise RuntimeError("Cannot select a desktop display; start a graphical session or supply --display and --xauthority.")
    if authority:
        env["XAUTHORITY"] = str(Path(authority).expanduser())
    # GLFW 3.3's Linux natives select their Wayland path when this is present.
    # Qt's backend is independent of GLFW, so select X11 for Prism as well.
    env.pop("WAYLAND_DISPLAY", None)
    env["QT_QPA_PLATFORM"] = "xcb"
    return env


def check_display(env):
    query = shutil.which("xdpyinfo")
    if not query:
        raise RuntimeError("xdpyinfo is required to check desktop access (Ubuntu package: x11-utils).")
    try:
        result = subprocess.run([query], env=env, capture_output=True, timeout=5)
    except subprocess.TimeoutExpired as error:
        raise RuntimeError("The desktop X server did not answer within 5 seconds; no game was started.") from error
    if result.returncode:
        raise RuntimeError("Cannot access the selected X display. Check the current desktop session and Xauthority; no game was started.")


def find_launcher(explicit):
    candidate = explicit or os.environ.get("PRISM_LAUNCHER") or shutil.which("prismlauncher")
    if candidate:
        path = Path(candidate).expanduser().resolve()
    else:
        path = Path.home() / "Downloads/PrismLauncher-Linux-x86_64.AppImage"
    if not path.is_file() or not os.access(path, os.X_OK):
        raise RuntimeError("Prism Launcher was not found; supply --launcher or PRISM_LAUNCHER.")
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("instance", help="Existing Prism instance ID, e.g. tellus-cube-net-unfold-20261031")
    parser.add_argument("--launcher", help="Prism executable or AppImage path")
    parser.add_argument("--display", help="Override the desktop display selection")
    parser.add_argument("--xauthority", help="Override the Xauthority file selection")
    parser.add_argument("--server", help="Join this server at startup (host:port)")
    parser.add_argument("--offline", metavar="PLAYER", help="Explicit offline test player; default uses Prism's configured account")
    parser.add_argument("--check", action="store_true", help="Check access and print the launch plan without starting Prism")
    args = parser.parse_args()
    try:
        launcher = find_launcher(args.launcher)
        env = desktop_environment(args.display, args.xauthority)
        check_display(env)
        command = [str(launcher), "--launch", args.instance]
        if args.server:
            command.extend(["--server", args.server])
        if args.offline:
            command.extend(["--offline", args.offline])
        # These fields contain neither account credentials nor Xauthority cookies.
        print(json.dumps({
            "command": command,
            "DISPLAY": env["DISPLAY"],
            "XAUTHORITY": env.get("XAUTHORITY"),
            "WAYLAND_DISPLAY": None,
            "QT_QPA_PLATFORM": env["QT_QPA_PLATFORM"],
        }, indent=2), flush=True)
        if args.check:
            return 0
        os.execve(str(launcher), command, env)
    except (OSError, RuntimeError) as error:
        print(f"Launch failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
