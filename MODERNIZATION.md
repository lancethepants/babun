# Babun x86_64 Modernization Notes

Reference for all changes made forking babun to build against modern (3.x+) 64-bit Cygwin on Windows 11. Original babun was abandoned in 2019 and built for 32-bit Cygwin via Jenkins CI.

## Quick build instructions

On a Windows host (11 used for testing):

```powershell
# install JDK 17 + Groovy 4.x via Scoop (recommended)
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
irm get.scoop.sh | iex
scoop bucket add java
scoop install temurin17-jdk groovy
```

Then from a fresh PowerShell:

```powershell
git clone https://github.com/lancethepants/babun.git
cd babun
groovy build.groovy clean
groovy build.groovy package
```

Output: `target\babun-dist\babun-1.3.0-dist.zip`.

**You must `clean` between builds** — Stage 3 moves the cygwin tree out of Stage 2's location, so an aborted Stage 3 can't be retried without a clean.

## Dev workflow

Stage 3 (`babun-core`) copies the babun source tree from your local working directory into the cygwin sysroot (instead of `git clone`-ing from GitHub like the original did). So local edits flow into the build *without* needing to commit + push.

The one exception: when testing on a different machine (your VM vs. the dev machine where Claude edits files), you DO still need to commit + push from the dev machine and pull on the VM. But within a single host, edit-build-edit cycles are immediate.

---

## Architecture-level changes

### x86_64 conversion (architectural rename)

- `babun-packages/conf/cygwin.x86.packages` → renamed to `cygwin.x86_64.packages`
- `babun-packages/packages.groovy:13-14` — `"x86"` → `"x86_64"` (two literals)
- `build.groovy:89` — package list path updated
- `babun-cygwin/cygwin.groovy:53,57` — `setup-x86.exe` → `setup-x86_64.exe`, `http://` → `https://`
- `babun-core/plugins/core/src/babun.rc:7` — `CYGWIN_VERSION=x86` → `x86_64`
- `babun-dist/start/update.bat` — four `setup-x86.exe` → `setup-x86_64.exe` replacements

### Build script: groovy invocation

- `build.groovy` — `"groovy.bat"` (four sub-script calls) → `["cmd", "/c", "groovy", ...]`
  - Scoop ships `groovy.cmd`, not `groovy.bat`. Java's ProcessBuilder calls Windows `CreateProcess`, which only auto-appends `.exe`, not `.cmd`/`.bat`. Routing through `cmd /c` lets PATHEXT resolution find the shim.

### `babun-packages/packages.groovy` — wget replaced with Java URL fetching

The original used a bundled 2014 `wget.exe` with `--cut-dirs=3` to walk Cygwin mirrors. That approach was brittle (cut-dirs value depends on mirror URL depth) and the bundled wget likely lacked modern TLS. Now:

- `downloadSetupIni` uses `new URL(...).withInputStream { ... }` to stream `setup.ini` directly to `<outputFolder>/x86_64/setup.ini`.
- `downloadPackage` parses each package's `install:` path from setup.ini (already starts with `x86_64/release/...`) and saves it at that exact relative path. So the resulting `target/babun-packages/` mirrors a canonical Cygwin repo layout.
- `parsePackageRequires` now reads **`depends2:`** (modern Cygwin setup.ini, comma-separated, with optional `(>=version)`) AND `requires:` (legacy, space-separated). Required because `vim-minimal` and others only declare deps in `depends2:`.
- `buildPackageDependencyTree` now silently returns when a transitive dep isn't in setup.ini — needed for virtual deps like `_windows`, `_python` that aren't real downloadable packages. Top-level missing packages still get caught.
- Added `findBasePackages(setupIni)` — automatically includes every package with `category: Base` so setup.exe has the whole base set to satisfy its built-in invariants (`crypto-policies`, `ca-certificates`, `dash`, `tzdata`, `_autorebase`, etc.).
- `mkdir()` → `mkdirs()` on outputFolder creation so missing `target/` parent is auto-created.

### `babun-cygwin/cygwin.groovy`

- `installCygwin` adds `--no-admin --no-verify` to setup.exe args
  - `--no-admin`: stops UAC elevation that detaches setup.exe into a child process while the parent returns immediately (caused early Stage 3 to find no `bash.exe` since install was still running)
  - `--no-verify`: we don't fetch `.sig` files alongside packages
