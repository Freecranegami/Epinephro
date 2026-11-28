#!/usr/bin/env python3
"""Recompile shaders/wfg_synth.comp and splice the SPIR-V into embedded_shaders.hpp (same flags as win-fg's build_shaders.sh).
usage: python3 tools/regen_synth.py [path to glslangValidator]"""
import pathlib, re, subprocess, sys, tempfile
root = pathlib.Path(__file__).resolve().parents[1]
glslang = sys.argv[1] if len(sys.argv) > 1 else "glslangValidator"
with tempfile.TemporaryDirectory() as d:
    out = pathlib.Path(d) / "s.spv"
    subprocess.run([glslang, "-e", "main", "-S", "comp", "--target-env", "vulkan1.1", "-V", str(root / "shaders/wfg_synth.comp"), "-o", str(out)], check=True)
    data = out.read_bytes()
words = [int.from_bytes(data[i:i + 4], "little") for i in range(0, len(data), 4)]
hpp = root / "embedded_shaders.hpp"
h = hpp.read_text()
h, n1 = re.subn(r"static const uint32_t WFG_SYNTH_SPV\[\] = \{[^}]*\};", "static const uint32_t WFG_SYNTH_SPV[] = {" + ",".join(f"0x{w:08x}u" for w in words) + "};", h)
h, n2 = re.subn(r"static const size_t  WFG_SYNTH_LEN = \d+;", f"static const size_t  WFG_SYNTH_LEN = {len(words)};", h)
assert n1 == 1 and n2 == 1
hpp.write_text(h)
print("WFG_SYNTH_SPV:", len(words), "words")
