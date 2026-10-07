package kr.n.nframe.hwplib.binary;

/**
 * HWP 5.0 데이터 레코드를 인코딩한다.
 *
 * 각 레코드는 다음과 같이 패킹된 32-bit 헤더를 가진다:
 *   bits  0- 9 : TagID  (10 bits)
 *   bits 10-19 : Level  (10 bits)
 *   bits 20-31 : Size   (12 bits)
 *
 * 페이로드가 4095 byte 이상이면 size 필드는 0xFFF로 설정되고,
 * 실제 크기는 헤더 직후 4-byte little-endian DWORD로 덧붙여진다.
 */
public class RecordWriter {

    private static final int MAX_INLINE_SIZE = 0xFFF; // 4095

    /**
     * 단일 레코드 페이로드 상한 (128 MB).
     * HWP 5.0 spec 은 extended-size 필드를 UINT32 까지 허용하지만,
     * 실제 정상 문서의 단일 레코드는 수 MB 이하이고, Integer.MAX_VALUE
     * 근처의 페이로드는 {@code totalLen} 산술에서 signed overflow 를
     * 유발하여 음수 capacity 로 배열 할당이 실패한다.
     */
    private static final int MAX_PAYLOAD_BYTES = 128 * 1024 * 1024;

    private RecordWriter() {
        // 유틸리티 클래스
    }

    /**
     * 32-bit 레코드 헤더 값을 인코딩한다.
     *
     * @param tagId 태그 식별자 (10 bits, 0-1023)
     * @param level 중첩 레벨 (10 bits, 0-1023)
     * @param size  페이로드 크기 (12 bits, 0-4095)
     * @return 패킹된 32-bit 헤더
     */
    public static int encodeHeader(int tagId, int level, int size) {
        return (tagId & 0x3FF)
                | ((level & 0x3FF) << 10)
                | ((size & 0xFFF) << 20);
    }

    /**
     * 완전한 레코드를 만든다 (헤더 + 선택적 확장 크기 + 페이로드).
     *
     * @param tagId 태그 식별자
     * @param level 중첩 레벨
     * @param data  페이로드 byte (비어 있을 수 있으나 null은 안 됨)
     * @return byte 배열로 인코딩된 레코드
     */
    public static byte[] buildRecord(int tagId, int level, byte[] data) {
        if (data == null) throw new IllegalArgumentException("record payload is null");
        int dataLen = data.length;
        if (dataLen < 0 || dataLen > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("record payload size out of range: " + dataLen);
        }
        boolean extended = dataLen >= MAX_INLINE_SIZE;

        int sizeField = extended ? MAX_INLINE_SIZE : dataLen;
        int header = encodeHeader(tagId, level, sizeField);

        // signed overflow 방어: 아래 세 항의 합이 Integer.MAX_VALUE 를 넘을 수 없도록
        // Math.addExact 로 감싸 즉시 ArithmeticException 발생시킴.
        int totalLen = Math.addExact(Math.addExact(4, extended ? 4 : 0), dataLen);
        byte[] record = new byte[totalLen];
        int pos = 0;

        // 헤더 쓰기 (4 byte LE)
        record[pos++] = (byte) (header & 0xFF);
        record[pos++] = (byte) ((header >>> 8) & 0xFF);
        record[pos++] = (byte) ((header >>> 16) & 0xFF);
        record[pos++] = (byte) ((header >>> 24) & 0xFF);

        // 필요 시 확장 크기 DWORD 쓰기 (4 byte LE)
        if (extended) {
            record[pos++] = (byte) (dataLen & 0xFF);
            record[pos++] = (byte) ((dataLen >>> 8) & 0xFF);
            record[pos++] = (byte) ((dataLen >>> 16) & 0xFF);
            record[pos++] = (byte) ((dataLen >>> 24) & 0xFF);
        }

        // 페이로드 쓰기
        System.arraycopy(data, 0, record, pos, dataLen);

        return record;
    }
}
