#!/usr/bin/env python3
"""Summarise a KGSL trace written by Epinephro's "GPU command trace" setting.

usage: kgsl-analyze.py <folder with trace.txt and submit_*.kgt>

Prints the allocation/submission summary, then the count of each GPU command packet (draws, event writes, indirect
buffers, render-mode markers, ...) found in the dumped command buffers, following indirect buffers that were also dumped.
Run it on a Turnip trace and on a stock-driver trace of the same scene and compare the two. Adreno 6xx/7xx packet layout.
"""
import struct, sys, glob, collections, os

PM4 = {0: 'CP_TYPE0_PKT', 1073741824: 'CP_TYPE1_PKT', 2147483648: 'CP_TYPE2_PKT', 3221225472: 'CP_TYPE3_PKT', 1879048192: 'CP_TYPE7_PKT', 72: 'CP_ME_INIT', 16: 'CP_NOP', 28: 'CP_PREEMPT_ENABLE', 30: 'CP_PREEMPT_TOKEN', 63: 'CP_INDIRECT_BUFFER', 87: 'CP_INDIRECT_BUFFER_CHAIN', 55: 'CP_INDIRECT_BUFFER_PFD', 38: 'CP_WAIT_FOR_IDLE', 60: 'CP_WAIT_REG_MEM', 82: 'CP_WAIT_REG_EQ', 83: 'CP_WAIT_REG_GTE', 92: 'CP_WAIT_UNTIL_READ', 93: 'CP_WAIT_IB_PFD_COMPLETE', 33: 'CP_REG_RMW', 47: 'CP_SET_BIN_DATA', 62: 'CP_REG_TO_MEM', 61: 'CP_MEM_WRITE', 79: 'CP_MEM_WRITE_CNTR', 68: 'CP_COND_EXEC', 69: 'CP_COND_WRITE', 70: 'CP_EVENT_WRITE', 88: 'CP_EVENT_WRITE_SHD', 89: 'CP_EVENT_WRITE_CFL', 91: 'CP_EVENT_WRITE_ZPD', 49: 'CP_RUN_OPENCL', 34: 'CP_DRAW_INDX', 54: 'CP_DRAW_INDX_2', 52: 'CP_DRAW_INDX_BIN', 53: 'CP_DRAW_INDX_2_BIN', 35: 'CP_VIZ_QUERY', 37: 'CP_SET_STATE', 45: 'CP_SET_CONSTANT', 39: 'CP_IM_LOAD', 43: 'CP_IM_LOAD_IMMEDIATE', 46: 'CP_LOAD_CONSTANT_CONTEXT', 59: 'CP_INVALIDATE_STATE', 74: 'CP_SET_SHADER_BASES', 80: 'CP_SET_BIN_MASK', 81: 'CP_SET_BIN_SELECT', 94: 'CP_CONTEXT_UPDATE', 64: 'CP_INTERRUPT', 44: 'CP_IM_STORE', 75: 'CP_SET_BIN_BASE_OFFSET', 95: 'CP_SET_PROTECTED_MODE', 111: 'CP_BOOTSTRAP_UCODE', 48: 'CP_LOAD_STATE', 58: 'CP_COND_INDIRECT_BUFFER_PFE', 50: 'CP_COND_INDIRECT_BUFFER_PFD', 76: 'CP_SET_BIN', 113: 'CP_TEST_TWO_MEMS', 120: 'CP_REG_WR_NO_CTXT', 17: 'CP_RECORD_PFP_TIMESTAMP', 102: 'CP_SET_SECURE_MODE', 19: 'CP_WAIT_FOR_ME', 67: 'CP_SET_DRAW_STATE', 56: 'CP_DRAW_INDX_OFFSET', 40: 'CP_DRAW_INDIRECT', 41: 'CP_DRAW_INDX_INDIRECT', 42: 'CP_DRAW_INDIRECT_MULTI', 36: 'CP_DRAW_AUTO', 25: 'CP_DRAW_PRED_ENABLE_GLOBAL', 26: 'CP_DRAW_PRED_ENABLE_LOCAL', 78: 'CP_DRAW_PRED_SET', 116: 'CP_WIDE_REG_WRITE', 77: 'CP_SCRATCH_TO_REG', 18: 'CP_WAIT_MEM_WRITES', 71: 'CP_COND_REG_EXEC', 66: 'CP_MEM_TO_REG', 65: 'CP_EXEC_CS_INDIRECT', 51: 'CP_EXEC_CS', 101: 'CP_SET_MARKER', 86: 'CP_SET_PSEUDO_REG', 29: 'CP_SKIP_IB2_ENABLE_GLOBAL', 98: 'CP_WHERE_AM_I', 100: 'CP_SET_VISIBILITY_OVERRIDE', 105: 'CP_PREEMPT_ENABLE_GLOBAL', 106: 'CP_PREEMPT_ENABLE_LOCAL', 107: 'CP_CONTEXT_SWITCH_YIELD', 108: 'CP_SET_RENDER_MODE', 110: 'CP_COMPUTE_CHECKPOINT', 115: 'CP_MEM_TO_MEM', 57: 'CP_REG_TEST', 99: 'CP_SET_MODE', 114: 'CP_REG_TO_MEM_OFFSET_REG', 20: 'CP_WAIT_MEM_GTE', 112: 'CP_WAIT_TWO_REGS', 117: 'CP_MEMCPY', 84: 'CP_CONTEXT_SWITCH', 85: 'CP_SET_AMBLE', 109: 'CP_REG_WRITE', 21: 'CP_GLOBAL_TIMESTAMP', 22: 'CP_LOCAL_TIMESTAMP', 23: 'CP_THREAD_CONTROL', 24: 'CP_RESOURCE_LIST', 27: 'CP_BV_BR_COUNT_OPS', 73: 'CP_MEM_TO_OC_MEM', 127: 'CP_FIXED_STRIDE_DRAW_TABLE', 31: 'CP_RESET_CONTEXT_STATE', 1: 'CP_SET_THREAD_BR', 2: 'CP_SET_THREAD_BV', 3: 'CP_SET_THREAD_BOTH'}
MARK = {1: "DIRECT_RENDER", 2: "BIN_VISIBILITY", 3: "BIN_DIRECT", 4: "BIN_RENDER_START", 5: "BIN_END_OF_DRAWS",
        6: "BIN_RESOLVE", 7: "BIN_RENDER_END", 8: "COMPUTE", 12: "BLIT2DSCALE"}
