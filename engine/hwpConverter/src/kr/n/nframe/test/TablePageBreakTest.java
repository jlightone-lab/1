package kr.n.nframe.test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.table.DivideAtPageBoundary;
import kr.dogfoot.hwplib.reader.HWPReader;

import kr.n.nframe.HwpMdConverter;

/**
 * v15.28 task3/task4 — 모든 표의 "여러 쪽 지원" 이 "쪽 경계에서 나눔" 으로 설정됨을 검증.
 *
 * <p>HWP binary : ControlTable.getTable().getProperty().getDivideAtPageBoundary() == Divide
 * <p>HWPX OWPML : &lt;hp:tbl pageBreak="NONE" ...&gt;
 *
 * <p>한/글 UI 매핑 :
 *  <ul>
 *    <li>"한 쪽으로"        ↔ NoDivide      ↔ pageBreak="TABLE"</li>
 *    <li>"셀 단위로 나눔"   ↔ DivideByCell  ↔ pageBreak="CELL"</li>
 *    <li>"쪽 경계에서 나눔" ↔ Divide        ↔ pageBreak="NONE" ← 본 테스트 목표</li>
 *  </ul>
 */
public class TablePageBreakTest {

    public static void main(String[] args) throws Exception {
        Path projRoot = Paths.get(System.getProperty("user.dir"));
        Path md = projRoot.resolve(
                "hwp/0515(추가개발건 예정)/dummy_file/11.hwp_md/case1.md");
        if (args.length >= 1) md = Paths.get(args[0]);
        if (!Files.exists(md)) {
            System.err.println("[TablePageBreakTest] MD 파일 없음: " + md);
            System.exit(2);
        }
        Path tmpDir = Files.createTempDirectory("table_page_break_test_");
        int passed = 0, failed = 0;
        try {
            Path outHwp  = tmpDir.resolve("case1.hwp");
            Path outHwpx = tmpDir.resolve("case1.hwpx");
            new HwpMdConverter().convertMarkdownToHwp(md.toString(), outHwp.toString());
            new HwpMdConverter().convertMarkdownToHwpx(md.toString(), outHwpx.toString());

            // ---- HWP : 모든 표의 DivideAtPageBoundary == Divide ----
            HWPFile hwp = HWPReader.fromFile(outHwp.toString());
            int[] hwpCounts = new int[3];  // [NoDivide, DivideByCell, Divide]
            int hwpTotal = 0;
            for (Section sec : hwp.getBodyText().getSectionList()) {
                for (Paragraph p : sec.getParagraphs()) {
                    if (p.getControlList() == null) continue;
                    for (Control c : p.getControlList()) {
                        hwpTotal = countTable(c, hwpCounts, hwpTotal);
                    }
                }
            }
            if (hwpTotal >= 5 && hwpCounts[2] == hwpTotal) {
                System.out.println("[HWP page break] PASS — Divide=" + hwpCounts[2]
                        + " (total=" + hwpTotal + ")");
                passed++;
            } else {
                System.out.println("[HWP page break] FAIL — NoDivide=" + hwpCounts[0]
                        + " DivideByCell=" + hwpCounts[1] + " Divide=" + hwpCounts[2]
                        + " (total=" + hwpTotal + ")");
                failed++;
            }

            // ---- HWPX : 모든 <hp:tbl ... pageBreak="NONE" ...> ----
            String sec = readSection0(outHwpx);
            int total = 0, noneCount = 0;
            Matcher m = Pattern.compile("<hp:tbl\\b[^>]*\\bpageBreak=\"([A-Z]+)\"").matcher(sec);
            int[] hwpxCounts = new int[3];
            while (m.find()) {
                String v = m.group(1);
                total++;
                switch (v) {
                    case "TABLE": hwpxCounts[0]++; break;
                    case "CELL":  hwpxCounts[1]++; break;
                    case "NONE":  hwpxCounts[2]++; noneCount++; break;
                }
            }
            if (total >= 5 && noneCount == total) {
                System.out.println("[HWPX page break] PASS — NONE=" + noneCount
                        + " (total=" + total + ")");
                passed++;
            } else {
                System.out.println("[HWPX page break] FAIL — TABLE=" + hwpxCounts[0]
                        + " CELL=" + hwpxCounts[1] + " NONE=" + hwpxCounts[2]
                        + " (total=" + total + ")");
                failed++;
            }
        } finally {
            try {
                Files.walk(tmpDir).sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            } catch (Exception ignored) {}
        }
        System.out.println();
        System.out.println("============================================================");
        System.out.println("[TablePageBreakTest] 통과=" + passed + " / 실패=" + failed);
        System.out.println("============================================================");
        if (failed > 0) System.exit(1);
    }

    private static int countTable(Control c, int[] counts, int total) {
        if (c instanceof ControlTable) {
            ControlTable t = (ControlTable) c;
            DivideAtPageBoundary d = t.getTable().getProperty().getDivideAtPageBoundary();
            if (d != null) {
                switch (d) {
                    case NoDivide:     counts[0]++; break;
                    case DivideByCell: counts[1]++; break;
                    case Divide:       counts[2]++; break;
                }
            }
            total++;
            for (kr.dogfoot.hwplib.object.bodytext.control.table.Row row : t.getRowList()) {
                for (kr.dogfoot.hwplib.object.bodytext.control.table.Cell cell : row.getCellList()) {
                    for (Paragraph p : cell.getParagraphList()) {
                        if (p.getControlList() == null) continue;
                        for (Control sub : p.getControlList()) {
                            total = countTable(sub, counts, total);
                        }
                    }
                }
            }
        }
        return total;
    }

    private static String readSection0(Path hwpx) throws Exception {
        try (ZipFile zf = new ZipFile(hwpx.toFile())) {
            ZipEntry e = zf.getEntry("Contents/section0.xml");
            if (e == null) throw new IllegalStateException("section0.xml 없음: " + hwpx);
            try (InputStream in = zf.getInputStream(e)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) baos.write(buf, 0, n);
                return new String(baos.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }
}
