# Setting up the EULA sync check on a new machine (macOS / Windows)

This guide configures the local `pre-commit` hook in a **`prismtabs-website`**
checkout. It lives in this repository because the hook needs a
checkout of `docs/EULA.md` from here to compare against — the steps themselves
are all run in the `prismtabs-website` clone.

`eula.html` in `prismtabs-website` is the canonical Agreement; `docs/EULA.md`
here is a mirror kept in lockstep with it. Two safety nets do that:

1. **CI** — `.github/workflows/eula-sync.yml` in `prismtabs-website` and its
   counterpart in this repository. This works on every machine because it runs on
   GitHub, not locally. **Nothing in this guide is needed for CI to keep
   working.**
2. **A local `pre-commit` hook** — `tools/git-hooks/pre-commit` in
   `prismtabs-website`, which runs `tools/check-eula-sync.py` before each commit
   and prints a reminder (it does not block) when `eula.html` and this mirror
   disagree.

The local hook is **per-clone opt-in** and has environmental requirements. This
guide is the checklist for enabling it on a macOS or Windows checkout of
`prismtabs-website`. Hand this file to Claude Code on that machine and let it
work through the steps.

---

## What "done" looks like

All four of these are true in the `prismtabs-website` checkout:

- `python3 --version` prints a Python 3.x version.
- A checkout of this repository exists at `../prismtabs` (sibling of
  `prismtabs-website`), **or** `PRISMTABS_EULA` points at its `docs/EULA.md`.
- `tools/check-eula-sync.py` prints `EULA in sync: EULA.md matches …`.
- `git config --get core.hooksPath` prints `tools/git-hooks`.

---

## Expected directory layout

The check defaults to `../prismtabs/docs/EULA.md`, so lay the two repos out as
siblings:

```
dev/
├── prismtabs/            # this repository (mirror: docs/EULA.md)
└── prismtabs-website/    # canonical Agreement: eula.html
```

If you keep them somewhere else, skip the sibling clone below and use the
`PRISMTABS_EULA` environment variable instead (see "Alternative" at the end).

---

## macOS

### 1. Prerequisites

- **Git** — `git --version`. If missing, `xcode-select --install`.
- **Python 3** — `python3 --version`. If missing, either:
  - `xcode-select --install` (ships a `python3`), or
  - `brew install python` (Homebrew).
- **`gh`** (optional, only if cloning via GitHub CLI) — `brew install gh`.

### 2. Clone both repositories as siblings

```bash
mkdir -p ~/dev && cd ~/dev
git clone git@github.com:prismtabs/prismtabs-website.git
git clone git@github.com:prismtabs/prismtabs.git
```

If they are already cloned, just `git pull` in each and make sure they sit next
to each other.

### 3. Verify the check runs

```bash
cd ~/dev/prismtabs-website
tools/check-eula-sync.py
```

Expected: `EULA in sync: EULA.md matches /Users/…/dev/prismtabs-website/eula.html`.

- `error: EULA mirror not found …` → the plugin repo is not at `../prismtabs`.
  Fix the layout or use `PRISMTABS_EULA`.
- `command not found` / permission denied → run it explicitly:
  `python3 tools/check-eula-sync.py`, and check the file is executable
  (`chmod +x tools/check-eula-sync.py`).

### 4. Enable the hook

```bash
git config core.hooksPath tools/git-hooks
```

This is a local setting — it lives in `.git/config` and is **not** pushed, which
is why every clone has to run it once.

### 5. Prove it works

```bash
tools/check-eula-sync.py && echo "check OK"
```

Expected: the `EULA in sync …` message, then `check OK`. The hook wraps this same
check, so from here on it runs automatically on `git commit` — silently when the
mirror matches, printing a sentence-level diff and a reminder (never blocking)
when it has drifted.

---

## Windows

Commit from **Git Bash** (bundled with Git for Windows). The hook is a `/bin/sh`
script; `cmd.exe` and some GUI clients cannot run it. Git itself runs
`core.hooksPath` scripts through its bundled `sh` regardless of the file's
executable bit, so `core.filemode` is not a concern here.

### 1. Prerequisites

