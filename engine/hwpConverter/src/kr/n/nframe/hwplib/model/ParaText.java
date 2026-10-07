package kr.n.nframe.hwplib.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class ParaText {
    public byte[] rawBytes; // 컨트롤 문자를 포함한 UTF-16LE 인코딩 텍스트

    public ParaText() {
    }

    public ParaText(byte[] rawBytes) {
        this.rawBytes = rawBytes;
    }

    /**
     * 일반 텍스트를 UTF-16LE로 변환하고 문단 끝 마커(0x000D)를 덧붙인다.
     */
    public static ParaText fromString(String text) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_16LE);
        // 문단 끝 마커 0x000D를 UTF-16LE로 덧붙임 (2 byte: 0x0D, 0x00)
        ByteBuffer buf = ByteBuffer.allocate(textBytes.length + 2);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(textBytes);
        buf.putShort((short) 0x000D);
        ParaText pt = new ParaText();
        pt.rawBytes = buf.array();
        return pt;
    }
}
