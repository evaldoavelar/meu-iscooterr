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
        log("TX", null, v);
    }

    /** Called at the start of BluetoothGattCallback.onCharacteristicChanged. */
    public static void rx(BluetoothGattCharacteristic c) {
        try {
            log("RX", uuid(c), c == null ? null : c.getValue());
        } catch (Throwable ignored) {
        }
    }

    /** Called at the start of BluetoothGattCallback.onCharacteristicWrite. */
    public static void wr(BluetoothGattCharacteristic c, int status) {
        try {
            Log.i(TAG, "WR t=" + SystemClock.elapsedRealtimeNanos() + " uuid=" + uuid(c) + " status=" + status);
        } catch (Throwable ignored) {
        }
    }

    private static String uuid(BluetoothGattCharacteristic c) {
        if (c == null || c.getUuid() == null) return "null";
        String s = c.getUuid().toString();
        return s.length() >= 8 ? s.substring(4, 8) : s;
    }

    private static void log(String dir, String uuid, byte[] v) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(dir).append(" t=").append(SystemClock.elapsedRealtimeNanos());
            if (uuid != null) sb.append(" uuid=").append(uuid);
            sb.append(' ').append(describe(v));
            Log.i(TAG, sb.toString());
        } catch (Throwable ignored) {
        }
    }

    static String describe(byte[] v) {
        if (v == null) return "null";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int frames = 0;
        while (i < v.length) {
            int end = frameEnd(v, i);
            if (end < 0) break;
            if (frames > 0) sb.append(" | ");
            sb.append(frame(v, i, end));
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

    private static String frame(byte[] v, int off, int end) {
        int cmd = v[off + 2] & 0xff;
        int len = v[off + 4] & 0xff;
        int dataOff = off + 5;
        int crcOff = dataOff + len;
        int crc = crc16(v, off + 2, 3 + len);
        int lo = v[crcOff] & 0xff;
        int hi = v[crcOff + 1] & 0xff;
        boolean crcLoHi = lo == (crc & 0xff) && hi == ((crc >> 8) & 0xff);
        boolean crcHiLo = hi == (crc & 0xff) && lo == ((crc >> 8) & 0xff);
        String crcState = crcLoHi ? "lohi" : (crcHiLo ? "hilo" : "bad");

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
