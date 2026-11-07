# Claude Code Remote Control from and-code

How to drive a Claude Code session in this workspace from claude.ai/code or the
Claude mobile app when running inside and-code, where the `/remote-control`
slash command isn't available.
Date: 2026-11-05.

## TL;DR

```bash
sh /root/start-remote-control.sh            # start (defaults to /workspace/voice-input)
sh /root/start-remote-control.sh --status   # check status and print links
sh /root/start-remote-control.sh --stop     # stop
```

Or just ask Claude in and-code: *"run /root/start-remote-control.sh"*.

The script prints two links:

- `https://claude.ai/code/session_…`: the session it created. Open it and start typing.
- `https://claude.ai/code?environment=env_…`: the environment. Start more sessions here (up to 32).

You can also open the Code tab in the Claude mobile app. The session is named
`voice-input (AndCode)`.

## What it is (and isn't)

- `claude remote-control` is a separate **server process** that hosts new
  sessions on this machine. It does **not** attach to the and-code chat you're
  currently in. The remote sessions are new conversations. They share the files,
  git state and `CLAUDE.md`/`AGENTS.md` context, but not the chat history.
- Sessions run in `/workspace/voice-input` (spawn mode `same-dir`), on whatever
  branch is checked out. Remote sessions and and-code sessions edit the same
  working tree, so don't run both on the same files at once.
- It's a background process inside PRoot. If and-code or Android kills the
  environment, the server stops. Run the script again; it's idempotent.

## Obstacles found and how the script handles them

| Problem | Symptom | Fix |
|---|---|---|
| No slash command in and-code | `/remote-control` unavailable | Use the CLI subcommand `claude remote-control` |
| Folder not trusted | `Error: Workspace not trusted. Please run claude in … first` | Set `projects["<dir>"].hasTrustDialogAccepted = true` in `/root/.claude.json` |
| Confirmation needs a real TTY | Hangs on `Enable Remote Control? (y/n)`; `yes y \|` doesn't work | Run under `script -qfc …` (pseudo-terminal) and feed `y` after a delay |
| Spawn-mode prompt | Hangs on `Choose [1/2]` | Pass `--spawn=same-dir` (or `--spawn=worktree` for isolated git worktrees) |
| Process exits when stdin closes | Server dies right after starting | Keep stdin open with a trailing `sleep 999999` in the feeder |
| `pkill -f "remote-control"` kills the calling shell | Bash tool returns exit code 144 | Use the pattern `"[c]laude remote-control"` so it doesn't match itself |

## Manual equivalent

```bash
cd /workspace/voice-input
( nohup sh -c 'sleep 4; printf "y\n"; sleep 999999' \
  | script -qfc 'claude remote-control --spawn=same-dir --name "voice-input (AndCode)"' /tmp/rc.log \
  >/dev/null 2>&1 & )
sleep 30; sed 's/\x1b\[[0-9;?]*[a-zA-Z]//g' /tmp/rc.log | tr '\r' '\n' | grep -E 'Connected|claude.ai' | tail -3
```

The log is at `/tmp/rc.log`. It's a TTY transcript full of escape codes, so
strip them as shown above.

## Options

- Use a different project: `sh /root/start-remote-control.sh /workspace/other "other (AndCode)"`
- Use isolated worktrees: edit the script to pass `--spawn=worktree`.
- Set the permission mode for spawned sessions: add `--permission-mode acceptEdits`
  (or `bypassPermissions`, `plan`, …) to the `claude remote-control` command in the script.
- Reattach to the last session instead of creating a new one: `claude remote-control -c`
  (works within about 4 hours).
