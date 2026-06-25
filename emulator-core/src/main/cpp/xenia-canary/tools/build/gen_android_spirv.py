#!/usr/bin/env python3
"""Regenerate the Vulkan SPIR-V shader bytecode headers for the Android build.

Edge's in-tree shader pipeline drives a host-built tool (xenia-shader-cc) plus
slangc, neither of which runs inside the arm64 NDK cross-build. XenDroid instead
guards edge's shader rules off on Android (see gpu/vulkan and ui/vulkan
CMakeLists) and produces the SPIR-V bytecode headers with this host-side script,
which only needs Python + the system glslang/spirv tools (glslangValidator,
spirv-opt, spirv-dis on PATH or under $VULKAN_SDK).

For each stage shader source under the shader dirs it writes
  <shader_dir>/bytecode/vulkan_spirv/<id>.h
via compile_shader_spirv.py. Behaviour:
  * Prefers a hand-tuned .glsl/.xesl twin over the .slang form (matching xenia's
    xe_shader_rules_slang "defer to legacy twin" skip).
  * Skips sources tagged `// XE_DXIL_ONLY` (D3D12-only, no SPIR-V output).
  * Incremental: skips an output that is newer than its source.
  * Tolerant by default: a shader that fails to compile (e.g. a true-Slang
    source glslang can't parse) is warned about and skipped, so it never blocks
    the build of the shaders the Vulkan backend actually consumes. Pass --strict
    to fail on the first error.

Usage: gen_android_spirv.py [--strict] <shader_dir> [<shader_dir> ...]
"""

import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
COMPILE = os.path.join(HERE, "compile_shader_spirv.py")
STAGES = ("vs", "hs", "ds", "gs", "ps", "cs")
# Authoritative-source preference: a hand-tuned GLSL/xesl twin wins over .slang.
EXT_PREF = (".xesl", ".glsl", ".slang")


def stage_of(name):
    base, ext = os.path.splitext(name)  # 'foo.cs', '.slang'
    if ext not in (".xesl", ".glsl", ".slang"):
        return None
    stage = os.path.splitext(base)[1].lstrip(".")  # 'cs'
    return stage if stage in STAGES else None


def collect(shader_dir):
    """Return {id: best_source_path} for stage shaders in shader_dir."""
    by_id = {}
    for name in sorted(os.listdir(shader_dir)):
        path = os.path.join(shader_dir, name)
        if not os.path.isfile(path) or stage_of(name) is None:
            continue
        ident = os.path.splitext(name)[0].replace(".", "_")  # 'foo_cs'
        ext = os.path.splitext(name)[1]
        cur = by_id.get(ident)
        if cur is None or EXT_PREF.index(ext) < EXT_PREF.index(os.path.splitext(cur)[1]):
            by_id[ident] = path
    return by_id


def is_dxil_only(path):
    try:
        with open(path, "r", errors="ignore") as f:
            for _ in range(40):
                line = f.readline()
                if not line:
                    break
                if line.lstrip().startswith("//") and "XE_DXIL_ONLY" in line:
                    return True
    except OSError:
        pass
    return False


def main():
    args = sys.argv[1:]
    strict = "--strict" in args
    dirs = [a for a in args if a != "--strict"]
    if not dirs:
        print(__doc__)
        return 2

    generated = skipped = failed = 0
    for shader_dir in dirs:
        shader_dir = os.path.abspath(shader_dir)
        out_dir = os.path.join(shader_dir, "bytecode", "vulkan_spirv")
        for ident, src in sorted(collect(shader_dir).items()):
            if is_dxil_only(src):
                continue
            out = os.path.join(out_dir, ident + ".h")
            if os.path.exists(out) and os.path.getmtime(out) >= os.path.getmtime(src):
                skipped += 1
                continue
            r = subprocess.run([sys.executable, COMPILE, src, out],
                               stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                               text=True)
            if r.returncode == 0:
                generated += 1
            else:
                failed += 1
                msg = (r.stderr or "").strip().splitlines()
                tail = msg[-1] if msg else "(no message)"
                print(f"WARN: skipped {os.path.basename(src)} -> {ident}.h: {tail}",
                      file=sys.stderr)
                if strict:
                    return 1

    print(f"spirv bytecode: {generated} generated, {skipped} up-to-date, "
          f"{failed} skipped/failed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
