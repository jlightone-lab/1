package kr.n.nframe.newfeature;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * [v16t56 신기능] 확장자에 의존하지 않고 파일 시그니처(매직 바이트)로 실제 포맷을 판별.
 *
 * <p>외부 의존(Apache Tika 등) 없이 lib 에 이미 포함된 Apache POI(OLE2/CFB 판독)와
 * 표준 {@link java.util.zip} 만 사용한다. 사용자가 확장자를 잘못 붙이거나(예: .hwp 인데
 * 실제 HWPX) 확장자가 없는 파일을 넘겨도 변환기가 올바른 경로를 타도록 돕는다.</p>
 *
 * <ul>
 *   <li>HWP5: CFB 시그니처 {@code D0CF11E0A1B11AE1} + Root 에 {@code FileHeader} 스트림 +
 *       그 선두 17바이트 = {@code "HWP Document File"}.</li>
 *   <li>HWPX: ZIP({@code PK..}) + 첫 엔트리명 {@code mimetype} + 내용
 *       {@code application/hwp+zip}.</li>
 *   <li>ODT: ZIP({@code PK..}) + 첫 엔트리명 {@code mimetype} + 내용
 *       {@code application/vnd.oasis.opendocument.text}.</li>
 *   <li>그 외: {@link Format#UNKNOWN}.</li>
 * </ul>
 */
public final class FormatSniffer {

    public enum Format { HWP5, HWPX, ODT, MD, UNKNOWN }

    /** HWPX mimetype 실측값(스펙의 hancom-hwpx 가 아님). */
    public static final String MIME_HWPX = "application/hwp+zip";
    public static final String MIME_ODT  = "application/vnd.oasis.opendocument.text";

    private static final byte[] CFB_MAGIC = {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
    };
    private static final byte[] ZIP_MAGIC = { 0x50, 0x4B, 0x03, 0x04 };
    private static final String HWP5_FILEHEADER_SIG = "HWP Document File";

    private FormatSniffer() {}

    /** 파일을 시그니처로 판별. 읽기 실패/미존재 시 {@link Format#UNKNOWN}. */
    public static Format sniff(File f) {
        if (f == null || !f.isFile()) return Format.UNKNOWN;
        byte[] head = readHead(f, 8);
        if (head == null) return Format.UNKNOWN;
        if (startsWith(head, CFB_MAGIC)) {
            return isHwp5(f) ? Format.HWP5 : Format.UNKNOWN;
        }
        if (startsWith(head, ZIP_MAGIC)) {
            String mime = firstZipEntryMime(f);
            if (MIME_HWPX.equals(mime)) return Format.HWPX;
            if (MIME_ODT.equals(mime))  return Format.ODT;
            return Format.UNKNOWN;
        }
        // [v57] 매직바이트 없음 → 텍스트/MD 휴리스틱 (CFB·ZIP 배제 후에만)
        if (sniffMarkdown(f)) return Format.MD;
        return Format.UNKNOWN;
    }

    /** 시그니처가 가리키는 정규 확장자(점 없이). UNKNOWN 이면 null. */
    public static String canonicalExt(Format fmt) {
        switch (fmt) {
            case HWP5: return "hwp";
            case HWPX: return "hwpx";
            case ODT:  return "odt";
            case MD:   return "md";   // [v57]
            default:   return null;
        }
    }

    /** 읽기 버퍼(헤드만으로 충분, 대용량 MD 도 선두에서 판정). */
    private static final int MD_SNIFF_BYTES = 4096;
    /** UTF-8 BOM. */
    private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

    /**
     * 텍스트(UTF-8 유효·바이너리 아님)면 MD 로 판정.
     *
     * <p>[v16t60 hotfix] 매직바이트가 없는 입력은 "마크다운 토큰 ≥1줄" 을 요구하던
     * 종전 휴리스틱을 폐기하고, 알려진 바이너리(CFB/ZIP=hwp/hwpx/odt, {@link #sniff}
     * 에서 선배제)가 아니면서 NUL·금지 C0 제어문자가 없고 UTF-8 로 디코딩되는 텍스트면
     * MD 로 폴백한다. 무확장자 마크다운의 선두 4096바이트가 HTML 표/거대한 단일행
     * base64 이미지로 채워져 토큰 윈도를 벗어나도 변환 경로를 타게 하기 위함.</p>
     */
    private static boolean sniffMarkdown(File f) {
        byte[] head = readHead(f, MD_SNIFF_BYTES);
        if (head == null || head.length == 0) return false;
        int off = startsWith(head, UTF8_BOM) ? UTF8_BOM.length : 0;  // BOM 허용
        // 1) 바이너리 배제: NUL·금지 C0 제어문자(허용: \t\n\r\f) 발견 시 비텍스트
        for (int i = off; i < head.length; i++) {
            int b = head[i] & 0xFF;
            if (b == 0x00) return false;
            if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D && b != 0x0C) return false;
        }
        // 2) 유효 UTF-8 텍스트 검증 (head 경계에서 잘린 멀티바이트는 관용)
        return isValidUtf8(head, off, head.length - off);
    }

    /** 엄격 UTF-8 디코딩. 끝 1~3바이트를 줄여가며 시도해 경계에서 잘린 멀티바이트만 관용. */
    private static boolean isValidUtf8(byte[] data, int off, int len) {
        if (len <= 0) return false;
        java.nio.charset.CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder();
        dec.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
        dec.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        for (int trim = 0; trim <= 3 && len - trim > 0; trim++) {
            dec.reset();
            try {
                dec.decode(java.nio.ByteBuffer.wrap(data, off, len - trim));
                return true;  // 깨끗이 디코딩됨 → 텍스트
            } catch (java.nio.charset.CharacterCodingException e) {
                // 끝 멀티바이트가 잘렸을 수 있음 → 한 바이트 줄여 재시도
            }
        }
        return false;  // 중간에 깨진 시퀀스 = 비텍스트(바이너리)
    }

    private static boolean isHwp5(File f) {
        try (FileInputStream fis = new FileInputStream(f);
             org.apache.poi.poifs.filesystem.POIFSFileSystem fs =
                     new org.apache.poi.poifs.filesystem.POIFSFileSystem(fis)) {
            org.apache.poi.poifs.filesystem.DirectoryEntry root = fs.getRoot();
            if (!root.hasEntry("FileHeader")) return false;
            org.apache.poi.poifs.filesystem.DocumentEntry hdr =
                    (org.apache.poi.poifs.filesystem.DocumentEntry) root.getEntry("FileHeader");
            byte[] data = new byte[Math.min(hdr.getSize(), 32)];
            try (org.apache.poi.poifs.filesystem.DocumentInputStream dis =
                         new org.apache.poi.poifs.filesystem.DocumentInputStream(hdr)) {
                dis.readFully(data);
            }
            byte[] sig = HWP5_FILEHEADER_SIG.getBytes(StandardCharsets.US_ASCII);
            return startsWith(data, sig);
        } catch (Exception e) {
            return false;
        }
    }

    /** ZIP 의 물리적 첫 엔트리가 {@code mimetype} 이면 그 내용(trim)을 반환, 아니면 null. */
    private static String firstZipEntryMime(File f) {
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            ZipEntry first = zin.getNextEntry();
            if (first == null || !"mimetype".equals(first.getName())) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int r;
            while ((r = zin.read(buf)) > 0) bos.write(buf, 0, r);
            return new String(bos.toByteArray(), StandardCharsets.US_ASCII).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readHead(File f, int n) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            byte[] b = new byte[n];
            int off = 0, r;
            while (off < n && (r = in.read(b, off, n - off)) > 0) off += r;
            if (off < n) {
                byte[] t = new byte[off];
                System.arraycopy(b, 0, t, 0, off);
                return t;
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data == null || data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
        return true;
    }
}