HDR = struct.Struct("<IIQQQQ")

def load(path):
    d = open(path, "rb").read()
    assert d[:4] == b"KGT1", path
    i, recs = 8, []
    while i + HDR.size <= len(d):
        kind, oid, addr, size, flags, base = HDR.unpack_from(d, i)
        i += HDR.size
        recs.append((kind, oid, addr, size, flags, base, d[i:i + size]))
        i += size
    return recs

def walk(buf, objs, cnt, marks, depth=0):
    words = struct.unpack("<%dI" % (len(buf) // 4), buf[: len(buf) // 4 * 4])
    i = 0
    while i < len(words):
        h = words[i]
        t = h >> 28
        if t == 7:
            op, n = (h >> 16) & 0x7f, h & 0x3fff
            cnt[PM4.get(op, "op_0x%02x" % op)] += 1
            body = words[i + 1:i + 1 + n]
            if op == 0x65 and body:
                marks[MARK.get(body[0] & 0xf, "mode_%d" % (body[0] & 0xf))] += 1
            if op == 0x3f and len(body) >= 3 and depth < 4:
                addr = body[0] | (body[1] << 32)
                for a, data in objs:
                    if a <= addr < a + len(data):
                        off = addr - a
                        walk(data[off: off + body[2] * 4], objs, cnt, marks, depth + 1)
                        break
            i += 1 + n
        elif t == 4:
            cnt["reg_writes(type4)"] += 1
            i += 1 + (h & 0x7f)
        else:
            i += 1

def main(folder):
    p = os.path.join(folder, "trace.txt")
    if os.path.exists(p):
        lines = open(p).read().splitlines()
        last = [i for i, l in enumerate(lines) if l.startswith("sum submits")]
        if last:
            print("\n".join(lines[last[-1]:]))
        for l in lines:
            if l.startswith("ctx create"):
                print(l)
    cnt, marks = collections.Counter(), collections.Counter()
    files = sorted(glob.glob(os.path.join(folder, "submit_*.kgt")))
    for f in files:
        recs = load(f)
        objs = [(r[2], r[6]) for r in recs]
        for r in recs:
            if r[0] == 1:
                walk(r[6], objs, cnt, marks)
    print("\ndumped submits:", len(files))
    for k, v in cnt.most_common(40):
        print("%8d  %s" % (v, k))
    print("render-mode markers:", dict(marks))

if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
