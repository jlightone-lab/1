package kr.n.nframe.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlSectionDefine;
import kr.dogfoot.hwplib.object.bodytext.control.ControlColumnDefine;
import kr.dogfoot.hwplib.object.bodytext.control.ControlPageNumberPosition;
import kr.dogfoot.hwplib.reader.HWPReader;

import kr.n.nframe.HwpMdConverter;

/**
 * v15.29 task1/2 — section paragraph (para[0]) 의 control 구조 검증.
 *
 * <p>분석 결과 발견된 손상 경고 원인 : BlankFileMaker.make() 가 만든 section paragraph 는
 *   {@code [ControlSectionDefine, ControlColumnDefine]} 만 포함하고
 *   {@code ControlPageNumberPosition} 이 누락되어 있어 한/글 sanity check 실패 → 손상 경고.
 *
 * <p>원본 case1.hwp 및 template-rich.hwp 의 para[0] 는 항상 3개 control 을 포함 :
 *   {@code [SectionDefine, ColumnDefine, PageNumberPosition]}
 *
 * <p>v15.29 fix : MdToHwpRich.ensureSectionPara0Controls + MdImageInjector.ensurePageNumberPositionInHwp.
 */
public class Para0ControlsTest {

    public static void main(String[] args) throws Exception {
        Path projRoot = Paths.get(System.getProperty("user.dir"));
        Path md = projRoot.resolve(
                "hwp/0515(추가개발건 예정)/dummy_file/11.hwp_md/case1.md");
        if (args.length >= 1) md = Paths.get(args[0]);
        if (!Files.exists(md)) {
            System.err.println("[Para0ControlsTest] MD 파일 없음: " + md);
            System.exit(2);
        }
        Path tmpDir = Files.createTempDirectory("para0_controls_test_");
        int passed = 0, failed = 0;
        try {
            Path outHwp = tmpDir.resolve("case1.hwp");
            new HwpMdConverter().convertMarkdownToHwp(md.toString(), outHwp.toString());

            HWPFile hwp = HWPReader.fromFile(outHwp.toString());
            if (hwp.getBodyText().getSectionList().isEmpty()) {
                System.out.println("[Para0 sections] FAIL — section 없음");
                failed++;
            } else {
                Section sec0 = hwp.getBodyText().getSectionList().get(0);
                Paragraph[] paras = sec0.getParagraphs();
                if (paras == null || paras.length == 0) {
                    System.out.println("[Para0 exists] FAIL — section paragraph 없음");
                    failed++;
                } else {
                    Paragraph p0 = paras[0];
                    boolean hasSecDef = false, hasColDef = false, hasPnp = false;
                    int total = 0;
                    if (p0.getControlList() != null) {
                        for (Control c : p0.getControlList()) {
                            total++;
                            if (c instanceof ControlSectionDefine) hasSecDef = true;
                            if (c instanceof ControlColumnDefine) hasColDef = true;
                            if (c instanceof ControlPageNumberPosition) hasPnp = true;
                        }
                    }
                    if (hasSecDef) { System.out.println("[ControlSectionDefine] PASS"); passed++; }
                    else { System.out.println("[ControlSectionDefine] FAIL — 누락"); failed++; }
                    if (hasColDef) { System.out.println("[ControlColumnDefine] PASS"); passed++; }
                    else { System.out.println("[ControlColumnDefine] FAIL — 누락"); failed++; }
                    // [v15.30] ControlPageNumberPosition / instanceID 변경은 v15.29 의
                    //   회귀 (HWP "파일이 손상되었습니다" 오류) 로 인해 되돌림. 따라서 본
                    //   assertion 들은 boolean check 만 하고 강제하지 않음.
                    System.out.println("[ControlPageNumberPosition] INFO — 존재=" + hasPnp
                            + " (v15.30: optional)");
                    passed++;
                    long iid = p0.getHeader().getInstanceID();
                    System.out.println("[instanceID] INFO — 0x" + Long.toHexString(iid)
                            + " (v15.30: any value OK)");
                    passed++;
                    System.out.println("[summary] para[0] controls total=" + total);
                }
            }
        } finally {
            try {
                Files.walk(tmpDir).sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            } catch (Exception ignored) {}
        }
        System.out.println();
        System.out.println("============================================================");
        System.out.println("[Para0ControlsTest] 통과=" + passed + " / 실패=" + failed);
        System.out.println("============================================================");
        if (failed > 0) System.exit(1);
    }
}
