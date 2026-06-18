# Rebase the emulator core onto xenia-edge — design

**Date:** 2026-06-18
**Branch:** `rebase-xenia-edge`
**Upstream:** `has207/xenia-edge`, branch `edge` (remote `xenia-edge`)

## Goal

Update XenDroid's vendored Xenia emulator core (`emulator-core/src/main/cpp/xenia-canary/`)
from its old Xenia Canary base to the tip of the `xenia-edge` fork's `edge` branch, **aligning
with edge as much as possible**, while preserving the Android-essential modifications XenDroid
has made to that source. Convert `third_party` from vendored files to **git submodules**, matching
edge. Land the result as a committed, documented rebase. Making it compile/link and run on a
device is an explicitly **deferred** follow-up phase.

## Topology

```
        49c700b9c  ← shared ancestor (the canary commit XenDroid vendored via git subtree add)
       /         \
XenDroid (ours)   xenia-edge/edge (theirs)
• xenia source UNDER prefix        • xenia source at REPO ROOT
  emulator-core/src/main/cpp/      • third_party = 41 git submodules (gitlinks)
  xenia-canary/                    • +1187 commits since base: GPU/Vulkan, CPU/a64,
• third_party VENDORED               SDL2→SDL3, +asio/boost_context/SPIRV-Cross/MoltenVK
  (~40k real files)
• +131 commits since base: a64 JIT, Vulkan/Turnip,
  Android platform, Compose UI, rebrand
```

- A literal `git rebase --onto` is **not viable**: our xenia source lives under a path prefix
  while edge has it at repo root, and the 131 "ours" commits include the entire Android-app
  history behind a `git subtree` merge.
- Edge keeps `third_party/*` as **submodule gitlinks** (mode 160000); XenDroid **materialized**
  them as real trees (mode 040000). A naive merge would turn 40k vendored files into empty
  submodule pointers (or storm with type-change conflicts).
- Both sides modified the **same 92 source files** (the conflict surface). XenDroid changed
  **51** source files edge did not (auto-preserved). Edge changed ~700 files XenDroid did not
  (auto-adopted).
- Edge **also has an a64 backend** (`src/xenia/cpu/backend/a64`), so "prefer edge" cannot be
  blind: XenDroid's Android-essential a64 work (unwind/FDE generation that otherwise hung every
  boot) must survive where it overlaps edge's a64 changes.

## Mechanism (deterministic where possible, 3-way only where needed)

The net change = edge's diff (base→edge) applied under the prefix, layered with XenDroid's
source delta. We express it as a **single subtree-aware merge commit** (parents: current
`rebase-xenia-edge` HEAD + edge tip) so edge ancestry is recorded and future updates are tractable.

1. **Safety backup.** Tag + backup branch of current HEAD for one-command rollback.
2. **third_party → submodules (prep commit).** Replace the vendored `third_party` trees under the
   prefix with edge's **gitlink entries** (copied directly from `xenia-edge/edge:third_party`, no
   need for the submodule objects to be present). Write a **root `.gitmodules`** generated from
   edge's 41 entries with each `path` prefixed by `emulator-core/src/main/cpp/xenia-canary/`
   (URLs identical to edge). The in-tree `<prefix>/.gitmodules` becomes edge's version via the
   merge. This pre-alignment makes third_party identical on both sides so the merge produces
   **no third_party conflict storm**.
3. **Subtree merge.** `git merge -X subtree=<prefix> xenia-edge/edge` (no-commit). Inspect the
   conflict set; it should be confined to the source overlap (~92 files). If it storms on
   third_party despite prep, fall back to a scratch-worktree merge at repo root via
   `git commit-tree` (our prefix tree re-rooted, parented on the base) then transplant.
4. **Conflict resolution — fanned out in a Workflow, one agent per conflicted file.**
   - **Default: take edge.** Maximize alignment with upstream.
   - **Keep XenDroid's side only for a documented, Android-essential reason:** a64 unwind/FDE
     and JIT-on-Android fixes, Vulkan/Turnip/Adreno workarounds, SAF/JNI/file-picker/surface
     integration, present-from-non-UI-thread and audio-thread fixes, and config/path handling
     required on Android.
   - Each conflict produces a structured report row: file, what each side changed, the decision,
     and the rationale. Agents edit only their assigned file; they perform no git index/state ops.
5. **Finalize.** Verify zero remaining conflict markers and a clean `git status` w.r.t. the merge;
   assemble `REBASE_EDGE_REPORT.md` from the collected rows; add a BUILD.md note that third_party
   is now submodules (`git submodule update --init --recursive` required before building); commit
   the merge.

## What is auto-handled vs judged

- **Auto-adopt edge** for the ~700 files only edge changed and for all of third_party.
- **Auto-keep XenDroid** for the 51 source files only XenDroid changed (incl. pure-Android files
  such as `file_picker_android.cc`, `surface_android.cc`) and XenDroid-only added files.
- **Human/agent judgment** only for the ~92 files both sides changed.

## Deliverables

- Rebased `rebase-xenia-edge` branch with a single merge commit (edge recorded as a parent).
- `third_party` as submodules matching edge + prefixed root `.gitmodules`.
- `REBASE_EDGE_REPORT.md`: every conflict, both sides' intent, resolution, rationale.
- This design doc; a BUILD.md submodule-init note.

## Explicitly deferred (per "land the rebase" decision)

- Making it compile/link against edge's reworked deps (SDL2→SDL3, asio/boost_context, new
  Vulkan/GPU APIs) and the Android CMake integration of edge's `third_party/CMakeLists.txt`.
- Initializing the actual submodule contents and selecting the Android-needed subset.
- On-device boot/run verification.

## Rollback

`git reset --hard <backup-tag>` restores the pre-rebase state; the backup branch and the
`xenia-edge` remote remain for re-attempts.
