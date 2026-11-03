#!/usr/bin/python3
"""Parse IX7TAP logcat lines (already masked at the source by ix7.Tap) into a
sanitized JSON report: command counts, LOCK windows (t_cmd, t_ack, t_error,
t_state), t_auth, heartbeat cadence, CONNECT/0x41 values and CRC byte order.

Only IX7TAP lines are read; every other logcat line is ignored, so MACs and
other identifiers from the system log never reach the output. Masked bytes
('**') stay masked. Usage: ix7tap_parse.py logcat.txt > report.json
"""
import json
import re
import statistics
import sys

# threadtime: "MM-DD HH:MM:SS.mmm  PID  TID L TAG: msg" -- IX7TAP must be the tag itself
LINE = re.compile(r"^\S+\s+\S+\s+\d+\s+\d+\s+[VDIWEF]\s+IX7TAP\s*:\s*(TX|RX|WR) t=(\d+)(?: uuid=([0-9a-f]{4}|null))?\s*(.*)$")
FRAME = re.compile(r"^([0-9a-f*]+) crc=(lohi|hilo|bad)(?: lock_data_len=(\d+))?$")


def parse_frame(hexs):
    """Return dict with cmd, tipo, len, data (list, None for masked bytes)."""
    b = [None if hexs[i:i + 2] == "**" else int(hexs[i:i + 2], 16) for i in range(0, len(hexs), 2)]
    if len(b) < 9 or b[0] != 0x1A or b[1] != 0xA1:
        return None
    n = b[4]
    return {"cmd": b[2], "tipo": b[3], "len": n, "data": b[5:5 + n]}


def events(lines):
    out = []
    for line_no, ln in enumerate(lines):
        m = LINE.search(ln)
        if not m:
            continue
        kind, t, uuid, rest = m.group(1), int(m.group(2)), m.group(3), m.group(4).strip()
        if kind == "WR":
            st = re.match(r"status=(-?\d+)(?: (.*))?$", rest)
            out.append({"k": "WR", "line": line_no, "t": t, "uuid": uuid,
                        "status": int(st.group(1)) if st else None,
                        "payload": (st.group(2) or "") if st else ""})
            continue
        payload = rest
        for part in rest.split(" | "):
            part = part.strip()
            if part.startswith("nonframe_len=") or part == "null":
                out.append({"k": kind, "line": line_no, "payload": payload, "t": t, "uuid": uuid, "nonframe": part})
                continue
            fm = FRAME.match(part)
            if not fm:
                out.append({"k": kind, "line": line_no, "payload": payload, "t": t, "uuid": uuid, "nonframe": "unparsed"})
                continue
            f = parse_frame(fm.group(1))
            if f is None:
                out.append({"k": kind, "line": line_no, "payload": payload, "t": t, "uuid": uuid, "nonframe": "unparsed"})
                continue
            f.update({"k": kind, "line": line_no, "payload": payload, "t": t, "uuid": uuid, "crc": fm.group(2),
                      "lock_data_len": int(fm.group(3)) if fm.group(3) else None})
            out.append(f)
    out.sort(key=lambda e: e["t"])  # stable: equal timestamps keep log order
    return out


def meter1(e):
    """Meter1 (CMD 0x20) status from RX data; None when too short or masked."""
    d = e.get("data") or []
    if e.get("k") != "RX" or e.get("cmd") != 0x20 or e.get("crc") == "bad" or len(d) < 4 or None in d[:4]:
        return None
    st = d[1]
    return {"lock_status": (st >> 5) & 1, "lock_has_pwd": (st >> 4) & 1, "battery_pct": d[3]}


def ms(a, b):
    return None if a is None or b is None else round((b - a) / 1e6, 3)


