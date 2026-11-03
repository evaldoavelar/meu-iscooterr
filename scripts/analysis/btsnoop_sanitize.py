#!/usr/bin/python3
"""Decodificador PASSIVO e sanitizador de btsnoop (HCI) do iScooter.

Lê um btsnoop local (research/captures/, ignorado pelo git) e imprime SOMENTE dados
sanitizados: PIN mascarado, MACs substituídos por pseudônimos (DEV-n), sem bytes de PIN.
Nunca escreve nem envia nada ao patinete. Uso: btsnoop_sanitize.py <arquivo.btsnoop>
"""
import struct, sys, hashlib, json

EPOCH_DELTA_US = 0x00dcddb30f2f8000  # btsnoop: µs desde 0000-01-01
CMDS = {0x10:"HEARTBEAT",0x20:"METER1",0x21:"METER2",0x30:"CONNECT",0x31:"POWERON",0x32:"LOCK",
        0x41:"CURRENT_USER_CAR",0x60:"QUERY_CAR",0x61:"QUERY_SN",0x62:"QUERY_SPEED_LIMIT",
        0x63:"QUERY_LIGHT",0x66:"QUERY_PRO",0x67:"QUERY_LOCK_PASSWORD"}

def crc16_modbus(b):
    c = 0xFFFF
    for x in b:
        c ^= x
        for _ in range(8):
            c = (c >> 1) ^ 0xA001 if c & 1 else c >> 1
    return c

def parse_frame(v):
    """Valida/decodifica um quadro 1A A1 ... 1F F1. Retorna dict sanitizado ou None."""
    if len(v) < 9 or v[0:2] != b"\x1a\xa1" or v[-2:] != b"\x1f\xf1":
        return None
    cmd, typ, ln = v[2], v[3], v[4]
    data, crc = v[5:-4], v[-4:-2]
    body = v[2:-4]
    c = crc16_modbus(body)
    crc_ok = crc == bytes([c & 0xFF, c >> 8]) or crc == bytes([c >> 8, c & 0xFF])
    out = {"cmd": f"0x{cmd:02X}", "name": CMDS.get(cmd, "?"), "type": f"0x{typ:02X}",
           "len": ln, "len_ok": ln == len(data) or ln == 0, "crc_ok": crc_ok}
    if cmd == 0x32 and typ == 0x02:           # comando LOCK: DADOS = [modo] + dígitos do PIN
        out["lock_mode"] = data[0] if data else None
        out["pin_present"] = len(data) > 1     # NUNCA expõe os dígitos
        out["pin_len"] = max(len(data) - 1, 0)
    elif cmd == 0x20 and typ != 0x02 and len(v) >= 17:   # Meter1: byte[6] = status, byte[8] = bateria
        st = v[6]
        out.update({"lock_has_pwd": (st >> 4) & 1, "lock_status": (st >> 5) & 1,
                    "battery_pct": v[8]})
    elif cmd == 0x67:
        out["data"] = "<mascarado>"            # resposta pode conter o PIN
    elif cmd == 0x61:
        out["data"] = "<sn-mascarado>"
    else:
        out["data_len"] = len(data)
    return out

class Pseudo:
    def __init__(self): self.m = {}
    def __call__(self, mac_bytes):
        k = hashlib.sha256(bytes(mac_bytes)).hexdigest()
        self.m.setdefault(k, f"DEV-{len(self.m)+1}")
        return self.m[k]

def read_records(path):
    d = open(path, "rb").read()
    if d[:8] != b"btsnoop\0":
        raise SystemExit("não é btsnoop")
    ver, dl = struct.unpack(">II", d[8:16])
    o = 16
    while o + 24 <= len(d):
        orig, incl, flags, drops, ts = struct.unpack(">IIIIq", d[o:o+24]); o += 24
        yield flags, (ts - EPOCH_DELTA_US) / 1e6, d[o:o+incl]; o += incl

