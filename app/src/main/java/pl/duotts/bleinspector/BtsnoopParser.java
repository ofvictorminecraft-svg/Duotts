package pl.duotts.bleinspector;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only btsnoop/HCI (DLT 1002) decoder, BLE ATT only. */
public final class BtsnoopParser {
    private static final long UNIX_OFFSET_US = 0x00dcddb30f2f8000L;
    private static final int MAX_RECORD = 1_048_576;
    private static final int MAX_EVENTS = 20_000;
    private static final byte[] MAGIC = {'b','t','s','n','o','o','p',0};

    public static class Event {
        public final long timeMs;
        public final int connectionHandle;
        public final int attributeHandle;
        public final boolean outgoing;
        public final String operation;
        public final String dataHex;
        public String deviceAddress = "nieznany";
        public String characteristicUuid = "";
        Event(long timeMs, int connectionHandle, int attributeHandle, boolean outgoing,
              String operation, byte[] value) {
            this.timeMs = timeMs;
            this.connectionHandle = connectionHandle;
            this.attributeHandle = attributeHandle;
            this.outgoing = outgoing;
            this.operation = operation;
            this.dataHex = hex(value);
        }
        public boolean isWrite() {
            return outgoing && (operation.equals("WRITE_REQ") || operation.equals("WRITE_CMD")
                    || operation.equals("SIGNED_WRITE") || operation.equals("PREP_WRITE"));
        }
        public String signature() { return attributeHandle + "|" + operation + "|" + dataHex; }
    }

    public static class Capture {
        public final List<Event> events = new ArrayList<>();
        public final Map<Integer,String> uuidsByHandle = new HashMap<>();
        public final Map<Integer,String> addressesByConnection = new HashMap<>();
        public int packets, aclPackets, writePackets, ignoredPackets;
        public boolean truncated;
        public long firstTimeMs = Long.MAX_VALUE, lastTimeMs = 0;
        public String sourceName = "";
    }

    private static class Assembly {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final int expected;
        final long timestamp;
        final boolean outgoing;
        Assembly(int expected, long timestamp, boolean outgoing) {
            this.expected = expected;
            this.timestamp = timestamp;
            this.outgoing = outgoing;
        }
    }

    private BtsnoopParser() {}

    public static Capture parse(InputStream input) throws IOException {
        byte[] header = new byte[16];
        readAll(input, header, 0, 16);
        for (int i = 0; i < 8; i++)
            if (header[i] != MAGIC[i]) throw new IOException("To nie jest plik btsnoop_hci.log (brak nagłówka BTSnoop).");
        int version = be32(header, 8), dlt = be32(header, 12);
        if (version != 1 || dlt != 1002)
            throw new IOException("Format BTSnoop nieobsługiwany: version=" + version + ", datalink=" + dlt + ". Wymagany HCI UART (1002).");
        Capture c = new Capture();
        Map<Integer, Assembly> assemblies = new HashMap<>();
        byte[] record = new byte[24];
        while (true) {
            int b = input.read();
            if (b < 0) break;
            record[0] = (byte)b;
            readAll(input, record, 1, 23);
            int len = be32(record, 4);
            int flags = be32(record, 8);
            if (len < 0 || len > MAX_RECORD) throw new IOException("Niedozwolony rozmiar pakietu: " + len);
            byte[] raw = new byte[len];
            readAll(input, raw, 0, len);
            c.packets++;
            long t = (be64(record, 16) - UNIX_OFFSET_US) / 1000;
            if (t > 1_500_000_000_000L && t < 4_000_000_000_000L) {
                c.firstTimeMs = Math.min(c.firstTimeMs, t);
                c.lastTimeMs = Math.max(c.lastTimeMs, t);
            }
            if (len == 0) continue;
            try {
                int type = raw[0] & 0xff;
                if (type == 4) hciEvent(c, raw);
                else if (type == 2) {
                    c.aclPackets++;
                    acl(c, assemblies, raw, t, (flags & 1) == 0);
                }
            } catch (IndexOutOfBoundsException badPacket) { c.ignoredPackets++; }
            if (c.events.size() >= MAX_EVENTS) { c.truncated = true; break; }
        }
        for (Event e : c.events) {
            e.deviceAddress = c.addressesByConnection.getOrDefault(e.connectionHandle, "nieznany");
            e.characteristicUuid = c.uuidsByHandle.getOrDefault(e.attributeHandle, "");
        }
        return c;
    }
    private static void hciEvent(Capture c, byte[] raw) {
        if (raw.length < 4) return;
        int code = raw[1] & 255;
        if (code == 0x3e && raw.length >= 15) {
            int subevent = raw[3] & 255;
            if ((subevent == 0x01 || subevent == 0x0a) && (raw[4] & 255) == 0) {
                c.addressesByConnection.put(le16(raw, 5) & 0xfff, address(raw, 9));
            }
        } else if (code == 0x03 && raw.length >= 12 && (raw[3] & 255) == 0) {
            c.addressesByConnection.put(le16(raw, 4) & 0xfff, address(raw, 6));
        } else if (code == 0x05 && raw.length >= 7) {
            // Handle may be reused later, avoid attaching old device address.
            c.addressesByConnection.remove(le16(raw, 4) & 0xfff);
        }
    }
    private static String address(byte[] b, int off) {
        if (b.length < off + 6) return "nieznany";
        StringBuilder out = new StringBuilder();
        for (int i = 5; i >= 0; --i) {
            if (out.length() != 0) out.append(':');
            out.append(String.format(Locale.US, "%02X", b[off + i] & 255));
        }
        return out.toString();
    }

