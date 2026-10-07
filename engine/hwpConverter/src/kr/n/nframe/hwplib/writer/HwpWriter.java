package kr.n.nframe.hwplib.writer;

import kr.n.nframe.hwplib.binary.ZlibCompressor;
import kr.n.nframe.hwplib.model.HwpDocument;
import kr.n.nframe.hwplib.model.Paragraph;
import kr.n.nframe.hwplib.model.Section;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * 최상위 HWP 5.0 바이너리 파일 writer.
 * 필요한 모든 스트림을 포함하여 OLE2 복합 문서를 조립한다.
 */
public class HwpWriter {

    private HwpWriter() {}

    /**
     * HwpDocument 모델을 HWP 5.0 바이너리 파일로 기록한다.
     *
     * @param doc        직렬화할 문서 모델
     * @param outputPath 출력 .hwp 파일의 파일 시스템 경로
     * @throws IOException 기록 실패 시
     */
    public static void write(HwpDocument doc, String outputPath) throws IOException {
        // 1. FileHeader 생성 (256 byte, 비압축)
        byte[] fileHeaderBytes = FileHeaderWriter.build(doc);

        // 2. DocInfo 레코드 생성 후 압축
        byte[] rawDocInfo = DocInfoWriter.build(doc);
        byte[] compressedDocInfo = ZlibCompressor.compress(rawDocInfo);

        // 3. 섹션 스트림 생성 후 압축
        byte[][] compressedSections = new byte[doc.sections.size()][];
        for (int i = 0; i < doc.sections.size(); i++) {
            byte[] rawSection = SectionWriter.build(doc.sections.get(i), doc);
            compressedSections[i] = ZlibCompressor.compress(rawSection);
        }

        // 4. OLE2 복합 문서 생성
        POIFSFileSystem poifs = new POIFSFileSystem();
        DirectoryEntry root = poifs.getRoot();

        // 5. 스트림 추가
        root.createDocument("FileHeader", new ByteArrayInputStream(fileHeaderBytes));
        root.createDocument("DocInfo", new ByteArrayInputStream(compressedDocInfo));

        // 섹션 스트림을 포함하는 BodyText 디렉토리
        DirectoryEntry bodyText = root.createDirectory("BodyText");
        for (int i = 0; i < compressedSections.length; i++) {
            bodyText.createDocument("Section" + i, new ByteArrayInputStream(compressedSections[i]));
        }

        // BinData 디렉토리 (임베디드 바이너리 데이터가 존재하는 경우)
        boolean hasBinData = doc.binDataItems.stream()
                .anyMatch(item -> item.data != null && item.data.length > 0);
        if (hasBinData) {
            DirectoryEntry binDir = root.createDirectory("BinData");
            BinDataWriter.write(binDir, doc);
        }

        // 요약 정보 (최소 구성)
        root.createDocument("\005HwpSummaryInformation",
                new ByteArrayInputStream(buildMinimalSummaryInfo()));

        // 미리보기 텍스트 (최소 구성)
        root.createDocument("PrvText",
                new ByteArrayInputStream(buildPrvText(doc)));

        // 미리보기 이미지 (비어 있지만 존재함)
        root.createDocument("PrvImage",
                new ByteArrayInputStream(new byte[0]));

        // Scripts 디렉토리 - 참조와 호환되는 형식 사용
        DirectoryEntry scripts = root.createDirectory("Scripts");
        // JScriptVersion: 참조에서 사용하는 특정 13-byte 형식
        scripts.createDocument("JScriptVersion",
                new ByteArrayInputStream(new byte[]{
                    0x63, 0x64, (byte)0x80, 0x00, 0x00, (byte)0xf7, (byte)0xdf,
                    (byte)0x88, (byte)0xa9, 0x08, 0x00, 0x00, 0x00}));
        // DefaultJScript: 참조에서 사용하는 특정 16-byte 형식
        scripts.createDocument("DefaultJScript",
                new ByteArrayInputStream(new byte[]{
                    0x63, 0x60, 0x40, 0x05, (byte)0xff, (byte)0x81, 0x00, 0x00,
                    0x6e, (byte)0xbb, 0x6e, (byte)0xd1, 0x14, 0x00, 0x00, 0x00}));

        // _LinkDoc를 포함하는 DocOptions 디렉토리 (524 byte의 0)
        DirectoryEntry docOptions = root.createDirectory("DocOptions");
        docOptions.createDocument("_LinkDoc",
                new ByteArrayInputStream(new byte[524]));

        // 6. 파일에 기록
        try (OutputStream fos = new FileOutputStream(outputPath)) {
            poifs.writeFilesystem(fos);
        } finally {
            poifs.close();
        }
    }

