import struct, subprocess, sys, tempfile, os, json, unittest
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts", "analysis"))
import btsnoop_sanitize as b

def frame(cmd, typ, data=b""):
    body = bytes([cmd, typ, len(data)]) + data
    c = b.crc16_modbus(body)
    return b"\x1a\xa1" + body + bytes([c & 255, c >> 8]) + b"\x1f\xf1"

def rec(ts_us, flags, pkt):
    return struct.pack(">IIIIq", len(pkt), len(pkt), flags, 0, ts_us + b.EPOCH_DELTA_US) + pkt

def acl_att(opcode, handle, val):
    att = bytes([opcode]) + struct.pack("<H", handle) + val
    l2 = struct.pack("<HH", len(att), 4) + att
    return b"\x02" + struct.pack("<HH", 0x0040, len(l2)) + l2

class T(unittest.TestCase):
    def test_pin_never_leaks_and_timings(self):
        pin = bytes([1, 2, 3, 4])
        f_cmd = frame(0x32, 2, bytes([1]) + pin)
        def m1(lock):
            data = bytearray(12); data[1] = 0b00110000 if lock else 0b00010000; data[3] = 77
            return frame(0x20, 0, bytes(data))
        recs = [rec(1_000_000, 1, b"\x04\x3e\x0a\x02"),
                rec(1_500_000, 0, b"\x01\x0c\x20\x02\x01\x00"),
                rec(2_000_000, 0, b"\x01\x0d\x20\x00"),
                rec(2_300_000, 1, b"\x04\x3e\x0a\x01\x00"),
                rec(3_000_000, 1, acl_att(0x1B, 0x10, m1(False))),
                rec(4_000_000, 0, acl_att(0x52, 0x11, f_cmd)),
                rec(4_050_000, 1, acl_att(0x1B, 0x10, frame(0x32, 5, b""))),
                rec(4_400_000, 1, acl_att(0x1B, 0x10, m1(True)))]
        with tempfile.NamedTemporaryFile(delete=False) as fh:
            fh.write(b"btsnoop\0" + struct.pack(">II", 1, 1002) + b"".join(recs)); p = fh.name
        out = subprocess.run([sys.executable, "-I", b.__file__, p], capture_output=True, text=True, check=True).stdout
        os.unlink(p)
        self.assertNotIn("1, 2, 3, 4", out); self.assertNotIn('"pin": ', out)
        j = json.loads(out)
        self.assertAlmostEqual(j["timings"]["t_scan"], 0.5, places=2)
        self.assertAlmostEqual(j["timings"]["t_conn"], 0.3, places=2)
        lk = j["timings"]["lock_commands"][0]
        self.assertEqual(lk["mode"], 1)
        self.assertAlmostEqual(lk["t_ack"], 0.05, places=2)
        self.assertAlmostEqual(lk["t_state"], 0.4, places=2)
        self.assertTrue(all(e.get("crc_ok", True) for e in j["events"]))
        cmd = [e for e in j["events"] if e.get("cmd") == "0x32" and e["kind"] == "ATT_TX"][0]
        self.assertEqual((cmd["pin_present"], cmd["pin_len"]), (True, 4))


    def _run(self, recs):
        with tempfile.NamedTemporaryFile(delete=False) as fh:
            fh.write(b"btsnoop\0" + struct.pack(">II", 1, 1002) + b"".join(recs)); p = fh.name
        out = subprocess.run([sys.executable, "-I", b.__file__, p], capture_output=True, text=True, check=True).stdout
        os.unlink(p); return json.loads(out)

    def test_error_not_attributed_as_ack_and_window(self):
        c = frame(0x32, 2, bytes([0]) + bytes([9, 9, 9, 9]))
        j = self._run([rec(1_000_000, 0, acl_att(0x52, 0x11, c)),
                       rec(1_100_000, 1, acl_att(0x1B, 0x10, frame(0x32, 0x89))),
                       rec(2_000_000, 0, acl_att(0x52, 0x11, c)),
                       rec(2_100_000, 1, acl_att(0x1B, 0x10, frame(0x32, 5)))])
        a, b2 = j["timings"]["lock_commands"]
        self.assertIsNone(a["t_ack"]); self.assertAlmostEqual(a["t_error"], 0.1, places=2)
        self.assertAlmostEqual(b2["t_ack"], 0.1, places=2)

    def test_t_auth(self):
        j = self._run([rec(3_000_000, 1, b"\x04\x3e\x0a\x01\x00"),
                       rec(4_000_000, 0, acl_att(0x52, 0x11, frame(0x30, 2, b"\x01"))),
                       rec(4_200_000, 1, acl_att(0x1B, 0x10, frame(0x30, 5, b"\x01")))])
        self.assertAlmostEqual(j["timings"]["t_auth"], 0.2, places=2)

    def test_short_meter1_is_not_telemetry(self):
        j = self._run([rec(1_000_000, 1, acl_att(0x1B, 0x10, frame(0x20, 0, b"")))])
        e = [x for x in j["events"] if x.get("cmd") == "0x20"][0]
        self.assertNotIn("lock_status", e); self.assertNotIn("battery_pct", e)

if __name__ == "__main__":
    unittest.main()
