package kr.n.nframe;

import java.io.IOException;

import kr.n.nframe.hwplib.model.HwpDocument;
import kr.n.nframe.hwplib.reader.HwpxReader;
import kr.n.nframe.hwplib.writer.HwpWriter;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import kr.dogfoot.hwp2hwpx.Hwp2Hwpx;
import kr.n.nframe.hwplib.writer.DistributionWriter;
import kr.n.nframe.hwplib.writer.HwpxPostProcessor;
import kr.n.nframe.hwplib.writer.HwpxXmlRewriter;

/**
 * HWPX와 HWP 포맷 간의 양방향 변환기.
 *
 * - convertHwpxToHwp: HWPX -> HWP (직접 바이너리 변환, 자체 구현)
 * - convertHwpToHwpx: HWP -> HWPX (neolord0/hwp2hwpx 라이브러리 사용)
 * - makeHwpDist:      HWP -> 배포용 HWP (DRM 보호, 복사/인쇄 방지)
 */
public class HwpConverter {

    /**
     * HWPX → HWP 변환 (직접 바이너리 자체 구현).
     */
    public void convertHwpxToHwp(String filePathHwpx, String filePathHwp) throws IOException {
        ensureDistinctPaths(filePathHwpx, filePathHwp);
        System.out.println("[HwpConverter] Reading HWPX: " + filePathHwpx);
        HwpDocument doc = HwpxReader.read(filePathHwpx);

        System.out.println("[HwpConverter] Sections: " + doc.sections.size());
        System.out.println("[HwpConverter] Fonts: " + doc.faceNames.stream().mapToInt(java.util.List::size).sum());
        System.out.println("[HwpConverter] CharShapes: " + doc.charShapes.size());
        System.out.println("[HwpConverter] ParaShapes: " + doc.paraShapes.size());
        System.out.println("[HwpConverter] BorderFills: " + doc.borderFills.size());
        System.out.println("[HwpConverter] Styles: " + doc.styles.size());
        System.out.println("[HwpConverter] BinData: " + doc.binDataItems.size());
        if (!doc.sections.isEmpty()) {
            System.out.println("[HwpConverter] Section0 paragraphs: " + doc.sections.get(0).paragraphs.size());
        }

        // v16t67: 목차 인라인 DOT leader 탭을 header TabDef DOT-fill 로 매핑 (HWPX→HWP 전용)
        kr.n.nframe.hwplib.reader.TocDotLeaderMapper.apply(doc);

        System.out.println("[HwpConverter] Writing HWP: " + filePathHwp);
        HwpWriter.write(doc, filePathHwp);

        System.out.println("[HwpConverter] Conversion complete.");
    }

    /**
     * HWP → HWPX 변환 (neolord0/hwp2hwpx 라이브러리 사용).
     * v16t50 R7: 종전 호출 그대로 — 원본 HWP 입력으로 간주(sourceIsOdt=false).
     */
    public void convertHwpToHwpx(String filePathHwp, String filePathHwpx) throws Exception {
        convertHwpToHwpx(filePathHwp, filePathHwpx, false);
    }

    /**
     * v16t50 R7: ODT 직변환 경로 전용 overload. sourceIsOdt=true 면 HwpxPostProcessor 의
     * 모든 표 pageBreak 강제 덮어쓰기를 스킵해 빌더가 매긴 표별 pageBreak 분포가 보존된다.
     * MD→HWPX 등 hwp 입력 경로는 종전 메서드(=false) 호출이라 byte 출력 불변.
     */
    public void convertHwpToHwpx(String filePathHwp, String filePathHwpx, boolean sourceIsOdt) throws Exception {
        ensureDistinctPaths(filePathHwp, filePathHwpx);
        // 배포용(dist) HWP 파일 조기 감지: FileHeader.properties 의 bit 2 가 켜져 있으면
        // 배포용 문서이며 AES-128 암호화된 ViewText 스트림만 실제 본문을 담고 있다.
        // hwp2hwpx 는 이를 복호화할 수 없으므로, 중간에 "This is not paragraph" 같은
        // 모호한 예외로 실패하는 대신 여기서 즉시 명확한 메시지로 중단한다.
        if (isDistributionHwp(filePathHwp)) {
            throw new IllegalStateException(
                    "입력 HWP가 배포용(dist, DRM/암호화) 문서입니다. "
                    + "hwp2hwpx 는 암호화된 HWP 를 HWPX 로 변환할 수 없습니다. "
                    + "배포용으로 저장되기 전의 원본 HWP 를 사용하세요.");
        }

        System.out.println("[HwpConverter] Reading HWP: " + filePathHwp);
        HWPFile hwpFile = HWPReader.fromFile(filePathHwp);

        // hwp2hwpx 호출 전에 HWP 텍스트에서 astral-plane 코드 포인트(surrogate pair)를
        // 스캔한다. 라이브러리의 HWPCharNormal.getCh()는 16-bit 코드 유닛을 UTF-16LE로
        // 각각 독립적으로 디코딩하기 때문에 단독 surrogate가 U+FFFD로 손상된다.
        // 이후 HWPX XML에 해당 코드 포인트를 다시 주입해 원본 문자를 복원한다.
        java.util.List<Integer> astralCodePoints = collectHwpAstralCodePoints(hwpFile);

        // hwp2hwpx 호출 전에 HWP 텍스트의 탭 컨트롤 파라미터(width/leader/type)를
        // 스캔한다. 라이브러리의 ForChars.addTab()이 실제 HWP 탭 데이터와 무관하게
        // width=4000, leader=NONE, type=LEFT 로 하드코딩하기 때문에 목차의 점선 리더
        // ("···")나 우측 정렬된 페이지 번호 탭 등이 사라진다. 이후 HWPX XML에 탭별
        // 설정을 다시 주입한다.
        java.util.List<int[]> tabSettings = collectHwpTabSettings(hwpFile);

        // v15.11: hwpxlib 1.0.9 의 writer 에는 BookmarkWriter 가 없어 hwp2hwpx
        //   가 만든 Bookmark 객체가 XML 로 직렬화되지 않는다. 그 결과 책갈피로
        //   target 되는 하이퍼링크 (?bookmark_name) 가 한/글에서 클릭해도 이동할
        //   곳이 없음. 회피책으로 소스 HWP 의 책갈피 이름을 paragraph 별로 수집
        //   하여 XML 사후 재작성 단계에서 <hp:bookmark> 를 주입한다.
        java.util.List<java.util.List<String>> sectionBookmarks = collectHwpBookmarks(hwpFile);

        System.out.println("[HwpConverter] Converting HWP → HWPX...");
        HWPXFile hwpxFile = Hwp2Hwpx.toHWPX(hwpFile);

        // HWPXFile을 한글 프로그램이 동일한 HWP에서 만들어내는 결과와 동일하게 맞춘다.
        // 변환은 HWP의 HWPTAG_COMPATIBLE_DOCUMENT targetProgram에 따라 조건부로 적용된다:
        // HWPCurrent에서 만들어진 문서는 MS_WORD 프로파일 + 레이아웃 플래그 + CELL 페이지
        // 분리를 받고, MSWord 출처 문서는 hwp2hwpx 기본값(HWP201X + 빈 compat)을 유지한다.
        // 프로파일별 세부 사항은 HwpxPostProcessor javadoc 참조.
        HwpxPostProcessor.normalize(hwpFile, hwpxFile, sourceIsOdt);

        System.out.println("[HwpConverter] Writing HWPX: " + filePathHwpx);
        HWPXWriter.toFilepath(hwpxFile, filePathHwpx);

        // XML 레벨 사후 재작성: hwpxlib의 객체 API로는 표현할 수 없는 한글 스타일의
        // 구조 마커(말미의 빈 run, tc name="", container.rdf)를 삽입한다. 예를 들어
        // TA-05의 의도적으로 비어 있는 2페이지를 한글에서 다시 열었을 때 보존하기 위해
        // 필요하다. 또한 HWP→HWPX 텍스트 변환 중 hwplib가 U+FFFD U+FFFD로 망가뜨린
        // astral-plane 코드 포인트를 다시 주입한다.
        HwpxXmlRewriter.rewrite(filePathHwpx, astralCodePoints, tabSettings, sectionBookmarks, sourceIsOdt);

        System.out.println("[HwpConverter] Conversion complete.");
    }

