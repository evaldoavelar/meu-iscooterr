import importlib.util, io, json, pathlib, unittest
spec = importlib.util.spec_from_file_location("p", pathlib.Path(__file__).resolve().parents[2] / "scripts/analysis/ix7tap_parse.py")
p = importlib.util.module_from_spec(spec); spec.loader.exec_module(p)

def crc(b):
    c = 0xFFFF
    for x in b:
        c ^= x
        for _ in range(8):
            c = (c >> 1) ^ 0xA001 if c & 1 else c >> 1
    return c

def fr(cmd, tipo, *data, lock=False):
    body = [cmd, tipo, len(data), *data]; c = crc(body)
    b = [0x1A, 0xA1, *body, c & 0xff, c >> 8, 0x1F, 0xF1]
    h = "".join(f"{x:02x}" for x in b)
    if lock:  # mimic Tap masking: keep mode, mask the rest of data + CRC
        h = h[:12] + "**" * (len(data) - 1 + 2) + "1ff1"
        return f"{h} crc=lohi lock_data_len={len(data)}"
    return f"{h} crc=lohi"

def L(kind, t_ms, rest, uuid=None):
    u = f" uuid={uuid}" if uuid else ""
    return f"11-03 12:00:00.000 1 2 I IX7TAP  : {kind} t={int(t_ms*1e6)}{u} {rest}"

def m1(locked):
    return fr(0x20, 0x01, 0, (1 << 4) | (locked << 5), 0, 80, 1, 2, 3, 4, 5, 6, 7, 8)

class T(unittest.TestCase):
    def run_log(self, lines):
        return p.report(p.events(io.StringIO("\n".join(lines))))

    def test_lock_window(self):
        r = self.run_log([
            "noise line 11:22:33:44:55:66",
            L("TX", 0, fr(0x30, 0x02, 1)),
            L("RX", 40, m1(0), "ff61"),
            L("TX", 100, fr(0x32, 0x02, 1, 1, 2, 3, 4, lock=True)),
            L("WR", 110, "status=0 " + fr(0x32, 0x02, 1, 1, 2, 3, 4, lock=True), "ff62"),
            L("RX", 150, fr(0x32, 0x05, 0), "ff61"),
            L("RX", 400, m1(1), "ff61"),
        ])
        self.assertEqual(r["connect"], [{"connect_value": 1, "t_auth_ms": 40.0}])
        w = r["lock_windows"][0]
        self.assertEqual((w["mode"], w["lock_data_len"]), (1, 5))
        self.assertEqual((w["t_cmd_ms"], w["t_ack_ms"], w["t_state_ms"]), (10.0, 50.0, 300.0))
        self.assertNotIn("11:22:33", json.dumps(r))

    def test_error_and_no_state(self):
        r = self.run_log([
            L("RX", 0, m1(1)),
            L("TX", 10, fr(0x32, 0x02, 0, 1, 2, 3, 4, lock=True)),
            L("RX", 30, fr(0x32, 0x89, 0)),
            L("RX", 60, m1(1)),
        ])
        w = r["lock_windows"][0]
        self.assertEqual((w["t_ack_ms"], w["t_error_ms"], w["t_state_ms"]), (None, 20.0, None))

    def test_nonframe_and_two_frames(self):
        r = self.run_log([L("RX", 0, fr(0x10, 0x02) + " | nonframe_len=7")])
        self.assertEqual((r["events"], r["nonframe_events"]), (2, 1))

    def test_codex_r1_regressions(self):
        other = "11-03 12:00:00.000 1 2 I OtherTag: IX7TAP: WR t=1 uuid=AA:BB:CC:DD:EE:FF status=0"
        r = self.run_log([other])
        self.assertEqual((r["events"], r["uuids"]), (0, []))
        bad_ack = "badcrc cmd=32 tipo=05 len=1"
        bad_m1 = "badcrc cmd=20 tipo=03 len=12"
        r = self.run_log([L("RX", 0, m1(1)), L("TX", 10, fr(0x32, 0x02, 0, 1, 2, 3, 4, lock=True)),
                          L("RX", 20, bad_ack), L("RX", 30, bad_m1)])
        w = r["lock_windows"][0]
        # header-only ACK still times t_ack (flagged unverified); header-only Meter1 has no state
        self.assertEqual((w["t_ack_ms"], w["ack_crc_verified"], w["t_state_ms"]), (10.0, False, None))
        self.assertEqual(r["rx_header_only"], 2)
        r = self.run_log([L("TX", 10, fr(0x10, 0x02)), L("TX", 20, fr(0x32, 0x02, 0, 1, 2, 3, 4, lock=True)),
                          L("WR", 21, "status=0 " + fr(0x10, 0x02)),
                          L("WR", 30, "status=0 " + fr(0x32, 0x02, 0, 1, 2, 3, 4, lock=True))])
        self.assertEqual(r["lock_windows"][0]["t_cmd_ms"], 10.0)
        # a lost WR callback must not shift later matches
        r = self.run_log([L("TX", 0, fr(0x10, 0x02)), L("TX", 20, fr(0x32, 0x02, 0, 1, 2, 3, 4, lock=True)),
                          L("WR", 25, "status=0 " + fr(0x32, 0x02, 0, 1, 2, 3, 4, lock=True))])
        self.assertEqual(r["lock_windows"][0]["t_cmd_ms"], 5.0)
        # Codex R1-r2: two identical masked LOCKs, first WR lost
        lk = fr(0x32, 0x02, 0, 1, 2, 3, 4, lock=True)
        r = self.run_log([L("TX", 10, lk), L("TX", 100, lk), L("WR", 110, "status=0 " + lk)])
        a, b = r["lock_windows"]
        self.assertEqual((a["t_cmd_ms"], b["t_cmd_ms"], b["t_cmd_ambiguous"]), (None, 10.0, True))
        self.assertFalse(a["t_cmd_ambiguous"])
        r = self.run_log([L("TX", 0, fr(0x30, 0x02, 1)), L("TX", 1000, fr(0x30, 0x02, 1)), L("RX", 1040, m1(0))])
        self.assertEqual([c["t_auth_ms"] for c in r["connect"]], [None, 40.0])

if __name__ == "__main__":
    unittest.main()
