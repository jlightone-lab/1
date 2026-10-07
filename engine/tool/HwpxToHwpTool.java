import java.io.File;
import java.io.PrintStream;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.tool.textextractor.TextExtractMethod;
import kr.dogfoot.hwplib.tool.textextractor.TextExtractor;
import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.reader.HWPXReader;
import kr.dogfoot.hwpxlib.tool.textextractor.TextMarks;
import kr.n.nframe.HwpConverter;

/**
 * HWPX → HWP 변환 + 결과 검증.
 *
 * 사용법: HwpxToHwpTool <input.hwpx> <output.hwp>
 *
 * 마지막 줄에 결과를 탭으로 구분해 출력한다.
 *   RESULT  OK    <원본 글자 수>  <결과 글자 수>
 *   RESULT  WARN  <사유>
 *   RESULT  FAIL  <사유>
 * 종료 코드: OK=0, WARN=1, FAIL=2
 */
public class HwpxToHwpTool {

    private static final byte[] OLE_SIGNATURE = {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
    };

    public static void main(String[] args) {
        PrintStream out;
        try {
            out = new PrintStream(System.out, true, "UTF-8");
        } catch (Exception e) {
            out = System.out;
        }

        if (args.length != 2) {
            out.println("RESULT\tFAIL\t사용법: HwpxToHwpTool <input.hwpx> <output.hwp>");
            System.exit(2);
            return;
        }
        String src = args[0];
        String dst = args[1];

        // 변환 엔진의 진행 메시지가 결과 줄과 섞이지 않도록 stderr로 보낸다
        PrintStream originalOut = System.out;
        System.setOut(System.err);
        try {
            new HwpConverter().convertHwpxToHwp(src, dst);
        } catch (Throwable t) {
            System.setOut(originalOut);
            out.println("RESULT\tFAIL\t변환 오류: " + oneLine(t));
            System.exit(2);
            return;
        }
        System.setOut(originalOut);

        File dstFile = new File(dst);
        if (!dstFile.isFile() || dstFile.length() == 0 || !hasOleSignature(dstFile)) {
            out.println("RESULT\tFAIL\t올바른 HWP 파일이 생성되지 않았습니다.");
            System.exit(2);
            return;
        }

        if (!hasBodySection(dstFile)) {
            out.println("RESULT\tFAIL\t결과 HWP에 본문(BodyText/Section0)이 없습니다. 원본 HWPX가 손상되었거나 비어 있을 수 있습니다.");
            System.exit(2);
            return;
        }

        String hwpText;
        try {
            HWPFile hwp = HWPReader.fromFile(dst);
            hwpText = TextExtractor.extract(hwp, TextExtractMethod.InsertControlTextBetweenParagraphText);
        } catch (Throwable t) {
            out.println("RESULT\tWARN\t결과 HWP를 다시 읽는 중 문제가 있습니다: " + oneLine(t));
            System.exit(1);
            return;
        }

        String hwpxText;
        try {
            HWPXFile hwpx = HWPXReader.fromFilepath(src);
            hwpxText = kr.dogfoot.hwpxlib.tool.textextractor.TextExtractor.extract(
                    hwpx,
                    kr.dogfoot.hwpxlib.tool.textextractor.TextExtractMethod.InsertControlTextBetweenParagraphText,
                    true,
                    new TextMarks());
        } catch (Throwable t) {
            out.println("RESULT\tWARN\t원본 HWPX 본문을 비교용으로 읽지 못했습니다: " + oneLine(t));
            System.exit(1);
            return;
        }

        String a = normalize(hwpxText);
        String b = normalize(hwpText);
        if (!a.equals(b)) {
            out.println("RESULT\tWARN\t본문 글자가 원본과 다릅니다 (원본 " + a.length() + "자 / 결과 " + b.length() + "자)");
            System.exit(1);
            return;
        }

        out.println("RESULT\tOK\t" + a.length() + "\t" + b.length());
        System.exit(0);
    }

    private static boolean hasBodySection(File f) {
        try (org.apache.poi.poifs.filesystem.POIFSFileSystem fs =
                     new org.apache.poi.poifs.filesystem.POIFSFileSystem(f, true)) {
            org.apache.poi.poifs.filesystem.Entry body = fs.getRoot().getEntry("BodyText");
            return body instanceof org.apache.poi.poifs.filesystem.DirectoryEntry
                    && ((org.apache.poi.poifs.filesystem.DirectoryEntry) body).hasEntry("Section0");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasOleSignature(File f) {
        byte[] head = new byte[OLE_SIGNATURE.length];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            if (in.read(head) != head.length) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        return java.util.Arrays.equals(head, OLE_SIGNATURE);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    private static String oneLine(Throwable t) {
        String msg = t.getMessage();
        String text = t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
        return text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }
}