    /**
     * HWP -> 배포용 HWP 변환 (배포용 문서).
     *
     * @param inputPath  일반 HWP 파일 경로
     * @param outputPath 배포용 HWP 출력 경로
     * @param password   암호화에 사용할 암호
     * @param noCopy     복사 방지 활성화 여부
     * @param noPrint    인쇄 방지 활성화 여부
     */
    public void makeHwpDist(String inputPath, String outputPath,
                            String password, boolean noCopy, boolean noPrint) throws Exception {
        ensureDistinctPaths(inputPath, outputPath);

        // [v16t64] 이미 배포용(DISTRIBUTE 비트=1) 입력의 재배포 차단 가드.
        // 배포용 문서는 본문이 암호화된 ViewText 로 옮겨지고 BodyText 에는 빈 더미만
        // 남는다. 그대로 --dist 를 재적용하면 DistributionWriter 가 그 빈 BodyText 를
        // 다시 읽어 암호화 → "빈 본문" 배포본이 만들어지고 한/글이 "파일이 손상되었습니다"
        // 로 거부한다(이전엔 조용히 빈 문서가 생성됐음). 일반 원본(비트=0)만 허용한다.
        if (isDistributedHwp(inputPath)) {
            String msg = "입력이 이미 배포용 문서입니다 (FileHeader DISTRIBUTE 비트=1): " + inputPath
                    + " — 배포용 문서를 다시 --dist 하면 빈 본문이 만들어집니다."
                    + " 일반 원본 HWP/HWPX 를 입력으로 사용하세요.";
            System.err.println("[HwpConverter] 오류: " + msg);
            throw new IllegalStateException(msg);
        }

        // v13.14: 출력 파일의 부모 디렉터리가 없으면 자동 생성한다.
        // 기존에는 부모 디렉터리가 없을 때 FileNotFoundException("지정된 경로를
        // 찾을 수 없습니다") 이 발생해 파일이 생성되지 않는 것처럼 보였다.
        ensureParentDir(outputPath);

        boolean inputIsHwpx = inputPath.toLowerCase().endsWith(".hwpx");
        boolean outputIsHwpx = outputPath.toLowerCase().endsWith(".hwpx");

        // v15.3: HWPX 는 표준상 DRM(배포용 문서, AES-128 ViewText) 메커니즘을
        // 가지지 않는다 — DRM 은 OLE2 기반 HWP 의 ViewText/Section 스트림 +
        // FileHeader.properties bit 2 로만 정의되어 있다.
        // 이전 동작 (v13.14~v15.2) : .hwpx 출력 경로면 DRM 을 건너뛰고
        //   비암호화 HWPX 를 작성 → 사용자는 "dist 가 안 걸려 있는" 결과
        //   를 받음. (tasks 4~6, 0512)
        // 현재 동작 (v15.3) : --dist 의도(복사/인쇄 방지를 적용)를 보존하기 위해
        //   출력 확장자를 자동으로 .hwp 로 변경하고, 정상적인 DRM HWP 를
        //   생성한다. 결과 파일은 한/글에서 DRM 보호된 채로 열려 내용이 노출된다.
        if (outputIsHwpx) {
            // [v16t63] --dist 출력 경로가 .hwpx 면 강제개명 없이 그 확장자를 그대로
            // 따른다. 단, 내용은 진짜 HWPX(OWPML zip) 컨테이너가 아니라 보안 HWP
            // (CFB / OLE2, 배포용 DRM) 바이너리다. 복사/인쇄 방지 DRM 은 HWP5 전용
            // 규격이며 HWPX/OWPML 표준엔 인코딩이 없기 때문이다. 개명하지 않고
            // 아래 DistributionWriter 폴스루로 그대로 진행한다.
            System.out.println("[HwpConverter] 주의: 출력 확장자 .hwpx 를 그대로 유지하되,"
                    + " 내용은 보안 HWP(CFB, 배포용)입니다. 진짜 HWPX 컨테이너가 아닙니다.");
            outputIsHwpx = false;
        }

        String hwpPath = inputPath;
        java.io.File tmpFile = null;

        try {
            // 입력이 HWPX인 경우 우선 HWP로 변환 (임시 파일)
            if (inputIsHwpx) {
                hwpPath = outputPath + ".tmp.hwp";
                tmpFile = new java.io.File(hwpPath);
                System.out.println("[HwpConverter] Input is HWPX, converting to HWP first...");
                convertHwpxToHwp(inputPath, hwpPath);
            }

            // v16t42 비덮어쓰기: 출력이 이미 있으면 새 이름(N)으로 저장.
            outputPath = kr.n.nframe.newfeature.OutputNaming.unique(outputPath);
            System.out.println("[HwpConverter] Creating Distribution HWP (noCopy=" + noCopy + ", noPrint=" + noPrint + ")");
            DistributionWriter.makeDistribution(hwpPath, outputPath, password, noCopy, noPrint);

            System.out.println("[HwpConverter] Writing Distribution HWP: " + outputPath);
            System.out.println("[HwpConverter] Conversion complete.");
        } finally {
            // 예외 발생 여부와 관계없이 임시 파일(평문 HWP) 삭제 — DRM 의도상
            // 평문 중간 산물이 디스크에 남으면 안 됨.
            if (tmpFile != null) {
                try { java.nio.file.Files.deleteIfExists(tmpFile.toPath()); }
                catch (java.io.IOException ignored) {}
            }
        }
    }

    /**
     * 출력 파일 경로의 부모 디렉터리가 존재하지 않으면 생성한다.
     * (존재하지만 디렉터리가 아니면 예외).
     */
    private static void ensureParentDir(String outputPath) throws IOException {
        if (outputPath == null) return;
        java.io.File out = new java.io.File(outputPath);
        java.io.File parent = out.getAbsoluteFile().getParentFile();
        if (parent == null) return;
        if (parent.exists()) {
            if (!parent.isDirectory()) {
                throw new IOException("출력 경로의 부모가 디렉터리가 아닙니다: " + parent);
            }
            return;
        }
        if (!parent.mkdirs()) {
            // 혹시 경쟁 생성으로 방금 존재하게 되었는지 재확인
            if (!parent.isDirectory()) {
                throw new IOException("출력 디렉터리 생성 실패: " + parent);
            }
        }
    }

