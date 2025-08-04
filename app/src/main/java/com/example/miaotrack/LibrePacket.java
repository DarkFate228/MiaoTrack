package com.example.miaotrack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class LibrePacket {
    public int indexTrend;
    public int indexHistory;
    public int minutes;
    public Date sensorStart;
    public List<float[]> history = new ArrayList<>();
    public List<float[]> trends  = new ArrayList<>();

    public static LibrePacket fromBytes(byte[] data) {
        // CRC1 (байты 0–23)
        boolean ok1 = Crc16.check(data, 0);

        // CRC2 (24–319)
        boolean ok2 = Crc16.check(data, 24);

        // CRC3 (320–343)
        boolean ok3 = Crc16.check(data, 320);

        // Индексы
        int idxT = data[26] & 0xFF;
        int idxH = data[27] & 0xFF;

        LibrePacket p = new LibrePacket();
        p.indexTrend   = idxT;
        p.indexHistory = idxH;

        // Количество минут назад
        ByteBuffer bb = ByteBuffer.wrap(data, 335, 2).order(ByteOrder.LITTLE_ENDIAN);
        p.minutes = bb.getShort();

        // Дата старта сенсора
        long now = System.currentTimeMillis();
        p.sensorStart = new Date(now - p.minutes * 60L * 1000L);

        // История (32 записей по 3×2 байта)
        int nh = 32, biasH = 142, step = 6;
        for (int i = 0; i < nh; i++) {
            int ird = (idxH - i - 1 + nh) % nh;
            int off = biasH + ird * step;
            float[] entry = new float[3];
            for (int j = 0; j < 3; j++) {
                int v = ((data[off + 2*j] & 0xFF) | ((data[off + 2*j+1] & 0xFF) << 8));
                entry[j] = v / 8.5f;
            }
            p.history.add(entry);
        }

        // Тренды (16 записей)
        int nt = 16, biasT = 46;
        for (int i = 0; i < nt; i++) {
            int ird = (idxT - i - 1 + nt) % nt;
            int off = biasT + ird * step;
            float[] entry = new float[3];
            for (int j = 0; j < 3; j++) {
                int v = ((data[off + 2*j] & 0xFF) | ((data[off + 2*j+1] & 0xFF) << 8));
                entry[j] = v / 8.5f;
            }
            p.trends.add(entry);
        }

        return p;
    }

    @Override
    public String toString() {
        return String.format("LibrePacket[start=%s min=%d histIdx=%d trIdx=%d]",
                sensorStart, minutes, indexHistory, indexTrend);
    }
}