def analyze(path):
    pseudo = Pseudo(); events = []; t0 = None
    frag = {}  # handle -> bytes reassembly (ACL)
    for flags, t, pkt in read_records(path):
        if t0 is None: t0 = t
        rel = round(t - t0, 3)
        direction = "rx" if flags & 1 else "tx"    # bit0: 1 = controller->host
        if not pkt: continue
        ptype, body = pkt[0], pkt[1:]
        if ptype == 0x01 and len(body) >= 3:                       # comando HCI
            op = body[0] | body[1] << 8
            if op == 0x200D: events.append((rel, "LE_CREATE_CONNECTION", {}))
            elif op in (0x200C, 0x2042) and len(body) > 3 and body[3] == 1:   # LE (Extended) Set Scan Enable = on
                events.append((rel, "SCAN_START", {}))
        elif ptype == 0x04 and len(body) >= 2:                     # evento HCI
            ev = body[0]
            if ev == 0x3E and len(body) >= 4:
                sub = body[2]
                if sub in (0x01, 0x0A) and body[3] == 0:           # LE Connection Complete
                    events.append((rel, "LE_CONNECTED", {}))
                elif sub in (0x02, 0x0D):                          # advertising report
                    events.append((rel, "ADV_REPORT", {}))
        elif ptype == 0x02 and len(body) >= 8:                     # ACL
            handle = (body[0] | body[1] << 8) & 0x0FFF
            l2 = body[4:]
            cid = l2[2] | l2[3] << 8 if len(l2) >= 4 else 0
            att = l2[4:] if cid == 4 else b""
            if att and att[0] in (0x12, 0x52, 0x1B, 0x1D) and len(att) > 3:
                val = att[3:]
                fr = parse_frame(val)
                if fr:
                    events.append((rel, f"ATT_{direction.upper()}", fr))
    return events

def timings(events):
    """Tempos relativos (t_scan = início do scan → LE Create Connection, proxy HCI da seleção; seleção na UI só via logcat): scan→conexão, comando→ACK, comando→estado real. Em segundos."""
    res = {}
    first_adv = next((t for t, k, _ in events if k == "SCAN_START"), None)
    create = next((t for t, k, _ in events if k == "LE_CREATE_CONNECTION"), None)
    conn = next((t for t, k, _ in events if k == "LE_CONNECTED"), None)
    c30 = next((t for t, k, f in events if k == "ATT_TX" and f["cmd"] == "0x30"), None)
    r30 = next((t for t, k, f in events if k == "ATT_RX" and f["cmd"] == "0x30" and c30 is not None and t >= c30), None)
    if c30 is not None and r30 is not None: res["t_auth"] = round(r30 - c30, 3)   # CONNECT enviado -> 1ª resposta
    if first_adv is not None and create is not None: res["t_scan"] = round(create - first_adv, 3)
    if create is not None and conn is not None: res["t_conn"] = round(conn - create, 3)
    if conn is not None and c30 is not None: res["t_auth_first_cmd"] = round(c30 - conn, 3)
    locks = [(t, f) for t, k, f in events if k == "ATT_TX" and f["cmd"] == "0x32"]
    out = []
    for i, (t, f) in enumerate(locks):
        nxt = locks[i + 1][0] if i + 1 < len(locks) else float("inf")   # janela: até o próximo comando LOCK
        ack = next((t2 for t2, k, g in events if k == "ATT_RX" and g["cmd"] == "0x32" and g["type"] == "0x05" and t <= t2 < nxt), None)
        err = next((t2 for t2, k, g in events if k == "ATT_RX" and g["cmd"] == "0x32" and g["type"] == "0x89" and t <= t2 < nxt), None)
        prev = next((g["lock_status"] for t2, k, g in reversed(events) if t2 <= t and k == "ATT_RX" and "lock_status" in g), None)
        st = next((t2 for t2, k, g in events if k == "ATT_RX" and "lock_status" in g and t <= t2 < nxt and prev is not None and g["lock_status"] != prev), None)
        out.append({"t_cmd": round(t, 3), "t_ack": None if ack is None else round(ack - t, 3),
                    "t_state": None if st is None else round(st - t, 3),
                    "t_error": None if err is None else round(err - t, 3), "mode": f.get("lock_mode")})
    res["lock_commands"] = out
    return res

if __name__ == "__main__":
    ev = analyze(sys.argv[1])
    print(json.dumps({"events": [{"t": t, "kind": k, **f} for t, k, f in ev],
                      "timings": timings(ev)}, ensure_ascii=False, indent=1))