- **Git for Windows** — <https://git-scm.com/download/win>. Gives you `git` and
  Git Bash. During install, "Checkout as-is, commit Unix-style line endings"
  (`core.autocrlf=input`) is a safe choice; the hook scripts must keep `LF`
  endings to run.
- **Python 3 exposing the `python3` command.** The hook's script starts with
  `#!/usr/bin/env python3`, so a bare `python` is not enough. Check in Git Bash:

  ```bash
  python3 --version
  ```

  If that fails, use one of:
  - **Microsoft Store Python 3** (simplest) — it installs both `python3` and
    `python` on `PATH`.
  - **python.org installer** — tick "Add python.exe to PATH", then in Git Bash
    add a `python3` alias that persists:

    ```bash
    echo "alias python3=python" >> ~/.bashrc
    ```

    An alias is enough for running the check by hand and for `git commit` from
    Git Bash (the hook runs under an interactive-ish shell). If a commit from the
    hook still cannot find `python3`, create a real shim instead — copy
    `python.exe` to `python3.exe` in the same folder, or add a tiny
    `python3` wrapper script earlier on `PATH`.

- **`gh`** (optional) — <https://cli.github.com/>.

### 2. Clone both repositories as siblings

In Git Bash:

```bash
mkdir -p ~/dev && cd ~/dev
git clone git@github.com:prismtabs/prismtabs-website.git
git clone git@github.com:prismtabs/prismtabs.git
```

Already cloned? `git pull` each and make sure they are siblings.

### 3. Verify the check runs

```bash
cd ~/dev/prismtabs-website
python3 tools/check-eula-sync.py
```

Expected: `EULA in sync: EULA.md matches …/dev/prismtabs-website/eula.html`.

Same failure modes as macOS step 3.

### 4. Enable the hook

```bash
git config core.hooksPath tools/git-hooks
```

Local to this clone; not pushed.

### 5. Prove it works

```bash
python3 tools/check-eula-sync.py && echo "check OK"
```

Expected: the `EULA in sync …` message, then `check OK`. Then do a real test:
make a trivial change, `git commit` from Git Bash, and confirm the check line
appears in the output. Always commit from Git Bash on this machine.

---

## Alternative: repos not laid out as siblings

If this repository lives somewhere other than `../prismtabs` relative to
`prismtabs-website`, point the check at its `docs/EULA.md` explicitly.

**Per-command:**

```bash
tools/check-eula-sync.py --mirror /path/to/prismtabs/docs/EULA.md
```

**Persistently (so the hook picks it up too):**

- macOS / Git Bash — add to `~/.zshrc` / `~/.bashrc`:

  ```bash
  export PRISMTABS_EULA="/path/to/prismtabs/docs/EULA.md"
  ```

- Windows, for commits made outside Git Bash — set a **user** environment
  variable `PRISMTABS_EULA` to the same path (System → Environment Variables),
  then restart the client.

---

## How the hook behaves once installed

- Commit **stages `eula.html`** and it has drifted from the mirror → commit
  proceeds, with a sentence-level diff and a reminder to update `docs/EULA.md`
  in this repository.
- Commit stages `eula.html` and it matches the mirror → commit proceeds
  silently.
- Commit **does not touch `eula.html`** but the two disagree → commit proceeds,
  with a warning.
- No mirror checkout available → the check is skipped silently (a one-line note
  only if `eula.html` is staged).
- `git commit --no-verify` bypasses the hook entirely — CI still catches drift.

---

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `error: EULA mirror not found at …` | Plugin repo not at `../prismtabs` | Clone it as a sibling, or set `PRISMTABS_EULA` |
| `env: python3: No such file or directory` | No `python3` on `PATH` (common on Windows) | Install Store Python 3, or add a `python3` shim/alias |
| Hook never runs on `git commit` | `core.hooksPath` not set in this clone | `git config core.hooksPath tools/git-hooks` |
| Hook not run by a GUI client on Windows | Client cannot execute `/bin/sh` scripts | Commit from Git Bash |
| Hook scripts fail with `\r` errors | Repo checked out with CRLF endings | `core.autocrlf=input`, re-checkout `tools/` |