    private static void acl(Capture c, Map<Integer,Assembly> assemblies, byte[] raw, long time, boolean outgoing) {
        if (raw.length < 9) return;
        int h = le16(raw, 1);
        int connection = h & 0xfff;
        int boundary = (h >>> 12) & 3;
        int aclLen = le16(raw, 3);
        if (raw.length - 5 < aclLen) return;
        int key = 2 * connection + (outgoing ? 1 : 0);
        if (boundary == 1) {
            Assembly fragment = assemblies.get(key);
            if (fragment == null) return;
            fragment.bytes.write(raw, 5, aclLen);
            if (fragment.bytes.size() >= fragment.expected) {
                l2cap(c, connection, fragment.outgoing, fragment.timestamp, fragment.bytes.toByteArray());
                assemblies.remove(key);
            }
            return;
        }
        if (aclLen < 4) return;
        int expected = le16(raw, 5) + 4;
        if (expected < 5 || expected > 65539) return;
        if (aclLen >= expected) {
            byte[] complete = new byte[expected];
            System.arraycopy(raw, 5, complete, 0, expected);
            l2cap(c, connection, outgoing, time, complete);
        } else {
            Assembly fragment = new Assembly(expected, time, outgoing);
            fragment.bytes.write(raw, 5, aclLen);
            assemblies.put(key, fragment);
        }
    }

    private static void l2cap(Capture c, int connection, boolean outgoing, long time, byte[] b) {
        if (b.length < 5 || le16(b, 2) != 4) return; // BLE ATT fixed channel
        int n = le16(b, 0);
        if (n + 4 > b.length || n < 1) return;
        int opcode = b[4] & 255;
        if ((opcode == 0x12 || opcode == 0x52 || opcode == 0xd2 ||
                opcode == 0x16 || opcode == 0x1b || opcode == 0x1d) && n >= 3) {
            int handle = le16(b, 5);
            String kind;
            switch (opcode) {
                case 0x12: kind = "WRITE_REQ"; break;
                case 0x52: kind = "WRITE_CMD"; break;
                case 0xd2: kind = "SIGNED_WRITE"; break;
                case 0x16: kind = "PREP_WRITE"; break;
                case 0x1b: kind = "NOTIFY"; break;
                default: kind = "INDICATE";
            }
            int valueOffset = opcode == 0x16 ? 9 : 7;
            if (4 + n < valueOffset) return;
            int valueSize = 4 + n - valueOffset;
            byte[] value = new byte[valueSize];
            System.arraycopy(b, valueOffset, value, 0, valueSize);
            Event e = new Event(time, connection, handle, outgoing, kind, value);
            c.events.add(e);
            if (e.isWrite()) c.writePackets++;
        } else if (opcode == 0x09 && n >= 2 && !outgoing) {
            // ATT Read By Type Response: declaration handle, properties, value handle, UUID.
            int size = b[5] & 255;
            if (size != 7 && size != 21) return;
            for (int off = 6; off + size <= 4 + n; off += size) {
                int handle = le16(b, off + 3);
                c.uuidsByHandle.put(handle, uuid(b, off + 5, size - 5));
            }
        } else if (opcode == 0x05 && n >= 2 && !outgoing) {
            int mode = b[5] & 255;
            int size = mode == 1 ? 4 : mode == 2 ? 18 : 0;
            if (size == 0) return;
            for (int off = 6; off + size <= 4 + n; off += size)
                c.uuidsByHandle.put(le16(b, off), uuid(b, off + 2, size - 2));
        }
    }
    private static String uuid(byte[] data, int off, int len) {
        if (len == 2) return String.format(Locale.US, "0000%04x-0000-1000-8000-00805f9b34fb", le16(data, off));
        if (len != 16) return "";
        StringBuilder s = new StringBuilder();
        for (int i = 15; i >= 0; --i) s.append(String.format(Locale.US, "%02x", data[off + i] & 255));
        String v = s.toString();
        return v.substring(0, 8) + "-" + v.substring(8, 12) + "-" + v.substring(12, 16) + "-" + v.substring(16, 20) + "-" + v.substring(20);
    }
    public static String hex(byte[] b) {
        char[] digits = "0123456789ABCDEF".toCharArray();
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[2 * i] = digits[(b[i] >> 4) & 15];
            out[2 * i + 1] = digits[b[i] & 15];
        }
        return new String(out);
    }
    private static int le16(byte[] b, int i) { return (b[i] & 255) | ((b[i+1] & 255) << 8); }
    private static int be32(byte[] b, int i) {
        return ((b[i] & 255) << 24) | ((b[i+1] & 255) << 16) | ((b[i+2] & 255) << 8) | (b[i+3] & 255);
    }
    private static long be64(byte[] b, int i) {
        return ((long)be32(b, i) << 32) | (be32(b, i+4) & 0xffffffffL);
    }
    private static void readAll(InputStream input, byte[] b, int offset, int n) throws IOException {
        while (n > 0) {
            int r = input.read(b, offset, n);
            if (r < 0) throw new EOFException("Niekompletny plik BTSnoop.");
            if (r == 0) {
                int value = input.read();
                if (value < 0) throw new EOFException("Niekompletny plik BTSnoop.");
                b[offset] = (byte)value;
                r = 1;
            }
            offset += r;
            n -= r;
        }
    }
}