- `writeCygwinVersion(outputFolder, repoFolder)` — new function. Parses the `cygwin` package's `version:` line from `setup.ini` and writes the real version (e.g. `3.6.9-1`) to `target/cygwin.version`. Used by `babun-core` and by the `babun update` flow on the end-user machine.
- `@Grab(group='org.apache.groovy', module='groovy-ant', version='5.0.6')` + `import groovy.ant.AntBuilder` added at top — needed for Groovy 4+/5 where `AntBuilder` moved out of core into the `groovy-ant` optional module.
- `mkdir()` → `mkdirs()` (same pattern as packages.groovy).

### `babun-core/core.groovy` — the heart of Stage 3

- `@Grab` + `import groovy.ant.AntBuilder` (same reason as cygwin.groovy).
- `copyCygwin(rootFolder, cygwinFolder, outputFolder)` rewritten:
  - **Originally** used AntBuilder to copy `target/babun-cygwin/cygwin` → `target/babun-core/cygwin`. AntBuilder didn't preserve Cygwin's NTFS-SYSTEM-bit symlink encoding, leaving DLLs unloadable.
  - Tried `robocopy /COPY:DAT /SL` — same problem; doesn't preserve Cygwin's special-content symlinks.
  - Tried Cygwin's own `cp -a` via ProcessBuilder → bash invocation — Java mangled the path argument's backslashes through Windows command-line escaping, causing `cygpath` to receive `/` instead of the real path. ProcessBuilder did launch the correct bash; the bug was in arg encoding.
  - **Final solution**: `java.nio.file.Files.move(cygwinFolder.toPath(), dstDir.toPath())`. Single atomic filesystem rename. No copy, no bash, no escape mangling, preserves everything. Tradeoff: Stage 2's tree at `target/babun-cygwin/cygwin/` is gone after this step, so you must `clean` before retrying a failed Stage 3.
- `cygwin.version` marker: `versionDst.parentFile.mkdirs()` explicitly creates `/usr/local/etc/babun/installed/` before writing, because cp-a/move only contains what was in the source — that babun-specific path doesn't exist after a clean Cygwin install.
- `installCore`:
  - **Removed `rebaseall`** — broke libcurl/libssl signature loading on 64-bit Cygwin and was unnecessary (huge address space + ASLR removes fork() conflicts).
  - **Replaced `git clone https://github.com/babun/babun.git`** with AntBuilder copy from `rootFolder` (the local working tree). Local edits now flow into builds. Excludes `target/**`.
  - Removed the `git config --global http.sslverify` toggling around the clone (irrelevant after switching to local copy).

### `babun-dist/start/update.bat`

- Stopped downloading `cygwin.version` from the dead `github.com/babun/babun-cygwin/master/cygwin.version` URL.
- Stopped downloading the snapshotted `setup-x86_64.exe` from a babun-cygwin commit tag.
- Now grabs the live installer directly: `https://cygwin.com/setup-x86_64.exe`.
- The post-upgrade `installed/cygwin` marker is now populated via `uname -r` instead of copying the old GitHub-pinned `cygwin.version` file.

### `babun-core/tools/check.sh`

- Updated URL: `https://raw.githubusercontent.com/babun/babun-cygwin/master/cygwin.version` → `https://raw.githubusercontent.com/lancethepants/babun/master/cygwin.version`.
- A `cygwin.version` file at the repo root holds the "latest known Cygwin version" string (currently `3.6.5-1`). Bump it manually to trigger the "OUTDATED" hint for users who haven't updated.

## Package-list updates

`babun-packages/conf/cygwin.x86_64.packages` — removed packages that have been renamed or merged out of modern Cygwin x86_64:

| Original | Reason |
| --- | --- |
| `ncursesw` | Wide-char support merged into the main `ncurses` package |
| `perl_vendor` | Merged into `perl` |
| `gcc` | Meta-package removed; `gcc-core` (still in list) covers C compiler |
| `man` | Renamed → `man-db` |
| `procps` | Renamed → `procps-ng` |
| `readline` | No standalone tool anymore; library auto-pulled by `bash` |
| `libevent2.0_5` | Still in list, but downloaded as a stub/compat entry — modern is `libevent2.1_7` (auto-pulled via deps) |

## Mirrors updated

`babun-packages/conf/cygwin.repositories` — original 2014 list had many dead entries. Now:

```
https://mirrors.kernel.org/sourceware/cygwin/
https://cygwin.mirror.constant.com/
https://mirrors.rit.edu/cygwin/
https://mirror.clarkson.edu/cygwin/
```

`mirrors.kernel.org` is the most reliable single endpoint.

## Plugin fixes

### `babun-core/plugins/cygfix/install.sh` (the big one)

Original cygfix overwrote four Cygwin binaries with versions babun bundled around 2014:

