# Graphical client startup recovery — 2026-11-02

## Result and limits

The unchanged `tellus-cube-net-unfold-20261031` Prism instance reached the
Minecraft 1.20.1 title screen on the current desktop session. The window used
the NVIDIA GeForce RTX 3090, rather than Mesa software rendering. Sodium,
Iris, Tellus, the DH fork, and ComplementaryReimagined r5.5.1 all remained
installed. `main-menu.png` is the captured game window;
`client-first-launch.log` records initialization and `mods.sha256` identifies
the mod jars used.

This recovers the ability to start the graphical test client. It does not
establish a fix for the older, direction-dependent missing-near-terrain bug.
No seam crossing or fog/shader/DH A/B acceptance is claimed by this test.

## Prior failure versus the current session

The November 1 evidence retained a busy Render thread in visible-window
`glfwCreateWindow`, including in a minimal window without Minecraft mods.
The current host boot began at 2026-11-02 09:45:28 Asia/Shanghai. Its GNOME
Shell is 50.1 and XWayland is 24.1.10, with the same NVIDIA driver 580.178.04.
The current visible OpenGL 3.2 core/forward-compatible GLFW smoke test
completed; the full modded game also advanced beyond window creation.

The prior `.mutter-Xwaylandauth.*` path is session-specific and has changed.
Refreshing this path is necessary when launching from a remote shell that has
no desktop environment, but the available evidence does **not** prove stale
authorization caused the prior native busy loop. The desktop session changed
between the failure and recovery, and the old failure has not been reproduced
in the current session. This is a verified recovery and a launch-environment
mitigation, not an identified or patched native GLFW/Mutter root cause.

`thread-first-launch.txt` samples the recovered Render thread in normal frame
rate limiting (`glfwWaitEventsTimeout`), after initialization, instead of the
old window-creation call. The first client was then stopped for a repeat
launch through the helper.

## Reusable launch entry

From the repository root:

```sh
python3 tools/launch_graphical_client.py tellus-cube-net-unfold-20261031 --check
python3 tools/launch_graphical_client.py tellus-cube-net-unfold-20261031
```

The first command checks desktop access without starting the game. The second
starts the instance with Prism's configured account. A graphical desktop must
already be logged in, and `xdpyinfo` must be installed (`x11-utils` on Ubuntu).

For an explicit offline test of a local offline-mode server:

```sh
python3 tools/launch_graphical_client.py tellus-cube-net-unfold-20261031 \
  --offline TellusProbe --server 127.0.0.1:25582
```

The helper does not start the server. `--offline` is opt-in and does not alter
Prism's stored accounts. Specify `--launcher /absolute/path/to/Prism.AppImage`
or `PRISM_LAUNCHER` if Prism is not installed on PATH or at the tested
`~/Downloads/PrismLauncher-Linux-x86_64.AppImage` location.

The helper reads the current user's live XWayland process command line to
obtain its display and Xauthority path, checks access with a five-second bound,
unsets inherited `WAYLAND_DISPLAY` for the bundled GLFW path, and selects Qt's
X11 backend for Prism. It never changes Minecraft jars, mod configs, drivers,
or session services. With several possible desktops, choose explicitly:

```sh
python3 tools/launch_graphical_client.py INSTANCE --display :0 \
  --xauthority /path/to/the/current/session/authority --check
```

The access check does not create a visible GLFW window, so it cannot certify
that a future session will be free of the old native stall. If that stall
returns after access succeeds, capture the Render thread and a visible-window
control in that same session before changing render mods. Restarting a desktop
session would disrupt other applications; this helper never does that.

The old native Wayland run aborted at `glfwSetWindowIcon`; [GLFW 3.3's window
reference](https://www.glfw.org/docs/3.3/group__window.html) documents the
unsupported Wayland icon operation. The tested route uses XWayland and keeps
Minecraft's icon handling intact.

## Validation

- The recovered first launch initialized the RTX OpenGL context at 10:20:50
  and completed resource atlas creation at 10:20:54 Asia/Shanghai.
- The reusable helper successfully discovers the live session from the remote
  shell, where `DISPLAY` and `XAUTHORITY` were initially absent.
- A deliberately stale inherited authority file is replaced with the live
  server's file, and inherited `WAYLAND_DISPLAY=wayland-0` is removed.
- An inaccessible display is rejected without starting Prism.
- `--check` does not start a client or modify instance files.
- The repeat launch plan is recorded in `helper-launch.json`, with launcher
  output in `prism-helper-launch.log`.
- The helper-launched client again reached the title screen: RTX OpenGL
  initialized at 10:28:16 and resource atlases completed at 10:28:20
  Asia/Shanghai. `main-menu-helper.png` and `client-helper-launch.log` retain
  this second result. All mod-jar hashes still match the first launch.

Both test clients and their launchers were stopped after evidence capture; no
server was started. Synthetic window-close input did not close the clients,
so test processes were stopped explicitly. Interactive input and world entry
were not accepted by this startup-only check.

Offline authentication 401/profile-key warnings and the existing Iris/DH
warnings are retained in the logs. They did not prevent the recovered title
screen from rendering; they are not silently classified as startup failures.
