package com.example.miaotrack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MiaoMiaoPacket {
    public static final byte NEW_SENSOR = 0x32;
    public static final byte NO_SENSOR  = 0x34;
    public static final byte START_PKT  = 0x28;
    public static final byte END_PKT    = 0x29;

    public int length;
    public int battery;
    public int fwVersion;
    public int hwVersion;
    public LibrePacket librePacket;

    public static MiaoMiaoPacket fromBytes(byte[] raw) {
        if (raw[0] != START_PKT) {
            throw new IllegalArgumentException("Start byte mismatch");
        }
        ByteBuffer bb = ByteBuffer.wrap(raw, 1, 2).order(ByteOrder.BIG_ENDIAN);
        int len = bb.getShort();
        if (raw.length != len) {
            throw new IllegalArgumentException("Length mismatch");
        }

        MiaoMiaoPacket pkt = new MiaoMiaoPacket();
        pkt.length = len;
        pkt.battery   = raw[13] & 0xFF;

        bb = ByteBuffer.wrap(raw, 14, 2).order(ByteOrder.BIG_ENDIAN);
        pkt.fwVersion = bb.getShort();

        bb = ByteBuffer.wrap(raw, 16, 2).order(ByteOrder.BIG_ENDIAN);
        pkt.hwVersion = bb.getShort();

        // payload: байты 18–(len-2)
        int payloadLen = len - 18 - 1;
        byte[] payload = new byte[payloadLen];
        System.arraycopy(raw, 18, payload, 0, payloadLen);

        pkt.librePacket = LibrePacket.fromBytes(payload);
        return pkt;
    }

    @Override
    public String toString() {
        return String.format("MiaoMiaoPacket[bat=%d fw=0x%X hw=0x%X %s]",
                battery, fwVersion, hwVersion, librePacket);
    }
}