- `/bin/mkpasswd.exe` ← `mkpasswd_1.7.29.exe`
- `/bin/mkgroup.exe` ← `mkgroup_1.7.29.exe`
- `/usr/libexec/git-core/git-remote-http.exe` ← `git-remote-http_2.1.4.exe`
- `/usr/libexec/git-core/git-remote-https.exe` ← `git-remote-https_2.1.4.exe`

These ancient binaries link against Cygwin 1.7-era libcurl/libssl/cygwin1.dll that no longer exist in 3.x Cygwin. After cygfix ran, **every HTTPS git operation aborted with `cannot open shared object file`**. This was the root cause of the multi-hour debugging chase (we thought it was AntBuilder, robocopy, rebaseall, WSL, symlinks, ...). It was cygfix all along.

The fix-bundled binaries addressed [babun#455](https://github.com/babun/babun/issues/455) which doesn't exist in modern Cygwin. Plugin is now a near-no-op (still creates `/bin/vi` symlink to vim).

### `babun-core/plugins/cacert/install.sh`

Original ran `cd /usr/ssl/certs` and downloaded `cacert.pem` from `curl.haxx.se` to split into individual cert files. That directory doesn't exist in modern Cygwin — the `ca-certificates` Cygwin package maintains the bundle at `/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem` and updates via normal `pact update`. Plugin is now a no-op echo.

### `babun-core/plugins/core/install.sh`

- **Reorder**: `.babunrc` is copied to `$babun/home/core/` BEFORE `source /usr/local/etc/babun.rc`. The rc file's tail has `if [[ ! -f "$homedir/.babunrc" ]]; then babun install; fi`, which triggers `plugin_install_home.sh` for every plugin — and the core plugin's install_home was failing because `home/core/.babunrc` didn't exist yet. Now it does.
- The `chmod -R /usr/local` and `chmod -R /etc` lines now have `|| echo "[babun] chmod ... had warnings above (non-fatal)"` instead of bare execution. Some files have Windows ACLs the non-admin build user can't modify; making it fatal kills the whole install.

## Known non-blocking issues

- `shell/install_home.sh` fails on `cp .vim` because `babun.rc`'s auto-install path triggers `install_home.sh` for every plugin BEFORE each plugin's `install.sh` has run. So `shell/install_home.sh` looks for `$babun/home/shell/.vim/` before `shell/install.sh` creates it. Only affects the build machine's `~/.vim` — the dist zip is intact because `shell/install.sh` runs later and populates the template. End-user installs are fine.
- `cat: /usr/local/etc/babun/installed/<plugin>: No such file or directory` — stderr noise from `cat || echo 0` in `plugin_install_home`. Harmless first-install signal.
- `[babun] chmod /etc had warnings above (non-fatal)` — informational, intentional.

## Key debugging discoveries (in case they reappear)

1. **`groovy.bat` not found**: Scoop installs `groovy.cmd`, not `.bat`. ProcessBuilder needs `cmd /c` to find `.cmd` shims.
2. **`setup-x86_64.exe` runs but installs nothing**: UAC elevated child process; original parent returned immediately. Fix: `--no-admin`.
3. **`solving: 0 tasks`**: Old wget invocation produced wrong directory layout. Setup.exe expects `<local>/x86_64/setup.ini` and `<local>/x86_64/release/<pkg>/...`. Fix was rewriting download in Groovy.
4. **`Can't happen. No packagemeta for base`**: Missing Base-category packages. Fix: auto-include them from setup.ini.
5. **`Cannot find dependencies of [_windows]`**: `depends2:` field includes virtual deps. Fix: silently skip un-resolvable deps.
6. **`git-remote-https.exe: error while loading shared libraries: ?: cannot open shared object file`** — the big one. Cygfix replaced modern binaries with 2014 ones. Fix: neuter cygfix.
7. **`The Windows Subsystem for Linux is not installed` dialog during build**: Java's ProcessBuilder hit `bash.exe` in `WindowsApps\` (App Execution Alias) instead of cygwin's bash. Fix: avoid invoking bash from Java entirely (Files.move).
8. **`mkdir()` silently fails**: Java's `File.mkdir()` only creates one level. Use `mkdirs()` when parent might not exist.

## Files unchanged but worth knowing

- `babun-cygwin/symlinks/symlinks_find.sh` (runs end of Stage 2, captures all symlinks to `symlinks_broken.txt`)
- `babun-cygwin/symlinks/symlinks_repair.sh` (runs in Stage 3 via `tools/fix_symlinks.sh`, and on end-user via `install.bat` → `post_extract.sh`, re-sets SYSTEM bit on each captured path)
- `cygwin.version` at repo root — hardcoded "latest known" version for update detection. Bump manually.
