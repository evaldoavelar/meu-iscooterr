package ix7;

import android.bluetooth.BluetoothGattCharacteristic;
import android.os.SystemClock;
import android.util.Log;

/**
 * Passive BLE tap for the Sprint 2 capture (docs/research/test-plan.md).
 * Logs GATT traffic of the original app to logcat (tag IX7TAP) with sensitive
 * fields masked at the source: PIN digits and CRC of LOCK (0x32), and the
 * whole payload + CRC of 0x67 (lock password), 0x61 (SN), 0x3A (set SN).
 * Bytes that do not parse as a complete 1A A1 .. 1F F1 frame are logged as
 * length only. Never throws into the host app.
 */
public final class Tap {
    private static final String TAG = "IX7TAP";

    private Tap() {}

    /** Called before BluetoothGattCharacteristic.setValue in the write path. */
    public static void tx(byte[] v) {
        log("TX", null, v, true);
    }

    /** Called at the start of BluetoothGattCallback.onCharacteristicChanged. */
    public static void rx(BluetoothGattCharacteristic c) {
        try {
            log("RX", uuid(c), c == null ? null : c.getValue(), false);
        } catch (Throwable ignored) {
        }
    }

    /** Called at the start of BluetoothGattCallback.onCharacteristicWrite. */
    public static void wr(BluetoothGattCharacteristic c, int status) {
        try {
            Log.i(TAG, "WR t=" + SystemClock.elapsedRealtimeNanos() + " uuid=" + uuid(c) + " status=" + status
                    + " " + describe(c == null ? null : c.getValue(), true));
        } catch (Throwable ignored) {
        }
    }

    private static String uuid(BluetoothGattCharacteristic c) {
        if (c == null || c.getUuid() == null) return "null";
        String s = c.getUuid().toString();
        return s.length() >= 8 ? s.substring(4, 8) : s;
    }