    /**
     * HWP SummaryInformation 스트림 템플릿 (493 bytes).
     *
     * <p>한글 워드프로세서(HWP 2024)가 실제로 생성한 {@code \005HwpSummaryInformation}
     * 스트림을 그대로 캡처한 바이트 배열이다. 아래 항목을 모두 포함하므로
     * hwplib의 shaded POI가 {@code UnexpectedPropertySetTypeException} 없이 파싱하고,
     * hwp2hwpx의 {@code ForContentHPFFile.metadata()} 가 {@code NullPointerException}
     * 없이 Title / Subject / Author / Keywords / Comments / LastAuthor / RevNumber /
     * CreateDateTime / LastSaveDateTime / PageCount / ApplicationName 등의 필드를
     * 조회할 수 있다.
     *
     * <p><b>FMTID</b>: {@code {9FA2B660-1061-11D4-B4C6-006097C09D8C}} (HWP 전용)
     * <br><b>속성 수</b>: 14 (dictionary 포함)
     *
     * <p>v13.4 이하는 56-byte 최소 헤더만 기록했는데, 이로 인해 두 단계에서 round-trip
     * (우리 HWP 를 다시 HWPX 로) 이 실패했다:
     * <ul>
     *   <li>v13.4 이하 : FMTID 바이트가 이중으로 뒤집혀 저장되어 shaded POI 가
     *       {@code "Not a SummaryInformation"} 으로 거부.</li>
     *   <li>속성 0개였을 때 : FMTID 는 맞아도 hwp2hwpx 가 필수 메타데이터 속성을
     *       읽다가 {@code NullPointerException}.</li>
     * </ul>
     * v13.5 부터는 아래 템플릿을 그대로 기록한다. 템플릿의 문자열 값(제목/저자/
     * 날짜 등)은 의미 없는 고정값이지만 round-trip 호환성이 최우선.
     */
    private static final byte[] HWP_SUMMARY_INFO_TEMPLATE = new byte[]{
        (byte)0xFE, (byte)0xFF, (byte)0x00, (byte)0x00, (byte)0x0D, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x60, (byte)0xB6, (byte)0xA2, (byte)0x9F, (byte)0x61, (byte)0x10, (byte)0xD4, (byte)0x11,
        (byte)0xB4, (byte)0xC6, (byte)0x00, (byte)0x60, (byte)0x97, (byte)0xC0, (byte)0x9D, (byte)0x8C, (byte)0x01, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x60, (byte)0xB6, (byte)0xA2, (byte)0x9F,
        (byte)0x61, (byte)0x10, (byte)0xD4, (byte)0x11, (byte)0xB4, (byte)0xC6, (byte)0x00, (byte)0x60, (byte)0x97, (byte)0xC0, (byte)0x9D, (byte)0x8C, (byte)0x30, (byte)0x00, (byte)0x00, (byte)0x00,
        (byte)0xBD, (byte)0x01, (byte)0x00, (byte)0x00, (byte)0x0E, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x02, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x78, (byte)0x00, (byte)0x00, (byte)0x00,
        (byte)0x03, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x94, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x04, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0xA0, (byte)0x00, (byte)0x00, (byte)0x00,
        (byte)0x14, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0xB4, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x05, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0xF8, (byte)0x00, (byte)0x00, (byte)0x00,
        (byte)0x06, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x04, (byte)0x01, (byte)0x00, (byte)0x00, (byte)0x08, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x10, (byte)0x01, (byte)0x00, (byte)0x00,
        (byte)0x09, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x34, (byte)0x01, (byte)0x00, (byte)0x00, (byte)0x0C, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x7C, (byte)0x01, (byte)0x00, (byte)0x00,
        (byte)0x0D, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x88, (byte)0x01, (byte)0x00, (byte)0x00, (byte)0x0B, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x94, (byte)0x01, (byte)0x00, (byte)0x00,
        (byte)0x0E, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0xA0, (byte)0x01, (byte)0x00, (byte)0x00, (byte)0x15, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0xA8, (byte)0x01, (byte)0x00, (byte)0x00,
        (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0xB0, (byte)0x01, (byte)0x00, (byte)0x00, (byte)0x1F, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0A, (byte)0x00, (byte)0x00, (byte)0x00,
        (byte)0xBD, (byte)0xAC, (byte)0x30, (byte)0xAE, (byte)0xC4, (byte)0xB3, (byte)0x20, (byte)0x00, (byte)0x08, (byte)0xC6, (byte)0xB0, (byte)0xC0, (byte)0xF4, (byte)0xB2, (byte)0xF9, (byte)0xB2,
        (byte)0x00, (byte)0xAD, (byte)0x00, (byte)0x00, (byte)0x1F, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
        (byte)0x1F, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x06, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x55, (byte)0x00, (byte)0x73, (byte)0x00, (byte)0x65, (byte)0x00, (byte)0x72, (byte)0x00,
        (byte)0x59, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x1F, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x1D, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x32, (byte)0x00, (byte)0x30, (byte)0x00,
        (byte)0x32, (byte)0x00, (byte)0x30, (byte)0x00, (byte)0x44, (byte)0xB1, (byte)0x20, (byte)0x00, (byte)0x37, (byte)0x00, (byte)0xD4, (byte)0xC6, (byte)0x20, (byte)0x00, (byte)0x31, (byte)0x00,
        (byte)0x35, (byte)0x00, (byte)0x7C, (byte)0xC7, (byte)0x20, (byte)0x00, (byte)0x18, (byte)0xC2, (byte)0x94, (byte)0xC6, (byte)0x7C, (byte)0xC7, (byte)0x20, (byte)0x00, (byte)0x24, (byte)0xC6,
        (byte)0x04, (byte)0xC8, (byte)0x20, (byte)0x00, (byte)0x31, (byte)0x00, (byte)0x30, (byte)0x00, (byte)0x3A, (byte)0x00, (byte)0x33, (byte)0x00, (byte)0x35, (byte)0x00, (byte)0x3A, (byte)0x00,
        (byte)0x32, (byte)0x00, (byte)0x38, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x1F, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01, (byte)0x00, (byte)0x00, (byte)0x00,
        (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x1F, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
        (byte)0x1F, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0E, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x41, (byte)0x00, (byte)0x64, (byte)0x00, (byte)0x6D, (byte)0x00, (byte)0x69, (byte)0x00,
        (byte)0x6E, (byte)0x00, (byte)0x69, (byte)0x00, (byte)0x73, (byte)0x00, (byte)0x74, (byte)0x00, (byte)0x72, (byte)0x00, (byte)0x61, (byte)0x00, (byte)0x74, (byte)0x00, (byte)0x6F, (byte)0x00,
        (byte)0x72, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x1F, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x20, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x31, (byte)0x00, (byte)0x32, (byte)0x00,
        (byte)0x2C, (byte)0x00, (byte)0x20, (byte)0x00, (byte)0x30, (byte)0x00, (byte)0x2C, (byte)0x00, (byte)0x20, (byte)0x00, (byte)0x30, (byte)0x00, (byte)0x2C, (byte)0x00, (byte)0x20, (byte)0x00,
        (byte)0x35, (byte)0x00, (byte)0x33, (byte)0x00, (byte)0x35, (byte)0x00, (byte)0x20, (byte)0x00, (byte)0x57, (byte)0x00, (byte)0x49, (byte)0x00, (byte)0x4E, (byte)0x00, (byte)0x33, (byte)0x00,
        (byte)0x32, (byte)0x00, (byte)0x4C, (byte)0x00, (byte)0x45, (byte)0x00, (byte)0x57, (byte)0x00, (byte)0x69, (byte)0x00, (byte)0x6E, (byte)0x00, (byte)0x64, (byte)0x00, (byte)0x6F, (byte)0x00,
        (byte)0x77, (byte)0x00, (byte)0x73, (byte)0x00, (byte)0x5F, (byte)0x00, (byte)0x31, (byte)0x00, (byte)0x30, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x40, (byte)0x00, (byte)0x00, (byte)0x00,
        (byte)0x00, (byte)0x70, (byte)0x23, (byte)0x38, (byte)0x48, (byte)0x5A, (byte)0xD6, (byte)0x01, (byte)0x40, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0xE0, (byte)0xD4, (byte)0xE4, (byte)0xF5,
        (byte)0x02, (byte)0xCB, (byte)0xDC, (byte)0x01, (byte)0x40, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
        (byte)0x03, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x03, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
        (byte)0x01, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00
    };

    private static byte[] buildMinimalSummaryInfo() {
        return HWP_SUMMARY_INFO_TEMPLATE.clone();
    }

    /**
     * 첫 번째 섹션의 첫 번째 문단으로부터 미리보기 텍스트를 생성한다.
     * UTF-16LE 인코딩된 텍스트를 반환한다.
     */
    private static byte[] buildPrvText(HwpDocument doc) {
        StringBuilder sb = new StringBuilder();
        if (!doc.sections.isEmpty()) {
            Section sec = doc.sections.get(0);
            for (Paragraph para : sec.paragraphs) {
                if (para.paraText != null && para.paraText.rawBytes != null) {
                    // UTF-16LE 원시 byte를 디코딩하며 제어 문자 제거
                    String text = new String(para.paraText.rawBytes, StandardCharsets.UTF_16LE);
                    for (int i = 0; i < text.length(); i++) {
                        char c = text.charAt(i);
                        if (c >= 0x20 || c == '\n' || c == '\r' || c == '\t') {
                            sb.append(c);
                        }
                    }
                    sb.append('\n');
                }
                if (sb.length() > 1024) break; // 미리보기 길이 제한
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_16LE);
    }

    /**
     * 최소한의 JScriptVersion 스트림을 생성한다.
     * 버전 문자열 "JScript1.0"을 UTF-16LE로 포함한다.
     */
    private static byte[] buildJScriptVersion() {
        return "JScript1.0".getBytes(StandardCharsets.UTF_16LE);
    }
}