def lock_windows(ev):
    res = []
    locks = [i for i, e in enumerate(ev) if e["k"] == "TX" and e.get("cmd") == 0x32]
    # FastBle serializes writes: the n-th WR callback belongs to the n-th TX (FIFO).
    # One logcat TX line = one GATT write. The WR callback carries the written value,
    # so match it by (masked) content to the oldest unmatched TX with that payload.
    wr_of = {}
    pending = []
    seen_lines = set()
    for i, e in enumerate(ev):
        if e["k"] == "TX" and e["line"] not in seen_lines:
            seen_lines.add(e["line"])
            pending.append((e["line"], e["payload"]))
        elif e["k"] == "WR" and e["payload"]:
            hit = next((p for p in pending if p[1] == e["payload"]), None)
            if hit:
                wr_of[hit[0]] = i
                pending.remove(hit)
    for idx in locks:
        tx = ev[idx]
        prev = None
        for e in ev[:idx]:
            m = meter1(e)
            if m:
                prev = m
        nxt = next((j for j in locks if j > idx), len(ev))
        w = {"mode": tx["data"][0] if tx["data"] else None, "lock_data_len": tx["lock_data_len"],
             "crc": tx["crc"], "t_cmd_ms": None, "t_ack_ms": None, "t_error_ms": None, "t_state_ms": None,
             "state_before": prev, "state_after": None}
        wr = wr_of.get(tx["line"])
        if wr is not None:
            w["t_cmd_ms"] = ms(tx["t"], ev[wr]["t"])
        for e in ev[idx + 1:nxt]:
            if e["k"] == "RX" and e.get("cmd") == 0x32 and e.get("crc") != "bad":
                if e["tipo"] == 0x05 and w["t_ack_ms"] is None:
                    w["t_ack_ms"] = ms(tx["t"], e["t"])
                elif e["tipo"] == 0x89 and w["t_error_ms"] is None:
                    w["t_error_ms"] = ms(tx["t"], e["t"])
            m = meter1(e)
            if m and prev is not None and w["t_state_ms"] is None and m["lock_status"] != prev["lock_status"]:
                w["t_state_ms"] = ms(tx["t"], e["t"])
                w["state_after"] = m
        res.append(w)
    return res


def stats(vals):
    v = sorted(x for x in vals if x is not None)
    if not v:
        return None
    p95 = v[min(len(v) - 1, int(round(0.95 * (len(v) - 1))))]
    return {"n": len(v), "median": round(statistics.median(v), 3), "p95": round(p95, 3), "min": v[0], "max": v[-1]}


def report(ev):
    counts = {}
    for e in ev:
        if "cmd" in e:
            key = f"{e['k']} 0x{e['cmd']:02X} tipo=0x{e['tipo']:02X}"
            counts[key] = counts.get(key, 0) + 1
    connects = [e for e in ev if e["k"] == "TX" and e.get("cmd") == 0x30]
    auth = []
    for n, c in enumerate(connects):
        stop = connects[n + 1]["t"] if n + 1 < len(connects) else None
        r = next((e for e in ev if e["t"] > c["t"] and (stop is None or e["t"] < stop)
                  and e["k"] == "RX" and "cmd" in e and e.get("crc") != "bad"), None)
        auth.append({"connect_value": c["data"][0] if c["data"] else None, "t_auth_ms": ms(c["t"], r["t"] if r else None)})
    hb = [e["t"] for e in ev if e["k"] == "TX" and e.get("cmd") == 0x10]
    hb_gap = [ms(a, b) for a, b in zip(hb, hb[1:])]
    w = lock_windows(ev)
    return {
        "events": len(ev),
        "nonframe_events": sum(1 for e in ev if "nonframe" in e),
        "crc_order": sorted({e["crc"] for e in ev if "crc" in e}),
        "uuids": sorted({e["uuid"] for e in ev if e.get("uuid")}),
        "command_counts": counts,
        "connect": auth,
        "current_user_car_0x41": [e["data"][0] if e["data"] else None for e in ev if e["k"] == "TX" and e.get("cmd") == 0x41],
        "heartbeat_gap_ms": stats(hb_gap),
        "lock_windows": w,
        "lock_stats": {k: stats([x[k] for x in w]) for k in ("t_cmd_ms", "t_ack_ms", "t_state_ms")},
    }


def main():
    with open(sys.argv[1], errors="replace") as f:
        ev = events(f)
    json.dump(report(ev), sys.stdout, indent=2)
    print()


if __name__ == "__main__":
    main()