    /**
     * 입력과 출력 경로가 같은 실제 파일을 가리키면 예외를 던진다.
     * 자기 덮어쓰기로 원본이 파괴되거나 POIFS 가 읽는 도중 출력이 같은 핸들에
     * 쓰이는 상황을 방지한다.
     */
    private static void ensureDistinctPaths(String in, String out) {
        if (in == null || out == null) return;
        java.nio.file.Path inP = java.nio.file.Paths.get(in);
        java.nio.file.Path outP = java.nio.file.Paths.get(out);
        try {
            if (java.nio.file.Files.exists(inP) && java.nio.file.Files.exists(outP)
                    && java.nio.file.Files.isSameFile(inP, outP)) {
                throw new IllegalArgumentException(
                        "입력과 출력이 같은 파일을 가리킵니다: " + in);
            }
        } catch (java.nio.file.NoSuchFileException ignored) {
        } catch (java.io.IOException ignored) {
        }
        // 정규화 비교 폴백 (존재하지 않는 출력 등)
        if (inP.toAbsolutePath().normalize().equals(outP.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException(
                    "입력과 출력 경로가 동일합니다 (정규화 후): " + in);
        }
    }

    /**
     * [v16t64] 입력 파일이 "이미 배포용 문서"인지 판별한다.
     * 확장자가 아니라 실제 내용으로 판단: CFB(OLE2) 매직이 아니면(예: 진짜 HWPX zip)
     * false. CFB 이면 FileHeader 스트림의 properties DWORD(offset 36) bit 2(배포용)
     * 를 검사한다. FileHeader 가 없거나 파싱 실패면 false(일반 진행).
     */
    private static boolean isDistributedHwp(String path) {
        if (path == null) return false;
        java.io.File f = new java.io.File(path);
        if (!f.isFile()) return false;
        // 1) CFB(OLE2) 매직 확인 — 아니면 배포용 HWP 아님
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            byte[] magic = new byte[8];
            int n = 0;
            while (n < 8) { int r = fis.read(magic, n, 8 - n); if (r < 0) break; n += r; }
            byte[] cfb = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                          (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
            if (n < 8 || !java.util.Arrays.equals(magic, cfb)) return false;
        } catch (java.io.IOException e) {
            return false;
        }
        // 2) FileHeader 스트림 properties bit 2 검사
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f);
             org.apache.poi.poifs.filesystem.POIFSFileSystem pfs =
                     new org.apache.poi.poifs.filesystem.POIFSFileSystem(fis)) {
            org.apache.poi.poifs.filesystem.DocumentEntry fh =
                    (org.apache.poi.poifs.filesystem.DocumentEntry) pfs.getRoot().getEntry("FileHeader");
            try (org.apache.poi.poifs.filesystem.DocumentInputStream dis =
                         new org.apache.poi.poifs.filesystem.DocumentInputStream(fh)) {
                byte[] buf = new byte[40];
                int n = 0;
                while (n < 40) { int r = dis.read(buf, n, 40 - n); if (r < 0) break; n += r; }
                if (n < 40) return false;
                int props = (buf[36] & 0xFF) | ((buf[37] & 0xFF) << 8)
                          | ((buf[38] & 0xFF) << 16) | ((buf[39] & 0xFF) << 24);
                return (props & 0x04) != 0;  // bit 2 = 배포용 문서
            }
        } catch (Exception e) {
            return false;  // FileHeader 없음/파싱 실패 → 일반 문서로 간주
        }
    }

    /**
     * HWP 본문의 모든 일반 텍스트 문자를 스캔하여 등장 순서대로 astral-plane
     * 코드 포인트(Supplementary Multilingual Plane 이상)를 수집한다.
     * 각 high+low UTF-16 surrogate pair를 하나의 유니코드 코드 포인트로 결합한다.
     *
     * <p>이 코드 포인트들은 HWP→HWPX 변환 중에 손실된다. hwplib의
     * {@code HWPCharNormal.getCh()}가 16-bit 코드 유닛을 독립적으로 디코딩하여
     * 단독 surrogate를 U+FFFD로 치환하기 때문이다. 수집된 리스트는 이후
     * {@link kr.n.nframe.hwplib.writer.HwpxXmlRewriter#recoverAstralChars}에서
     * HWPX XML의 원본 문자를 복원하는 데 사용된다.
     */
    private static java.util.List<Integer> collectHwpAstralCodePoints(HWPFile hwp) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        if (hwp == null || hwp.getBodyText() == null) return out;
        for (kr.dogfoot.hwplib.object.bodytext.Section sec : hwp.getBodyText().getSectionList()) {
            for (kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph p : sec) {
                collectFromParagraph(p, out);
            }
        }
        return out;
    }

    /**
     * HWPX 출력에서 복원할 수 있도록 HWP 인라인 탭 파라미터를 문서 순서대로 수집한다
     * (hwp2hwpx는 이 값들을 하드코딩된 기본값으로 떨어뜨림).
     *
     * <p>코드 0x0009인 HWPCharControlInline의 탭 레이아웃
     * (HWP 5.0 §5.4, 인라인 컨트롤 정보 = 12 byte):
     * <pre>
     *   offset 0..3 : UINT32 LE   탭 너비 (HWPUNIT)
     *   offset 4    : BYTE        리더 스타일 (0=NONE, 1=SOLID, 2=DASH,
     *                             3=DOT, 4=DASH_DOT, 5=DASH_DOT_DOT, …)
     *   offset 5    : BYTE        탭 항목 종류 (0=LEFT, 1=RIGHT,
     *                             2=CENTER, 3=DECIMAL)
     *   offset 6..11: BYTE[6]     패딩 (일반적으로 ASCII 공백 3개)
     * </pre>
     *
     * @return 탭별 int[]{width, leader, type} 리스트, 소스 순서대로
     */
    private static java.util.List<int[]> collectHwpTabSettings(HWPFile hwp) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        if (hwp == null || hwp.getBodyText() == null) return out;
        for (kr.dogfoot.hwplib.object.bodytext.Section sec : hwp.getBodyText().getSectionList()) {
            for (kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph p : sec) {
                collectTabsFromParagraph(p, out);
            }
        }
        return out;
    }

    /**
     * 소스 HWP 의 책갈피(ControlBookmark) 를 paragraph 별로 수집한다.
     *
     * <p>v15.11: hwpxlib 1.0.9 의 writer 에 BookmarkWriter 가 부재하여
     * hwp2hwpx 가 만든 Bookmark 객체가 HWPX XML 로 직렬화되지 않는다 →
     * 책갈피 target 이 사라져 ?bookmark_name 하이퍼링크가 한/글에서 동작하지
     * 않음. 이 메서드로 paragraph 인덱스 → 책갈피 이름 리스트를 수집한 뒤
     * HwpxXmlRewriter 에서 &lt;hp:ctrl&gt;&lt;hp:bookmark name="..."/&gt;&lt;/hp:ctrl&gt;
     * 를 첫 번째 섹션의 해당 위치에 직접 주입한다.</p>
     *
     * @return outer = 섹션 0 paragraph 인덱스, inner = 해당 paragraph 에 부착된
     *         책갈피 이름들 (순서 보존). 일반적으로 paragraph 당 책갈피는 0~1 개.
     */
    private static java.util.List<java.util.List<String>> collectHwpBookmarks(HWPFile hwp) {
        java.util.List<java.util.List<String>> result = new java.util.ArrayList<>();
        if (hwp == null || hwp.getBodyText() == null) return result;
        if (hwp.getBodyText().getSectionList().isEmpty()) return result;
        kr.dogfoot.hwplib.object.bodytext.Section sec = hwp.getBodyText().getSectionList().get(0);
        for (kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph p : sec) {
            java.util.List<String> names = new java.util.ArrayList<>();
            if (p != null && p.getControlList() != null) {
                for (kr.dogfoot.hwplib.object.bodytext.control.Control c : p.getControlList()) {
                    // HWP 의 책갈피는 두 가지 형태가 있다:
                    //   1) ControlBookmark      — standalone CTRL_HEADER 'bmkt'
                    //   2) ControlField (FIELD_BOOKMARK) — extended field '%bmk'
                    // 실제 입찰공고문/TA-03 등은 (2) 케이스. getType() 으로 분기.
                    boolean isBookmark = (c instanceof kr.dogfoot.hwplib.object.bodytext.control.ControlBookmark)
                            || (c instanceof kr.dogfoot.hwplib.object.bodytext.control.ControlField
                                && c.getType() == kr.dogfoot.hwplib.object.bodytext.control.ControlType.FIELD_BOOKMARK);
                    if (isBookmark) {
                        String name = extractBookmarkName(c);
                        if (name != null && !name.isEmpty()) names.add(name);
                    }
                }
            }
            result.add(names);
        }
        return result;
    }

    /**
     * 책갈피 이름 추출. ControlField 는 getName() 으로 직접, ControlBookmark 는
     * CtrlData ParameterSet 의 itemId 0x4000 String 값으로 추출.
     */
    private static String extractBookmarkName(kr.dogfoot.hwplib.object.bodytext.control.Control c) {
        try {
            if (c instanceof kr.dogfoot.hwplib.object.bodytext.control.ControlField) {
                String n = ((kr.dogfoot.hwplib.object.bodytext.control.ControlField) c).getName();
                if (n != null && !n.isEmpty()) return n;
            }
            kr.dogfoot.hwplib.object.bodytext.control.bookmark.CtrlData cd = c.getCtrlData();
            if (cd == null) return null;
            kr.dogfoot.hwplib.object.bodytext.control.bookmark.ParameterSet ps = cd.getParameterSet();
            if (ps == null) return null;
            kr.dogfoot.hwplib.object.bodytext.control.bookmark.ParameterItem item =
                    ps.getParameterItem(0x4000);
            if (item == null) return null;
            if (item.getType() != kr.dogfoot.hwplib.object.bodytext.control.bookmark.ParameterType.String)
                return null;
            return item.getValue_BSTR();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void collectTabsFromParagraph(
            kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph p,
            java.util.List<int[]> out) {
        if (p == null) return;
        kr.dogfoot.hwplib.object.bodytext.paragraph.text.ParaText t = p.getText();
        if (t != null && t.getCharList() != null) {
            for (kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPChar c : t.getCharList()) {
                if (!(c instanceof kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharControlInline)) continue;
                int code = c.getCode() & 0xFFFF;
                if (code != 0x0009) continue; // TAB 인라인 컨트롤만
                kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharControlInline inl =
                        (kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharControlInline) c;
                byte[] ad = inl.getAddition();
                if (ad == null || ad.length < 6) { out.add(new int[]{4000, 0, 0}); continue; }
                long w = ((long)(ad[0] & 0xFF))
                       | ((long)(ad[1] & 0xFF) << 8)
                       | ((long)(ad[2] & 0xFF) << 16)
                       | ((long)(ad[3] & 0xFF) << 24);
                int leader = ad[4] & 0xFF;
                int type = ad[5] & 0xFF;
                out.add(new int[]{(int)(w & 0x7FFFFFFF), leader, type});
            }
        }
        if (p.getControlList() != null) {
            for (kr.dogfoot.hwplib.object.bodytext.control.Control c : p.getControlList()) {
                collectTabsFromControl(c, out);
            }
        }
    }

    private static void collectTabsFromControl(
            kr.dogfoot.hwplib.object.bodytext.control.Control c,
            java.util.List<int[]> out) {
        try {
            try {
                java.lang.reflect.Method mm = c.getClass().getMethod("getParagraphList");
                Object pl = mm.invoke(c);
                if (pl != null) {
                    int cnt = (int) pl.getClass().getMethod("getParagraphCount").invoke(pl);
                    java.lang.reflect.Method getM = pl.getClass().getMethod("getParagraph", int.class);
                    for (int i = 0; i < cnt; i++) {
                        collectTabsFromParagraph(
                                (kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph) getM.invoke(pl, i),
                                out);
                    }
                }
            } catch (NoSuchMethodException nsme) { /* 모든 컨트롤이 이 메서드를 갖지는 않음 */ }
            if (c instanceof kr.dogfoot.hwplib.object.bodytext.control.ControlTable) {
                kr.dogfoot.hwplib.object.bodytext.control.ControlTable t =
                        (kr.dogfoot.hwplib.object.bodytext.control.ControlTable) c;
                for (kr.dogfoot.hwplib.object.bodytext.control.table.Row row : t.getRowList()) {
                    for (kr.dogfoot.hwplib.object.bodytext.control.table.Cell cell : row.getCellList()) {
                        Object pl = cell.getParagraphList();
                        if (pl == null) continue;
                        int cnt = (int) pl.getClass().getMethod("getParagraphCount").invoke(pl);
                        java.lang.reflect.Method getM = pl.getClass().getMethod("getParagraph", int.class);
                        for (int i = 0; i < cnt; i++) {
                            collectTabsFromParagraph(
                                    (kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph) getM.invoke(pl, i),
                                    out);
                        }
                    }
                }
            }
        } catch (Exception e) { /* best-effort */ }
    }

    private static void collectFromParagraph(
            kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph p,
            java.util.List<Integer> out) {
        if (p == null) return;
        kr.dogfoot.hwplib.object.bodytext.paragraph.text.ParaText t = p.getText();
        if (t != null && t.getCharList() != null) {
            java.util.List<kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPChar> chars = t.getCharList();
            for (int i = 0; i < chars.size(); i++) {
                kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPChar c = chars.get(i);
                if (c.getType() != kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharType.Normal) continue;
                int code = c.getCode() & 0xFFFF;
                if (Character.isHighSurrogate((char) code) && i + 1 < chars.size()) {
                    kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPChar next = chars.get(i + 1);
                    if (next.getType() == kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharType.Normal) {
                        int low = next.getCode() & 0xFFFF;
                        if (Character.isLowSurrogate((char) low)) {
                            int cp = Character.toCodePoint((char) code, (char) low);
                            out.add(cp);
                            i++; // low surrogate 소비
                        }
                    }
                }
            }
        }
        // 표 셀 / 머리말·꼬리말 하위 리스트로 재귀
        if (p.getControlList() != null) {
            for (kr.dogfoot.hwplib.object.bodytext.control.Control c : p.getControlList()) {
                collectFromControl(c, out);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectFromControl(
            kr.dogfoot.hwplib.object.bodytext.control.Control c,
            java.util.List<Integer> out) {
        // 리플렉션으로 getParagraphList() 를 탐색 — hwplib 는 Control 서브클래스
        // (Table, Header, Footer, Textbox …) 마다 문단 중첩 방식이 다름.
        try {
            // 직접 getParagraphList (예: Header/Footer)
            java.lang.reflect.Method m;
            try {
                m = c.getClass().getMethod("getParagraphList");
                Object pl = m.invoke(c);
                if (pl != null) {
                    java.lang.reflect.Method cntM = pl.getClass().getMethod("getParagraphCount");
                    java.lang.reflect.Method getM = pl.getClass().getMethod("getParagraph", int.class);
                    int cnt = (int) cntM.invoke(pl);
                    for (int i = 0; i < cnt; i++) {
                        collectFromParagraph(
                                (kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph) getM.invoke(pl, i),
                                out);
                    }
                }
            } catch (NoSuchMethodException nsme) { /* not all controls have it */ }

            // 표: 행·셀 순회
            if (c instanceof kr.dogfoot.hwplib.object.bodytext.control.ControlTable) {
                kr.dogfoot.hwplib.object.bodytext.control.ControlTable t =
                        (kr.dogfoot.hwplib.object.bodytext.control.ControlTable) c;
                for (kr.dogfoot.hwplib.object.bodytext.control.table.Row row : t.getRowList()) {
                    for (kr.dogfoot.hwplib.object.bodytext.control.table.Cell cell : row.getCellList()) {
                        Object pl = cell.getParagraphList();
                        if (pl == null) continue;
                        int cnt = (int) pl.getClass().getMethod("getParagraphCount").invoke(pl);
                        java.lang.reflect.Method getM = pl.getClass().getMethod("getParagraph", int.class);
                        for (int i = 0; i < cnt; i++) {
                            collectFromParagraph(
                                    (kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph) getM.invoke(pl, i),
                                    out);
                        }
                    }
                }
            }
        } catch (Exception e) { /* best-effort scan */ }
    }

    /**
     * HWP 파일의 FileHeader 스트림을 열어 배포용 플래그(properties bit 2)를 검사한다.
     * OLE2 구조를 POI 로 직접 읽고 offset 36 (properties UINT32) 의 bit 2 만 본다.
     * 파일이 HWP 가 아니거나 읽을 수 없으면 false 반환(비배포용으로 간주).
     */
    private static boolean isDistributionHwp(String path) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(path);
             org.apache.poi.poifs.filesystem.POIFSFileSystem fs =
                     new org.apache.poi.poifs.filesystem.POIFSFileSystem(fis)) {
            org.apache.poi.poifs.filesystem.DirectoryEntry root = fs.getRoot();
            if (!root.hasEntry("FileHeader")) return false;
            org.apache.poi.poifs.filesystem.DocumentEntry hdr =
                    (org.apache.poi.poifs.filesystem.DocumentEntry) root.getEntry("FileHeader");
            byte[] data = new byte[Math.min(hdr.getSize(), 256)];
            try (org.apache.poi.poifs.filesystem.DocumentInputStream dis =
                         new org.apache.poi.poifs.filesystem.DocumentInputStream(hdr)) {
                dis.readFully(data);
            }
            if (data.length < 40) return false;
            int props = (data[36] & 0xFF)
                    | ((data[37] & 0xFF) << 8)
                    | ((data[38] & 0xFF) << 16)
                    | ((data[39] & 0xFF) << 24);
            return (props & 0x04) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Are any shell metacharacters present that cmd.exe would consume
     *  (redirection / pipe / escape) unless the argument is quoted? */
    private static boolean containsUnescapedShellMeta(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' || c == '>' || c == '|' || c == '&' || c == '^') return true;
        }
        return false;
    }

    /** Does the argument look like an HWP/HWPX file path? */
    private static boolean looksLikeHwpPath(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        return lower.endsWith(".hwp") || lower.endsWith(".hwpx");
    }

    /**
     * v13.35: 경로가 "출력 파일" 로 보이는지 (vs. 디렉터리) 판별.
     * HWP/HWPX/MD 확장자로 끝나면 파일로 간주하고, 그 외는 디렉터리 후보로 본다.
     * 다건 파일 지정 vs 단건 구분에 사용.
     */
    private static boolean looksLikeOutputFilePath(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        return lower.endsWith(".hwp") || lower.endsWith(".hwpx") || lower.endsWith(".md");
    }

    /**
     * {@code --dist} 에 공백으로 분리된 경로 인자가 들어왔을 때
     * 한글 안내 메시지를 출력한다. 사용자가 의도한 입력/출력/암호 를
     * 재조합해서 보여주고, 따옴표로 올바르게 감싼 명령 예시를 함께 출력한다.
     */
    private static void printDistQuotingError(
            java.util.List<String> positional, boolean noCopy, boolean noPrint) {
        // 사용자 의도 재조합:
        //   ... <.hwp(x) 로 끝나는 경로> <.hwp(x) 로 끝나는 경로> <암호>
        // 인접 조각들을 공백 1개로 그리디하게 이어붙여
        // 첫 번째 조각이 파일 경로처럼 보일 때까지 반복.
        String inputPath = null, outputPath = null, password = null;
        int i = 0, n = positional.size();
        StringBuilder sb = new StringBuilder();
        for (; i < n && inputPath == null; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(positional.get(i));
            if (looksLikeHwpPath(sb.toString())) {
                inputPath = sb.toString();
                sb.setLength(0);
            }
        }
        for (; i < n && outputPath == null; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(positional.get(i));
            if (looksLikeHwpPath(sb.toString())) {
                outputPath = sb.toString();
                sb.setLength(0);
            }
        }
        if (i < n) {
            StringBuilder pw = new StringBuilder();
            for (; i < n; i++) {
                if (pw.length() > 0) pw.append(' ');
                pw.append(positional.get(i));
            }
            password = pw.toString();
        }

        System.err.println();
        System.err.println("[오류] --dist 인자 해석에 실패했습니다.");
        System.err.println("        경로 또는 암호에 공백이 포함되어 있는데 큰따옴표(\"\")로 감싸지 않아");
        System.err.println("        명령 셸이 공백 기준으로 인자를 여러 개로 나누었습니다.");
        System.err.println("        또는 암호에 셸 특수문자(< > | & ^) 가 있어 cmd.exe 가");
        System.err.println("        리다이렉션으로 해석해 인자가 사라졌을 수 있습니다.");
        System.err.println();
        System.err.println("  [수신된 위치 인자 " + positional.size() + "개]");
        for (int k = 0; k < positional.size(); k++) {
            System.err.println("     args[" + (k + 1) + "] = " + positional.get(k));
        }
        System.err.println();
        if (inputPath != null && outputPath != null && password != null) {
            System.err.println("  [추정한 의도]");
            System.err.println("     input    = " + inputPath);
            System.err.println("     output   = " + outputPath);
            System.err.println("     password = " + password);
            System.err.println();
            System.err.println("  [올바른 명령 예시 — 공백이 있는 경로·암호는 반드시 큰따옴표로 감싸세요]");
            StringBuilder cmd = new StringBuilder();
            cmd.append("     hwpConverter.bat --dist ");
            cmd.append('"').append(inputPath).append("\" ");
            cmd.append('"').append(outputPath).append("\" ");
            cmd.append('"').append(password).append('"');
            if (noCopy) cmd.append(" --no-copy");
            if (noPrint) cmd.append(" --no-print");
            System.err.println(cmd.toString());
        } else {
            System.err.println("  [올바른 형식]");
            System.err.println("     hwpConverter.bat --dist \"<input.hwp>\" \"<output.hwp>\" \"<password>\" [--no-copy] [--no-print]");
            System.err.println();
            System.err.println("  공백이 포함된 경로·암호는 반드시 큰따옴표(\") 로 감싸야 합니다.");
        }
        System.err.println();
    }

    /**
     * 암호에 셸 특수문자(&lt;, &gt;, |, &amp;, ^) 가 포함된 경우 안내를 출력.
     * 큰따옴표로 감싸지 않으면 Windows cmd.exe 가 이들을 리다이렉션/파이프
     * 토큰으로 소모한다. 예: 암호가 {@code <script>} 이면 cmd 는 이를
     * {@code script} 파일로부터의 입력 리다이렉트로 해석해 실제 암호가
     * Java 로 전달되지 않는다.
     */
    private static void printDistPasswordQuotingError(
            String inputPath, String outputPath, String password,
            boolean noCopy, boolean noPrint) {
        System.err.println();
        System.err.println("[오류] 암호에 셸 특수문자가 감지되었습니다. 큰따옴표(\") 로 감싸야 합니다.");
        System.err.println("        Windows cmd.exe 는 < > | & ^ 문자를 인자가 아닌");
        System.err.println("        리다이렉션/파이프/이스케이프 기호로 해석합니다.");
        System.err.println();
        System.err.println("  [수신된 값]");
        System.err.println("     input    = " + inputPath);
        System.err.println("     output   = " + outputPath);
        System.err.println("     password = " + password + "   ← 이 값 안에 < > | & ^ 중 하나가 있음");
        System.err.println();
        System.err.println("  [올바른 명령 예시]");
        StringBuilder cmd = new StringBuilder("     hwpConverter.bat --dist ");
        cmd.append('"').append(inputPath).append("\" ");
        cmd.append('"').append(outputPath).append("\" ");
        cmd.append('"').append(password).append('"');
        if (noCopy) cmd.append(" --no-copy");
        if (noPrint) cmd.append(" --no-print");
        System.err.println(cmd.toString());
        System.err.println();
        System.err.println("  ※ 한글 프로그램의 \"배포용 문서로 저장\" 기능도 암호에 < > 등을 허용하므로");
        System.err.println("     큰따옴표로만 감싸면 동일한 암호를 사용할 수 있습니다.");
        System.err.println();
    }

    // ==========================================================
    //  배치(다건) 모드 지원
    // ==========================================================

    /**
     * 배치 변환 결과 집계.
     *
     * <p>라이브러리 사용자가 성공/실패 건수와 실패 목록을 조회할 수 있도록 public 으로 공개.
     * 필드 접근과 getter 를 모두 지원한다 (읽기 전용 스냅샷으로 간주).
     */
    public static final class BatchResult {
        /** 변환 성공 건수 */
        public int ok = 0;
        /** 변환 실패 건수 (SKIP 은 여기 포함되지 않음) */
        public int fail = 0;
        /** 실패한 파일별 상세 메시지 ("파일명 : 원인" 형식) */
        public final java.util.List<String> failDetails = new java.util.ArrayList<>();

        public int getOk()                               { return ok; }
        public int getFail()                             { return fail; }
        public java.util.List<String> getFailDetails()   { return failDetails; }
    }

    /** 주어진 디렉터리에서 확장자(소문자)가 일치하는 파일만 비재귀적으로 수집 */
    private static java.util.List<java.io.File> listByExt(java.io.File dir, String... exts) {
        java.util.List<java.io.File> out = new java.util.ArrayList<>();
        java.io.File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (java.io.File f : fs) {
            if (!f.isFile()) continue;
            String low = f.getName().toLowerCase();
            // v13.35: MD 사이드카 ({md}.origin.hwp / {md}.origin.hwpx) 는 배치 입력에서 제외.
            //         폴더 안에 .md 와 그 사이드카가 같이 있어도 auto-detect 가 .hwp 로
            //         잘못 분기되지 않도록 한다. 사이드카는 MD 역변환 시 내부적으로만 참조된다.
            if (low.endsWith(".origin.hwp") || low.endsWith(".origin.hwpx")) continue;
            for (String ext : exts) {
                if (low.endsWith(ext)) { out.add(f); break; }
            }
        }
        java.util.Collections.sort(out, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    /**
     * 다건-개별-파일 모드에서 입력 경로를 사전 검증한다.
     * 반환값:
     *   · 정상 파일: File 객체
     *   · 실패: null + r.fail++ + r.failDetails 에 에러 기록
     *   · 스킵(관용 처리): null, r.fail 증가 없음, INFO 로그만 출력
     *                    skipCounter 배열의 [0] 를 1 증가시킨다.
     *
     * <p>스킵 대상(에러 아님):
     *   · 빈 문자열 / 공백만
     *   · 디렉터리 (v13.7 부터) — 출력 디렉터리를 중복 지정한 실수로 간주
     *
     * <p>실패 대상(에러):
     *   · Windows 금칙 문자 포함 — 거의 확실히 암호를 잘못 자리매김
     *   · 존재하지 않는 파일
     *   · .hwp / .hwpx 가 아닌 확장자
     */
    private static java.io.File validateInputFileForBatch(String rawPath, BatchResult r, int[] skipCounter) {
        return validateInputFileForBatch(rawPath, r, skipCounter, ".hwp", ".hwpx");
    }

    /**
     * v13.35: 허용 확장자 파라미터화.
     * 기존 호출(.hwp/.hwpx) 은 위 단축 시그니처가 그대로 사용한다.
     */
    private static java.io.File validateInputFileForBatch(String rawPath, BatchResult r,
                                                          int[] skipCounter, String... allowedExts) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            skipCounter[0]++;
            return null;
        }
        if (rawPath.matches(".*[<>|?*\"].*")) {
            r.fail++;
            String msg = rawPath + " : 유효하지 않은 파일명(Windows 금칙 문자 포함). "
                    + "암호 인자가 입력 파일 자리에 잘못 들어갔을 수 있습니다.";
            r.failDetails.add(msg);
            System.err.println("  [FAIL] " + msg);
            return null;
        }
        java.io.File f = new java.io.File(rawPath);
        if (!f.exists()) {
            r.fail++;
            String msg = rawPath + " : 파일이 존재하지 않습니다.";
            r.failDetails.add(msg);
            System.err.println("  [FAIL] " + msg);
            return null;
        }
        if (f.isDirectory()) {
            skipCounter[0]++;
            System.out.println("  [SKIP] " + rawPath
                    + " : 디렉터리 - 출력 디렉터리를 중복 지정한 것으로 보여 건너뜁니다.");
            return null;
        }
        String low = f.getName().toLowerCase();
        boolean ok = false;
        for (String ae : allowedExts) {
            if (low.endsWith(ae)) { ok = true; break; }
        }
        if (!ok) {
            r.fail++;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < allowedExts.length; i++) {
                if (i > 0) sb.append(" / ");
                sb.append(allowedExts[i]);
            }
            String msg = rawPath + " : " + sb + " 확장자가 아닙니다.";
            r.failDetails.add(msg);
            System.err.println("  [FAIL] " + msg);
            return null;
        }
        return f;
    }

    /** 출력 디렉터리 준비 (없으면 생성) */
    private static void ensureOutputDir(java.io.File dir) throws IOException {
        if (dir.exists()) {
            if (!dir.isDirectory()) {
                throw new IOException("출력 경로가 파일로 존재합니다. 디렉터리여야 합니다: " + dir);
            }
            return;
        }
        if (!dir.mkdirs()) {
            // v13.33: 더 친절한 에러 메시지 — 경로에 leading/trailing 공백이 있는지 감지
            String path = dir.getPath();
            String hint = "";
            if (!path.equals(path.trim())) {
                hint = "\n  [원인 추정] 경로 앞/뒤에 공백이 포함되어 있습니다:"
                     + " [\"" + path + "\"] (길이 " + path.length() + ")"
                     + "\n  [해결]     명령어에서 따옴표와 경로 사이의 공백을 제거하세요."
                     + "\n             예) 잘못됨: \" C:\\work\\out\"   →   올바름: \"C:\\work\\out\"";
            } else if (!dir.getAbsoluteFile().getParentFile().exists()) {
                hint = "\n  [원인 추정] 상위 디렉터리가 존재하지 않습니다:"
                     + "\n             상위: " + dir.getAbsoluteFile().getParentFile();
            }
            throw new IOException("출력 디렉터리 생성 실패: " + dir + hint);
        }
    }

    /** 입력 파일명 기반으로 출력 파일 경로 생성 (확장자 교체) */
    private static java.io.File outFileFor(java.io.File in, java.io.File outDir, String newExt) {
        String name = in.getName();
        int dot = name.lastIndexOf('.');
        String base = (dot >= 0) ? name.substring(0, dot) : name;
        return new java.io.File(outDir, base + newExt);
    }

    /** HWPX → HWP 배치 */
    public BatchResult batchHwpxToHwp(String inputDir, String outputDir) throws IOException {
        java.io.File in = new java.io.File(inputDir);
        java.io.File out = new java.io.File(outputDir);
        ensureOutputDir(out);
        java.util.List<java.io.File> files = listByExt(in, ".hwpx");
        System.out.println("[Batch] HWPX → HWP : " + files.size() + " files in " + in.getAbsolutePath());
        BatchResult r = new BatchResult();
        for (int i = 0; i < files.size(); i++) {
            java.io.File f = files.get(i);
            java.io.File o = outFileFor(f, out, ".hwp");
            System.out.println("[" + (i + 1) + "/" + files.size() + "] " + f.getName() + " → " + o.getName());
            try {
                convertHwpxToHwp(f.getAbsolutePath(), kr.n.nframe.newfeature.OutputNaming.unique(o.getAbsolutePath()));
                r.ok++;
            } catch (Exception e) {
                r.fail++;
                r.failDetails.add(f.getName() + " : " + describeException(e));
                System.err.println("  [FAIL] " + describeException(e));
            }
        }
        printBatchSummary(r, files.size());
        return r;
    }

    /** HWP → HWPX 배치 */
    public BatchResult batchHwpToHwpx(String inputDir, String outputDir) throws IOException {
        java.io.File in = new java.io.File(inputDir);
        java.io.File out = new java.io.File(outputDir);
        ensureOutputDir(out);
        java.util.List<java.io.File> files = listByExt(in, ".hwp");
        System.out.println("[Batch] HWP → HWPX : " + files.size() + " files in " + in.getAbsolutePath());
        BatchResult r = new BatchResult();
        for (int i = 0; i < files.size(); i++) {
            java.io.File f = files.get(i);
            java.io.File o = outFileFor(f, out, ".hwpx");
            System.out.println("[" + (i + 1) + "/" + files.size() + "] " + f.getName() + " → " + o.getName());
            try {
                convertHwpToHwpx(f.getAbsolutePath(), kr.n.nframe.newfeature.OutputNaming.unique(o.getAbsolutePath()));
                r.ok++;
            } catch (Exception e) {
                r.fail++;
                r.failDetails.add(f.getName() + " : " + describeException(e));
                System.err.println("  [FAIL] " + describeException(e));
            }
        }
        printBatchSummary(r, files.size());
        return r;
    }

    /**
     * --dist 배치 (입력 디렉터리 내 .hwp/.hwpx 모두 처리).
     * 출력 파일 확장자는 단건 모드와 동일하게 사용자가 지정 가능:
     *   · forceOutExt == null  → 입력 확장자 보존 (.hwp→.hwp, .hwpx→.hwpx)
     *   · forceOutExt == ".hwp"  / ".hwpx" → 모든 출력 파일 강제 지정
     * (DistributionWriter는 경로와 무관하게 HWP 바이너리를 기록한다.
     *  단건 모드에서 --dist input.hwp output.hwpx 로 .hwpx 출력 파일을 만들 수
     *  있던 동작을 배치에서도 재현한다.)
     */
    public BatchResult batchDist(String inputDir, String outputDir,
                                 String password, boolean noCopy, boolean noPrint,
                                 String forceOutExt) throws IOException {
        java.io.File in = new java.io.File(inputDir);
        java.io.File out = new java.io.File(outputDir);
        ensureOutputDir(out);
        java.util.List<java.io.File> files = listByExt(in, ".hwp", ".hwpx");
        System.out.println("[Batch] --dist : " + files.size() + " files in " + in.getAbsolutePath()
                + "  (noCopy=" + noCopy + ", noPrint=" + noPrint
                + ", outExt=" + (forceOutExt == null ? "(입력 확장자 보존)" : forceOutExt) + ")");
        BatchResult r = new BatchResult();
        for (int i = 0; i < files.size(); i++) {
            java.io.File f = files.get(i);
            String outExt = (forceOutExt != null) ? forceOutExt
                    : (f.getName().toLowerCase().endsWith(".hwpx") ? ".hwpx" : ".hwp");
            java.io.File o = outFileFor(f, out, outExt);
            System.out.println("[" + (i + 1) + "/" + files.size() + "] " + f.getName() + " → " + o.getName());
            try {
                makeHwpDist(f.getAbsolutePath(), o.getAbsolutePath(), password, noCopy, noPrint);
                r.ok++;
            } catch (Exception e) {
                r.fail++;
                r.failDetails.add(f.getName() + " : " + describeException(e));
                System.err.println("  [FAIL] " + describeException(e));
            }
        }
        printBatchSummary(r, files.size());
        return r;
    }

    /** 이전 시그니처 호환 (확장자 보존 기본) */
    public BatchResult batchDist(String inputDir, String outputDir,
                                 String password, boolean noCopy, boolean noPrint) throws IOException {
        return batchDist(inputDir, outputDir, password, noCopy, noPrint, null);
    }

    // ==========================================================
    //  다건 개별 파일 지정 모드 (v13.2)
    //  폴더 전체가 아닌 특정 파일 N개를 골라서 변환
    // ==========================================================

    /**
     * 개별 파일 리스트 → 일반 변환 배치.
     * v13.35: 입력 확장자별 자동 라우팅 —
     *   · .hwp  → .hwpx : convertHwpToHwpx
     *   · .hwpx → .hwp  : convertHwpxToHwp
     *   · .md   → .hwp  : convertMarkdownToHwp   (사이드카 필요)
     *   · .md   → .hwpx : convertMarkdownToHwpx  (사이드카 필요)
     */
    public BatchResult batchFiles(java.util.List<java.io.File> inputFiles,
                                  String outputDir, String toExt) throws IOException {
        java.io.File out = new java.io.File(outputDir);
        ensureOutputDir(out);
        int given = inputFiles.size();
        BatchResult r = new BatchResult();
        int[] skipCounter = new int[]{0};
        // 입력 파일 사전 검증 — .hwp / .hwpx / .md 모두 허용 (v13.35)
        java.util.List<java.io.File> valid = new java.util.ArrayList<>();
        for (java.io.File raw : inputFiles) {
            java.io.File v = validateInputFileForBatch(raw.getPath(), r, skipCounter,
                    ".hwp", ".hwpx", ".md");
            if (v != null) valid.add(v);
        }
        int total = valid.size();
        int effectiveRequested = given - skipCounter[0];
        System.out.println("[Batch-Files] " + total + " / " + effectiveRequested + " files → "
                + out.getAbsolutePath() + "  (toExt=" + toExt + ")"
                + (r.fail > 0 ? "  (사전 검증 실패 " + r.fail + "건 제외)" : "")
                + (skipCounter[0] > 0 ? "  (SKIP " + skipCounter[0] + "건 - 입력 자리의 디렉터리/빈 문자열)" : ""));
        boolean toHwpx = ".hwpx".equals(toExt);
        HwpMdConverter md = null;
        for (int i = 0; i < total; i++) {
            java.io.File f = valid.get(i);
            java.io.File o = outFileFor(f, out, toExt);
            String inLow = f.getName().toLowerCase();
            System.out.println("[" + (i + 1) + "/" + total + "] " + f.getAbsolutePath() + " → " + o.getName());
            try {
                if (inLow.endsWith(".md")) {
                    // v13.35: MD 입력 → 사이드카 기반 역변환
                    if (md == null) md = new HwpMdConverter();
                    if (toHwpx) md.convertMarkdownToHwpx(f.getAbsolutePath(), o.getAbsolutePath());
                    else        md.convertMarkdownToHwp(f.getAbsolutePath(), o.getAbsolutePath());
                } else if (inLow.endsWith(".hwpx")) {
                    if (toHwpx) {
                        // 동일 포맷 변환 요청 — .hwpx → .hwpx 은 의미 없음, 경고
                        throw new IOException("입력과 출력 포맷이 같습니다 (.hwpx → .hwpx). --to-hwp 를 쓰거나 MD 사이드카를 이용하세요.");
                    }
                    convertHwpxToHwp(f.getAbsolutePath(), kr.n.nframe.newfeature.OutputNaming.unique(o.getAbsolutePath()));
                } else {
                    // .hwp
                    if (!toHwpx) {
                        throw new IOException("입력과 출력 포맷이 같습니다 (.hwp → .hwp). --to-hwpx 를 쓰거나 MD 사이드카를 이용하세요.");
                    }
                    convertHwpToHwpx(f.getAbsolutePath(), kr.n.nframe.newfeature.OutputNaming.unique(o.getAbsolutePath()));
                }
                r.ok++;
            } catch (Exception e) {
                r.fail++;
                r.failDetails.add(f.getName() + " : " + describeException(e));
                System.err.println("  [FAIL] " + describeException(e));
            }
        }
        printBatchSummary(r, effectiveRequested);
        return r;
    }

    /**
     * 폴더 전체 → Markdown 배치 (v13.16).
     * 입력 디렉터리 내 .hwp / .hwpx 파일 전부를 .md 로 변환.
     */
    public BatchResult batchAnyToMd(String inputDir, String outputDir) throws IOException {
        java.io.File in = new java.io.File(inputDir);
        java.io.File out = new java.io.File(outputDir);
        ensureOutputDir(out);
        java.util.List<java.io.File> files = listByExt(in, ".hwp", ".hwpx");
        System.out.println("[Batch] HWP/HWPX → Markdown : " + files.size()
                + " files in " + in.getAbsolutePath());
        BatchResult r = new BatchResult();
        HwpMdConverter md = new HwpMdConverter();
        for (int i = 0; i < files.size(); i++) {
            java.io.File f = files.get(i);
            java.io.File o = outFileFor(f, out, ".md");
            System.out.println("[" + (i + 1) + "/" + files.size() + "] "
                    + f.getName() + " → " + o.getName());
            try {
                if (f.getName().toLowerCase().endsWith(".hwpx")) {
                    md.convertHwpxToMarkdown(f.getAbsolutePath(), o.getAbsolutePath());
                } else {
                    md.convertHwpToMarkdown(f.getAbsolutePath(), o.getAbsolutePath());
                }
                r.ok++;
            } catch (Exception e) {
                r.fail++;
                r.failDetails.add(f.getName() + " : " + describeException(e));
                System.err.println("  [FAIL] " + describeException(e));
            }
        }
        printBatchSummary(r, files.size());
        return r;
    }

    /**
     * 개별 파일 리스트 → Markdown 배치 (v13.16).
     */
    public BatchResult batchFilesToMd(java.util.List<java.io.File> inputFiles,
                                      String outputDir) throws IOException {
        java.io.File out = new java.io.File(outputDir);
        ensureOutputDir(out);
        int given = inputFiles.size();
        BatchResult r = new BatchResult();
        int[] skipCounter = new int[]{0};
        java.util.List<java.io.File> valid = new java.util.ArrayList<>();
        for (java.io.File raw : inputFiles) {
            java.io.File v = validateInputFileForBatch(raw.getPath(), r, skipCounter);
            if (v != null) valid.add(v);
        }
        int total = valid.size();
        int effectiveRequested = given - skipCounter[0];
        System.out.println("[Batch-Files] HWP/HWPX → Markdown : " + total + " / "
                + effectiveRequested + " files → " + out.getAbsolutePath()
                + (r.fail > 0 ? "  (사전 검증 실패 " + r.fail + "건 제외)" : "")
                + (skipCounter[0] > 0 ? "  (SKIP " + skipCounter[0] + "건)" : ""));
        HwpMdConverter md = new HwpMdConverter();
        for (int i = 0; i < total; i++) {
            java.io.File f = valid.get(i);
            java.io.File o = outFileFor(f, out, ".md");
            System.out.println("[" + (i + 1) + "/" + total + "] "
                    + f.getAbsolutePath() + " → " + o.getName());
            try {
                if (f.getName().toLowerCase().endsWith(".hwpx")) {
                    md.convertHwpxToMarkdown(f.getAbsolutePath(), o.getAbsolutePath());
                } else {
                    md.convertHwpToMarkdown(f.getAbsolutePath(), o.getAbsolutePath());
                }
                r.ok++;
            } catch (Exception e) {
                r.fail++;
                r.failDetails.add(f.getName() + " : " + describeException(e));
                System.err.println("  [FAIL] " + describeException(e));
            }
        }
        printBatchSummary(r, effectiveRequested);
        return r;
    }

    /** 개별 파일 리스트 → --dist 배치 */
    public BatchResult batchDistFiles(java.util.List<java.io.File> inputFiles,
                                      String outputDir, String password,
                                      boolean noCopy, boolean noPrint,
                                      String forceOutExt) throws IOException {
        java.io.File out = new java.io.File(outputDir);
        ensureOutputDir(out);
        int given = inputFiles.size();
        BatchResult r = new BatchResult();
        int[] skipCounter = new int[]{0};
        // 입력 파일 사전 검증 (빈 문자열/디렉터리는 SKIP, 금칙 문자/미존재/잘못된 확장자는 FAIL)
        java.util.List<java.io.File> valid = new java.util.ArrayList<>();
        for (java.io.File raw : inputFiles) {
            java.io.File v = validateInputFileForBatch(raw.getPath(), r, skipCounter);
            if (v != null) valid.add(v);
        }
        int total = valid.size();
        int effectiveRequested = given - skipCounter[0];
        System.out.println("[Batch-Files] --dist : " + total + " / " + effectiveRequested + " files → "
                + out.getAbsolutePath()
                + "  (noCopy=" + noCopy + ", noPrint=" + noPrint
                + ", outExt=" + (forceOutExt == null ? "(입력 확장자 보존)" : forceOutExt) + ")"
                + (r.fail > 0 ? "  (사전 검증 실패 " + r.fail + "건 제외)" : "")
                + (skipCounter[0] > 0 ? "  (SKIP " + skipCounter[0] + "건 - 입력 자리의 디렉터리/빈 문자열)" : ""));
        for (int i = 0; i < total; i++) {
            java.io.File f = valid.get(i);
            String outExt = (forceOutExt != null) ? forceOutExt
                    : (f.getName().toLowerCase().endsWith(".hwpx") ? ".hwpx" : ".hwp");
            java.io.File o = outFileFor(f, out, outExt);
            System.out.println("[" + (i + 1) + "/" + total + "] " + f.getAbsolutePath() + " → " + o.getName());
            try {
                makeHwpDist(f.getAbsolutePath(), o.getAbsolutePath(), password, noCopy, noPrint);
                r.ok++;
            } catch (Exception e) {
                r.fail++;
                r.failDetails.add(f.getName() + " : " + describeException(e));
                System.err.println("  [FAIL] " + describeException(e));
            }
        }
        printBatchSummary(r, effectiveRequested);
        return r;
    }

    // ==========================================================
    //  v13.35: MD → HWP / HWPX 역변환 배치
    //  (.md 사이드카를 참조해 원본 HWP/HWPX 을 복원 또는 교차변환)
    // ==========================================================

    /**
     * 폴더 전체 MD → HWP 배치.
     * @param inputDir  .md 파일들이 있는 디렉터리 (사이드카 {md}.origin.hwp[x] 포함)
     * @param outputDir 출력 .hwp 저장 디렉터리
     */
    public BatchResult batchMdToHwp(String inputDir, String outputDir) throws IOException {
        return batchMdToFormat(inputDir, outputDir, ".hwp");
    }

    /**
     * 폴더 전체 MD → HWPX 배치.
     */
    public BatchResult batchMdToHwpx(String inputDir, String outputDir) throws IOException {
        return batchMdToFormat(inputDir, outputDir, ".hwpx");
    }

    private BatchResult batchMdToFormat(String inputDir, String outputDir, String toExt) throws IOException {
        java.io.File in = new java.io.File(inputDir);
        java.io.File out = new java.io.File(outputDir);
        ensureOutputDir(out);
        java.util.List<java.io.File> files = listByExt(in, ".md");
        System.out.println("[Batch] Markdown → " + toExt.substring(1).toUpperCase()
                + " : " + files.size() + " files in " + in.getAbsolutePath());
        BatchResult r = new BatchResult();
        HwpMdConverter md = new HwpMdConverter();
        boolean toHwpx = ".hwpx".equals(toExt);
        for (int i = 0; i < files.size(); i++) {
            java.io.File f = files.get(i);
            java.io.File o = outFileFor(f, out, toExt);
            System.out.println("[" + (i + 1) + "/" + files.size() + "] "
                    + f.getName() + " → " + o.getName());
            try {
                if (toHwpx) md.convertMarkdownToHwpx(f.getAbsolutePath(), o.getAbsolutePath());
                else        md.convertMarkdownToHwp(f.getAbsolutePath(), o.getAbsolutePath());
                r.ok++;
            } catch (Exception e) {
                r.fail++;
                r.failDetails.add(f.getName() + " : " + describeException(e));
                System.err.println("  [FAIL] " + describeException(e));
            }
        }
        printBatchSummary(r, files.size());
        return r;
    }

    /**
     * 예외 메시지를 사용자 친화적으로 변환.
     * "This is not paragraph." 등 hwp2hwpx 라이브러리의 저수준 오류는
     * 거의 항상 "입력 HWP가 배포용(AES-128 암호화) 문서인데 복호화 불가"
     * 또는 "이미 손상된 파일" 을 의미하므로 한국어 힌트를 덧붙인다.
     */
    private static String describeException(Exception e) {
        String msg = String.valueOf(e.getMessage());
        String base = e.getClass().getSimpleName() + " - " + msg;
        // IllegalStateException (convertHwpToHwpx 에서 dist 파일을 조기 차단한 경우)
        // 및 hwp2hwpx 의 "This is not paragraph" (dist BodyText 를 평문으로
        // 잘못 파싱한 경우) 모두 동일한 안내로 정규화.
        if (msg != null && (msg.contains("배포용")
                         || msg.contains("This is not paragraph")
                         || msg.contains("not paragraph"))) {
            return base + "  [hint] 배포용(dist) HWP 는 암호화되어 있어 HWPX 로 재변환할 수 없습니다. "
                    + "배포용 저장 전의 원본 HWP 를 사용하세요.";
        }
        return base;
    }

    private static void printBatchSummary(BatchResult r, int total) {
        System.out.println();
        System.out.println("[Batch] 완료 - 성공 " + r.ok + " / 실패 " + r.fail + " / 전체 " + total);
        if (r.fail > 0) {
            System.out.println("[Batch] 실패 목록:");
            for (String d : r.failDetails) System.out.println("  - " + d);
        }
    }

    /**
     * Auto-detect conversion direction based on file extensions and arguments.
     * 입력 경로가 디렉터리인 경우 자동으로 배치 모드로 동작한다.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            printUsage();
            return;
        }

        HwpConverter converter = new HwpConverter();

        // --dist 모드
        if ("--dist".equals(args[0])) {
            if (args.length < 4) {
                printUsage();
                return;
            }
            java.util.List<String> positional = new java.util.ArrayList<>();
            boolean noCopy = false, noPrint = false;
            String forceOutExt = null; // 배치 모드 전용: 출력 확장자 강제
            // --to-hwpx / --to-hwp 가 --dist 와 함께 지정된 경우:
            //   · 폴더 입력 → --dist 를 무시하고 일반 폴더 배치 변환으로 라우팅
            //   · 파일 입력 → --out-hwpx / --out-hwp 와 동일하게 취급 (forceOutExt 설정)
            String toModeFromDist = null; // "hwp2hwpx" or "hwpx2hwp" (null = not specified)
            for (int i = 1; i < args.length; i++) {
                // v13.33: positional 인자의 leading/trailing whitespace 자동 trim.
                //         사용자가 cmd 에서 " path" 처럼 따옴표와 경로 사이에 공백을
                //         넣은 경우 File(" C:\\...") 경로 오인으로 "디렉터리 생성 실패" 발생.
                String a = args[i];
                String trimmed = a.trim();
                if ("--no-copy".equals(trimmed))  { noCopy = true; continue; }
                if ("--no-print".equals(trimmed)) { noPrint = true; continue; }
                if ("--out-hwpx".equals(trimmed)) { forceOutExt = ".hwpx"; continue; }
                if ("--out-hwp".equals(trimmed))  { forceOutExt = ".hwp";  continue; }
                if ("--to-hwpx".equals(trimmed))  { toModeFromDist = "hwp2hwpx"; continue; }
                if ("--to-hwp".equals(trimmed))   { toModeFromDist = "hwpx2hwp"; continue; }
                if (!a.equals(trimmed)) {
                    System.err.println("[경고] 인자 앞/뒤 공백이 감지되어 자동 제거했습니다: \""
                            + a + "\" → \"" + trimmed + "\"");
                }
                positional.add(trimmed);
            }

            // --to-hwpx / --to-hwp + 폴더 입력 → 일반 폴더 배치 변환 (--dist 무시)
            // 이 경우 사용자가 --dist 를 실수로 앞에 붙인 것으로 간주한다.
            if (toModeFromDist != null && positional.size() >= 1) {
                java.io.File firstIn = new java.io.File(positional.get(0));
                if (firstIn.isDirectory()) {
                    if (positional.size() < 2) {
                        System.err.println("[오류] 출력 디렉터리를 지정해야 합니다.");
                        System.exit(2); return;
                    }
                    System.out.println("[HwpConverter] --dist 와 --to-" +
                            ("hwp2hwpx".equals(toModeFromDist) ? "hwpx" : "hwp") +
                            " 가 함께 지정되었습니다. 일반 폴더 배치 변환으로 처리합니다.");
                    BatchResult br;
                    if ("hwp2hwpx".equals(toModeFromDist))
                        br = converter.batchHwpToHwpx(positional.get(0), positional.get(1));
                    else
                        br = converter.batchHwpxToHwp(positional.get(0), positional.get(1));
                    if (br.fail > 0) System.exit(3);
                    return;
                }
                // 파일 입력이면 --to-hwpx/--to-hwp 를 --out-hwpx/--out-hwp 와 동일하게 취급
                if (forceOutExt == null) {
                    forceOutExt = "hwp2hwpx".equals(toModeFromDist) ? ".hwpx" : ".hwp";
                }
            }

            // 다건(폴더): 첫 번째 위치 인자가 디렉터리인 경우
            if (positional.size() >= 1) {
                java.io.File firstIn = new java.io.File(positional.get(0));
                if (firstIn.isDirectory()) {
                    if (positional.size() != 3) {
                        System.err.println("[오류] --dist 배치(폴더) 모드 인자 수가 맞지 않습니다.");
                        System.err.println("  형식: hwpConverter --dist <inputDir> <outputDir> <password> [옵션]");
                        System.exit(2);
                        return;
                    }
                    String pw = positional.get(2);
                    if (containsUnescapedShellMeta(pw)) {
                        System.out.println("[HwpConverter] 주의: 암호에 셸 특수문자 < > | & ^ 가 포함되어 있습니다.");
                    }
                    BatchResult br = converter.batchDist(
                            positional.get(0), positional.get(1), pw, noCopy, noPrint, forceOutExt);
                    if (br.fail > 0) System.exit(3);
                    return;
                }
            }

            // 다건(파일 지정): positional >= 4 → [파일...] [출력Dir] [암호]
            if (positional.size() >= 4) {
                String pw = positional.get(positional.size() - 1);
                String outDir = positional.get(positional.size() - 2);
                java.util.List<java.io.File> files = new java.util.ArrayList<>();
                for (int i = 0; i < positional.size() - 2; i++) {
                    files.add(new java.io.File(positional.get(i)));
                }
                if (containsUnescapedShellMeta(pw)) {
                    System.out.println("[HwpConverter] 주의: 암호에 셸 특수문자 < > | & ^ 가 포함되어 있습니다.");
                }
                BatchResult br = converter.batchDistFiles(files, outDir, pw, noCopy, noPrint, forceOutExt);
                if (br.fail > 0) System.exit(3);
                return;
            }

            // 단건 (기존 동작)
            if (positional.size() != 3
                    || !looksLikeHwpPath(positional.get(0))
                    || !looksLikeHwpPath(positional.get(1))) {
                printDistQuotingError(positional, noCopy, noPrint);
                System.exit(2);
                return;
            }
            String passwordForDist = positional.get(2);
            if (containsUnescapedShellMeta(passwordForDist)) {
                System.out.println("[HwpConverter] 주의: 암호에 셸 특수문자 < > | & ^ 가 포함되어 있습니다.");
            }

            String input = positional.get(0);
            String output = positional.get(1);
            try {
                converter.makeHwpDist(input, output, passwordForDist, noCopy, noPrint);
            } catch (IllegalStateException dex) {
                // [v16t64] 이미 배포용 입력 등 배포 거부 — 인자/입력 오류 관례(exit 2).
                System.err.println("[HwpConverter] 배포 거부: " + dex.getMessage());
                System.exit(2);
                return;
            }
            return;
        }

        // 옵션 플래그 파싱 (--to-hwpx / --to-hwp / --to-md)
        // v13.33: positional 인자의 leading/trailing whitespace 자동 trim
        // v14.0: --no-embed / --no-sidecar / --structure 는 폐기 (외부 MD 도 항상 구조 변환)
        java.util.List<String> positional = new java.util.ArrayList<>();
        String toMode = null;
        for (String a : args) {
            String trimmed = a.trim();
            if ("--to-hwpx".equals(trimmed))    { toMode = "hwp2hwpx"; continue; }
            if ("--to-hwp".equals(trimmed))     { toMode = "hwpx2hwp"; continue; }
            if ("--to-md".equals(trimmed))      { toMode = "any2md";   continue; }
            if ("--no-embed".equals(trimmed) || "--no-sidecar".equals(trimmed) || "--structure".equals(trimmed)) {
                System.err.println("[안내] " + trimmed + " 옵션은 v14.0부터 사용되지 않습니다 (외부 MD 변환이 항상 구조 변환됨). 무시합니다.");
                continue;
            }
            if (!a.equals(trimmed)) {
                System.err.println("[경고] 인자 앞/뒤 공백이 감지되어 자동 제거했습니다: \""
                        + a + "\" → \"" + trimmed + "\"");
            }
            positional.add(trimmed);
        }

        String input = positional.get(0);
        String output = positional.get(1);
        java.io.File inFile = new java.io.File(input);

        // 다건(폴더): 첫 번째 인자가 디렉터리인 경우
        if (inFile.isDirectory()) {
            int hwp = listByExt(inFile, ".hwp").size();
            int hwpx = listByExt(inFile, ".hwpx").size();
            int mdFiles = listByExt(inFile, ".md").size();

            String mode = toMode;
            if (mode == null) {
                // 자동 판별 (단일 포맷만 있는 경우)
                if (hwp > 0 && hwpx == 0 && mdFiles == 0) mode = "hwp2hwpx";
                else if (hwpx > 0 && hwp == 0 && mdFiles == 0) mode = "hwpx2hwp";
                else {
                    System.err.println("[오류] 입력 디렉터리의 포맷을 자동 판별할 수 없습니다.");
                    System.err.println("       --to-hwpx / --to-hwp / --to-md 옵션을 추가하세요.");
                    System.exit(2);
                    return;
                }
            }

            // v13.35: .md 파일이 주가 되는 경우 → MD 역변환 분기
            boolean mdDominant = mdFiles > 0 && hwp == 0 && hwpx == 0;
            BatchResult br;
            if ("any2md".equals(mode)) {
                br = converter.batchAnyToMd(input, output);
            } else if ("hwp2hwpx".equals(mode)) {
                if (mdDominant) br = converter.batchMdToHwpx(input, output);
                else            br = converter.batchHwpToHwpx(input, output);
            } else { // "hwpx2hwp"
                if (mdDominant) br = converter.batchMdToHwp(input, output);
                else            br = converter.batchHwpxToHwp(input, output);
            }
            if (br.fail > 0) System.exit(3);
            return;
        }

        // 다건(파일 지정): --to-hwpx/--to-hwp/--to-md 있고 positional >= 3
        // → 마지막 positional = 출력 디렉터리, 나머지 = 입력 파일
        // v13.35: positional == 2 이고 last positional 이 파일 확장자가 아니면(디렉터리 후보)
        //         입력 1개 + 출력 디렉터리 1개로 간주해 다건(파일 지정) 경로로 라우팅한다.
        boolean isMultiFileLayout = toMode != null && (
                positional.size() >= 3 ||
                (positional.size() == 2 && !looksLikeOutputFilePath(positional.get(1))));
        if (isMultiFileLayout) {
            String outDir = positional.get(positional.size() - 1);
            java.util.List<java.io.File> files = new java.util.ArrayList<>();
            for (int i = 0; i < positional.size() - 1; i++) {
                files.add(new java.io.File(positional.get(i)));
            }
            if ("any2md".equals(toMode)) {
                BatchResult br = converter.batchFilesToMd(files, outDir);
                if (br.fail > 0) System.exit(3);
                return;
            }
            String toExt = "hwp2hwpx".equals(toMode) ? ".hwpx" : ".hwp";
            BatchResult br = converter.batchFiles(files, outDir, toExt);
            if (br.fail > 0) System.exit(3);
            return;
        }

        // 단건 (기존 동작) + .md 입출력 라우팅 (v13.16 출력, v13.35 입력 추가)
        String inLower = input.toLowerCase();
        String outLower = output.toLowerCase();
        if (outLower.endsWith(".md")) {
            // HWP/HWPX → Markdown (HwpMdConverter 위임, 원본 사이드카 자동 저장)
            HwpMdConverter md = new HwpMdConverter();
            if (inLower.endsWith(".hwpx")) {
                md.convertHwpxToMarkdown(input, output);
            } else if (inLower.endsWith(".hwp")) {
                md.convertHwpToMarkdown(input, output);
            } else {
                System.out.println("Error: .md 출력은 .hwp 또는 .hwpx 입력에서만 지원됩니다.");
                System.exit(2);
            }
        } else if (inLower.endsWith(".md") && outLower.endsWith(".hwp")) {
            // v13.35: MD → HWP (사이드카 자동 탐지)
            new HwpMdConverter().convertMarkdownToHwp(input, output);
        } else if (inLower.endsWith(".md") && outLower.endsWith(".hwpx")) {
            // v13.35: MD → HWPX (사이드카 자동 탐지)
            new HwpMdConverter().convertMarkdownToHwpx(input, output);
        } else if (inLower.endsWith(".hwpx") && outLower.endsWith(".hwp")) {
            converter.convertHwpxToHwp(input, kr.n.nframe.newfeature.OutputNaming.unique(output));
        } else if (inLower.endsWith(".hwp") && outLower.endsWith(".hwpx")) {
            converter.convertHwpToHwpx(input, kr.n.nframe.newfeature.OutputNaming.unique(output));
        } else {
            System.out.println("Error: Cannot determine conversion direction.");
            System.out.println("  Supported: .hwpx -> .hwp  or  .hwp -> .hwpx  or  .hwp(x) -> .md  "
                    + "or  .md -> .hwp(x) (사이드카 필요)  or  --dist");
        }
    }

    private static void printUsage() {
        System.out.println("Usage (단건):");
        System.out.println("  HwpConverter <input.hwpx> <output.hwp>              (HWPX → HWP)");
        System.out.println("  HwpConverter <input.hwp>  <output.hwpx>             (HWP → HWPX)");
        System.out.println("  HwpConverter <input.hwp(x)> <output.md>             (HWP/HWPX → MD)");
        System.out.println("  HwpConverter <input.md> <output.hwp(x)>             (MD → HWP/HWPX, 외부 MD 도 자동 구조 변환)");
        System.out.println("  HwpConverter --dist <input> <output> <password> [--no-copy] [--no-print]");
        System.out.println();
        System.out.println("Usage (다건 - 폴더 전체):");
        System.out.println("  HwpConverter <inputDir> <outputDir> [--to-hwpx | --to-hwp | --to-md]");
        System.out.println("  HwpConverter --dist <inputDir> <outputDir> <password> [옵션] [--out-hwpx|--out-hwp]");
        System.out.println();
        System.out.println("Usage (다건 - 개별 파일 지정):");
        System.out.println("  HwpConverter <file1> <file2> ... <outputDir> --to-hwpx|--to-hwp|--to-md");
        System.out.println("  HwpConverter --dist <file1> <file2> ... <outputDir> <password> [옵션] [--out-hwpx|--out-hwp]");
        System.out.println();
        System.out.println("MD → HWP/HWPX 는 외부 MD 도 구조 변환으로 처리됩니다 (양식은 템플릿 기본값).");
        System.out.println();
        System.out.println("※ 주의: 경로·암호는 반드시 큰따옴표(\")로 감싸고, 여는/닫는 쌍을 맞춰야 합니다.");
        System.out.println("  암호에 < > | & ^ 가 있으면 큰따옴표로 감싸야 cmd 리다이렉션 오류를 피할 수 있습니다.");
    }
}
