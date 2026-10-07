package kr.n.nframe.hwplib.binary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * raw deflate(zlib/gzip 헤더 없음) 방식으로 byte 배열을 압축/해제한다.
 * HWP 5.0 본문 스트림이 이 raw deflate 포맷을 사용한다.
 */
public class ZlibCompressor {

    private static final int BUFFER_SIZE = 4096;

    /**
     * 해제 결과 바이트 수 상한. 악의적으로 부풀린 입력(zip bomb)에 대한 방어.
     * HWP 본문/DocInfo 스트림 실측은 수 MB 수준이므로 256 MB 는 정상 한도를 충분히 초과한다.
     */
    private static final long MAX_DECOMPRESSED_BYTES = 256L * 1024 * 1024;

    /**
     * 입력 대비 허용 압축비 상한. zip bomb 은 대개 1:1000 이상.
     * 정상 HWP 스트림은 대체로 1:3~1:10 수준이므로 1:200 은 안전 마진.
     * 초기 바이트(압축 해제 결과가 아직 작을 때) 는 고정 여유값(FLOOR) 를 더해
     * 작은 입력이 정상적으로 해제되는 케이스까지 커버한다.
     */
    private static final long MAX_COMPRESSION_RATIO = 200L;
    private static final long RATIO_FLOOR_BYTES = 1024L * 1024; // 1 MB

    private ZlibCompressor() {
        // 유틸리티 클래스
    }

    /**
     * raw deflate(nowrap = true, zlib 헤더 없음)로 데이터를 압축한다.
     *
     * @param data 압축되지 않은 입력
     * @return 압축된 byte
     */
    public static byte[] compress(byte[] data) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            deflater.setInput(data);
            deflater.finish();

            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
            byte[] buf = new byte[BUFFER_SIZE];
            while (!deflater.finished()) {
                int count = deflater.deflate(buf);
                out.write(buf, 0, count);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /**
     * raw deflate(nowrap = true)로 압축된 데이터를 해제한다.
     *
     * <p>보안 방어:
     * <ul>
     *   <li>해제 결과가 {@link #MAX_DECOMPRESSED_BYTES} 를 초과하면 즉시 중단 (zip bomb)</li>
     *   <li>입력 대비 압축비가 {@link #MAX_COMPRESSION_RATIO} 를 초과하면 중단</li>
     *   <li>{@code Inflater.finished()} 로 정상 종료되지 않으면 예외 (truncated stream 조용한 부분 해제 차단)</li>
     * </ul>
     *
     * @param data 압축된 입력
     * @return 해제된 byte
     * @throws IOException 압축 해제 실패 / 크기 한도 초과 / truncated stream 시
     */
    public static byte[] decompress(byte[] data) throws IOException {
        if (data == null) throw new IOException("compressed data is null");
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(data);

            // 초기 용량은 입력 x2 로 시작하되 상한 이하로 클램프
            int initial = (int) Math.min((long) data.length * 2, MAX_DECOMPRESSED_BYTES);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(initial, 64));
            byte[] buf = new byte[BUFFER_SIZE];
            long ratioCeiling = saturatingMul(data.length, MAX_COMPRESSION_RATIO) + RATIO_FLOOR_BYTES;
            while (!inflater.finished()) {
                int count;
                try {
                    count = inflater.inflate(buf);
                } catch (DataFormatException e) {
                    throw new IOException("Invalid compressed data", e);
                }
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        // 입력은 모두 소진되었는데 finished() 가 false
                        // → truncated stream. 조용한 부분 해제를 막기 위해 명시적 실패.
                        throw new IOException("Truncated deflate stream (not finished)");
                    }
                    // 진전 없는 상태가 지속되면 무한 루프 방지
                    break;
                }
                long after = (long) out.size() + count;
                if (after > MAX_DECOMPRESSED_BYTES) {
                    throw new IOException("Decompressed size exceeds limit ("
                            + MAX_DECOMPRESSED_BYTES + " bytes)");
                }
                if (after > ratioCeiling) {
                    throw new IOException("Compression ratio exceeds limit (1:"
                            + MAX_COMPRESSION_RATIO + ") — suspected zip bomb");
                }
                out.write(buf, 0, count);
            }
            if (!inflater.finished()) {
                throw new IOException("Truncated deflate stream (loop exited before finish)");
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

    /** long 곱셈의 오버플로를 포화(Long.MAX_VALUE) 로 클램프한다. */
    private static long saturatingMul(long a, long b) {
        if (a <= 0 || b <= 0) return 0;
        long r = a * b;
        if (r / b != a) return Long.MAX_VALUE;
        return r;
    }
}