    private static void log(String dir, String uuid, byte[] v, boolean tx) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(dir).append(" t=").append(SystemClock.elapsedRealtimeNanos());
            if (uuid != null) sb.append(" uuid=").append(uuid);
            sb.append(' ').append(describe(v, tx));
            Log.i(TAG, sb.toString());
        } catch (Throwable ignored) {
        }
    }

    static String describe(byte[] v, boolean tx) {
        if (v == null) return "null";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int frames = 0;
        while (i < v.length) {
            int end = frameEnd(v, i);
            if (end < 0) break;
            if (frames > 0) sb.append(" | ");
            sb.append(frame(v, i, end, tx));
            frames++;
            i = end;
        }
        if (i < v.length) {
            if (frames > 0) sb.append(" | ");
            sb.append("nonframe_len=").append(v.length - i);
        }
        return sb.toString();
    }

    /** Returns the index after the frame starting at off, or -1 if none is complete. */
    private static int frameEnd(byte[] v, int off) {
        if (v.length - off < 9) return -1;
        if ((v[off] & 0xff) != 0x1A || (v[off + 1] & 0xff) != 0xA1) return -1;
        int len = v[off + 4] & 0xff;
        int end = off + 5 + len + 2 + 2;
        if (end > v.length) return -1;
        if ((v[end - 2] & 0xff) != 0x1F || (v[end - 1] & 0xff) != 0xF1) return -1;
        return end;
    }

    private static String frame(byte[] v, int off, int end, boolean tx) {
        int cmd = v[off + 2] & 0xff;
        int len = v[off + 4] & 0xff;
        int dataOff = off + 5;
        int crcOff = dataOff + len;
        // Only CRC-valid frames are ever printed. TX uses CRC-16/Modbus over CMD..DATA
        // (confirmed on the device). RX uses an algorithm the app never checks; try a few
        // standard candidates and log only the NAME of the one that matches. If none
        // matches, CMD/LEN may be corrupted, so print only the header.
        String crcState = crcMatch(v, off, len, crcOff, tx);
        // Owner decision 2026-11-03: residual PIN-leak risk accepted (PIN will be changed after
        // Sprint 2). Frames with an unmatched CRC are printed with the usual masks and crc=bad,
        // because the RX CRC algorithm is unknown and telemetry is needed for t_state.
        if (crcState == null) crcState = "bad";
        boolean embedded = false;
        for (int k = off + 2; k + 2 < end; k++) {
            if ((v[k] & 0xff) == 0x1A && (v[k + 1] & 0xff) == 0xA1) {
                int c2 = v[k + 2] & 0xff;
                if (c2 == 0x32 || c2 == 0x67 || c2 == 0x61 || c2 == 0x3A) embedded = true;
            }
        }
        if (embedded) {
            return "embedded cmd=" + hex2(cmd) + " tipo=" + hex2(v[off + 3] & 0xff) + " len=" + len;
        }

        boolean lock = cmd == 0x32;
        boolean secret = cmd == 0x67 || cmd == 0x61 || cmd == 0x3A;
        StringBuilder sb = new StringBuilder();
        for (int k = off; k < end; k++) {
            boolean inData = k >= dataOff && k < crcOff;
            boolean inCrc = k >= crcOff && k < crcOff + 2;
            boolean mask = (secret && (inData || inCrc))
                    || (lock && ((inData && k > dataOff) || inCrc));
            if (mask) {
                sb.append("**");
            } else {
                sb.append(Character.forDigit((v[k] >> 4) & 0xf, 16));
                sb.append(Character.forDigit(v[k] & 0xf, 16));
            }
        }
        sb.append(" crc=").append(crcState);
        if (lock) sb.append(" lock_data_len=").append(len);
        return sb.toString();
    }

    /** Name of the CRC candidate that matches the frame, or null. TX: only Modbus over CMD..DATA. */
    static String crcMatch(byte[] v, int off, int len, int crcOff, boolean tx) {
        int lo = v[crcOff] & 0xff;
        int hi = v[crcOff + 1] & 0xff;
        int n = 3 + len;
        int[][] cands = tx
                ? new int[][] {{0, 2}}
                : new int[][] {{0, 2}, {0, 0}, {1, 2}, {1, 0}, {2, 2}, {2, 0}, {3, 2}, {3, 0}};
        String[] names = {"modbus", "xmodem", "ccitt", "sum16"};
        for (int[] c : cands) {
            int start = off + c[1];
            int crc = calc(c[0], v, start, n + (2 - c[1]));
            String range = c[1] == 2 ? "" : "_full";
            if (lo == (crc & 0xff) && hi == ((crc >> 8) & 0xff)) return (tx ? "lohi" : names[c[0]] + range + "_lohi");
            if (hi == (crc & 0xff) && lo == ((crc >> 8) & 0xff)) return (tx ? "hilo" : names[c[0]] + range + "_hilo");
        }
        return null;
    }

    private static int calc(int alg, byte[] v, int off, int n) {
        switch (alg) {
            case 0: return crc16(v, off, n);
            case 1: return ccitt(v, off, n, 0x0000);
            case 2: return ccitt(v, off, n, 0xFFFF);
            default: {
                int s = 0;
                for (int k = off; k < off + n; k++) s += v[k] & 0xff;
                return s & 0xffff;
            }
        }
    }

    private static int ccitt(byte[] v, int off, int n, int init) {
        int crc = init;
        for (int k = off; k < off + n; k++) {
            crc ^= (v[k] & 0xff) << 8;
            for (int b = 0; b < 8; b++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1);
                crc &= 0xffff;
            }
        }
        return crc;
    }

    private static String hex2(int b) {
        return "" + Character.forDigit((b >> 4) & 0xf, 16) + Character.forDigit(b & 0xf, 16);
    }

    /** CRC-16/Modbus: init 0xFFFF, reflected poly 0xA001. */
    private static int crc16(byte[] v, int off, int n) {
        int crc = 0xFFFF;
        for (int k = off; k < off + n; k++) {
            crc ^= v[k] & 0xff;
            for (int b = 0; b < 8; b++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
            }
        }
        return crc & 0xffff;
    }
}
