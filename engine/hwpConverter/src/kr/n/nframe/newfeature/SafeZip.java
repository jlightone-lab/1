package kr.n.nframe.newfeature;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 악성/손상 ZIP(zip bomb, Zip Slip) 방어 공용 유틸.
 *
 * <p>기존 HWPX 경로(HwpxReader.validateZipStructure / HwpxXmlRewriter 의 엔트리 개수·
 * 단일/누적 크기 상한)와 <b>동일한 정책</b>을 신규 ODT 경로에서도 재사용하기 위해
 * 로직을 한 곳으로 추출한 것이다. 새 정책을 도입하지 않는다.
 *
 * <ul>
 *   <li>엔트리 개수 상한 {@link #MAX_ENTRIES}</li>
 *   <li>단일 엔트리 압축해제 크기 상한 {@link #MAX_ENTRY_BYTES} (content/section XML 대용량 대비 512MB)</li>
 *   <li>전체 누적 크기 상한 {@link #MAX_TOTAL_BYTES}</li>
 *   <li>엔트리 이름 traversal / Zip Slip / 절대경로 / 드라이브레터 / NUL 검사</li>
 * </ul>
 */
public final class SafeZip {

    /** ZIP 엔트리 최대 개수. */
    public static final int MAX_ENTRIES = 10_000;
    /** 단일 엔트리 최대 압축해제 크기 (content.xml/section0.xml 대용량 고려 → 512MB). */
    public static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;
    /** 모든 엔트리 누적 최대 크기. */
    public static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;

    private SafeZip() {}

    /**
     * ZIP 엔트리 이름의 traversal / 절대경로 / NUL / 드라이브레터를 검사한다.
     * (HwpxReader.validateZipStructure / HwpxXmlRewriter.validateEntryName 과 동일.)
     */
    public static void validateEntryName(String name) throws IOException {
        if (name == null) throw new IOException("Null ZIP entry name");
        if (name.indexOf('\0') >= 0) throw new IOException("Malicious ZIP entry name (NUL byte)");
        String norm = name.replace('\\', '/');
        if (norm.startsWith("/")
                || norm.contains("../")
                || norm.equals("..")
                || norm.endsWith("/..")
                || (norm.length() >= 2 && norm.charAt(1) == ':')) {
            throw new IOException("Malicious ZIP entry path: " + name);
        }
    }

    /** {@code ++count} 후 엔트리 개수 상한을 검사한다. 초과 시 {@code IOException}. */
    public static void checkEntryCount(int count) throws IOException {
        if (count > MAX_ENTRIES) {
            throw new IOException("ZIP entry count exceeds limit (" + MAX_ENTRIES + ")");
        }
    }

    /**
     * 엔트리 스트림을 끝까지 읽되 단일 엔트리 상한과 누적 상한을 강제한다.
     * {@code runningTotal[0]} 에 이번 엔트리 크기를 누적한다(호출자 공유).
     *
     * @param in          엔트리 입력 스트림 (호출자가 close)
     * @param entryName    오류 메시지용 이름
     * @param initialHint  ByteArrayOutputStream 초기 용량 힌트 (음수/과대는 보정)
     * @param runningTotal 길이 1 의 누적 카운터 (여러 엔트리 간 공유)
     */
    public static byte[] readEntryBounded(InputStream in, String entryName,
                                          long initialHint, long[] runningTotal) throws IOException {
        int cap = (initialHint > 0 && initialHint <= (1 << 20)) ? (int) initialHint : 8192;
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8192, cap));
        byte[] buf = new byte[16384];
        int n;
        long entryBytes = 0;
        while ((n = in.read(buf)) >= 0) {
            entryBytes += n;
            if (entryBytes > MAX_ENTRY_BYTES) {
                throw new IOException("ZIP entry too large: " + entryName);
            }
            if (runningTotal[0] + entryBytes > MAX_TOTAL_BYTES) {
                throw new IOException("ZIP total size exceeds limit (" + MAX_TOTAL_BYTES + " bytes)");
            }
            out.write(buf, 0, n);
        }
        runningTotal[0] += entryBytes;
        return out.toByteArray();
    }
}
