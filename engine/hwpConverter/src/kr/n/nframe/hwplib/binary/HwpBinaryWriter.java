package kr.n.nframe.hwplib.binary;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * HWP 5.0 포맷 데이터를 위한 little-endian 바이너리 writer.
 * ByteArrayOutputStream을 래핑하여 HWP 기본 타입에 맞는 타입별 쓰기 메서드를 제공한다.
 */
public class HwpBinaryWriter {

    private final ByteArrayOutputStream out;

    public HwpBinaryWriter() {
        this.out = new ByteArrayOutputStream();
    }

    public HwpBinaryWriter(int initialCapacity) {
        this.out = new ByteArrayOutputStream(initialCapacity);
    }

    /** 1 byte unsigned (UINT8) 쓰기. */
    public void writeUInt8(int v) {
        out.write(v & 0xFF);
    }

    /** 1 byte signed (INT8) 쓰기. */
    public void writeInt8(int v) {
        out.write(v & 0xFF);
    }

    /** 2 byte little-endian unsigned (UINT16) 쓰기. */
    public void writeUInt16(int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
    }

    /** 2 byte little-endian signed (INT16) 쓰기. */
    public void writeInt16(int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
    }

    /** 4 byte little-endian unsigned (UINT32) 쓰기. */
    public void writeUInt32(long v) {
        out.write((int) (v & 0xFF));
        out.write((int) ((v >>> 8) & 0xFF));
        out.write((int) ((v >>> 16) & 0xFF));
        out.write((int) ((v >>> 24) & 0xFF));
    }

    /** 4 byte little-endian signed (INT32) 쓰기. */
    public void writeInt32(int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }

    /** COLORREF 값 쓰기 (4 byte, UINT32와 동일한 레이아웃). */
    public void writeColorRef(long rgb) {
        writeUInt32(rgb);
    }

    /** HWPUNIT 값 쓰기 (INT32). */
    public void writeHwpUnit(int v) {
        writeInt32(v);
    }

    /** HWPUNIT16 값 쓰기 (INT16). */
    public void writeHwpUnit16(int v) {
        writeInt16(v);
    }

    /**
     * 문자열을 UTF-16LE로 쓴다 (문자당 2 byte).
     * BOM 접두사 없음. null 종결자 없음.
     */
    public void writeUtf16Le(String s) {
        if (s == null || s.isEmpty()) {
            return;
        }
        byte[] encoded = s.getBytes(StandardCharsets.UTF_16LE);
        out.write(encoded, 0, encoded.length);
    }

    /** WCHAR 한 글자 쓰기 (2 byte little-endian). */
    public void writeWChar(char c) {
        out.write(c & 0xFF);
        out.write((c >>> 8) & 0xFF);
    }

    /** 원시 byte 쓰기. */
    public void writeBytes(byte[] data) {
        out.write(data, 0, data.length);
    }

    /** count개의 0 byte를 패딩으로 쓰기. */
    public void writePad(int count) {
        for (int i = 0; i < count; i++) {
            out.write(0);
        }
    }

    /** 누적된 모든 byte 반환. */
    public byte[] toByteArray() {
        return out.toByteArray();
    }

    /** 현재 byte 수 반환. */
    public int size() {
        return out.size();
    }
}
