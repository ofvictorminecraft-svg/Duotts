package pl.duotts.bleinspector;
import java.io.*;

/** No JUnit dependency: javac + java test for a minimal synthetic ATT Write packet. */
public class ParserSmokeTest {
    public static void main(String[] args) throws Exception {
        byte[] header = {'b','t','s','n','o','o','p',0,0,0,0,1,0,0,3,(byte)234};
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(header);
        byte[] packet = {2,1,0,9,0,5,0,4,0,0x12,0x25,0,0x55,0x66};
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(packet.length);
        out.writeInt(packet.length);
        out.writeInt(0);
        out.writeInt(0);
        out.writeLong(0x00dcddb30f2f8000L + 1700000000000000L);
        out.write(packet);
        BtsnoopParser.Capture capture = BtsnoopParser.parse(new ByteArrayInputStream(stream.toByteArray()));
        if (capture.writePackets != 1 || capture.events.size() != 1)
            throw new AssertionError("Expected 1 ATT WRITE");
        BtsnoopParser.Event e = capture.events.get(0);
        if (!e.isWrite() || e.attributeHandle != 0x25 || !e.dataHex.equals("5566"))
            throw new AssertionError("Incorrect ATT decoding: " + e.dataHex);
        System.out.println("PASS: decoded ATT WRITE handle=0x25 hex=5566");
    }
}
