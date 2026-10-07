package kr.n.nframe.test;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.control.*;
import kr.dogfoot.hwplib.object.bodytext.control.table.*;
import kr.dogfoot.hwplib.reader.HWPReader;

/**
 * hwplib으로 reference 파일을 읽고, 이어서 우리 파일을 읽어본다.
 * 우리 파일에서 파싱이 실패하는 정확한 table/cell을 잡아낸다.
 */
public class HwpLibDeepDebug {
    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "output/test_hwpx2_conv.hwp";

        // 먼저 reference를 읽어서 hwplib이 정상 동작하는지 확인
        System.out.println("Reading reference...");
        HWPFile refFile = HWPReader.fromFile("hwp/test_hwpx2_conv.hwp");
        Section refSec = refFile.getBodyText().getSectionList().get(0);
        System.out.println("Reference: " + refSec.getParagraphCount() + " paragraphs");
        int refTables = 0;
        for (int i = 0; i < refSec.getParagraphCount(); i++) {
            Paragraph p = refSec.getParagraph(i);
            if (p.getControlList() != null) {
                for (Control c : p.getControlList()) {
                    if (c instanceof ControlTable) refTables++;
                }
            }
        }
        System.out.println("Reference tables: " + refTables);

        // 이진 탐색: 우리 Section0을 레코드 단위로 읽으면서 그룹을 reference로 대체하여
        // 어느 레코드 그룹이 실패를 일으키는지 찾는다
        System.out.println("\nBinary search for failing record...");

        org.apache.poi.poifs.filesystem.POIFSFileSystem refPoi =
            new org.apache.poi.poifs.filesystem.POIFSFileSystem(new java.io.FileInputStream("hwp/test_hwpx2_conv.hwp"));
        org.apache.poi.poifs.filesystem.POIFSFileSystem outPoi =
            new org.apache.poi.poifs.filesystem.POIFSFileSystem(new java.io.FileInputStream(path));

        byte[] refSec2 = FixedSectionTest.decompress(BinaryIsolator.readStream(refPoi, "BodyText", "Section0"));
        byte[] outSec2 = FixedSectionTest.decompress(BinaryIsolator.readStream(outPoi, "BodyText", "Section0"));
        byte[] refFH = BinaryIsolator.readStream(refPoi, "FileHeader");
        byte[] refDI = BinaryIsolator.readStream(refPoi, "DocInfo");

        java.util.List<byte[]> refRecs = RecordReplacer.parseRawRecords(refSec2);
        java.util.List<byte[]> outRecs = RecordReplacer.parseRawRecords(outSec2);

        // 이진 탐색: ref[0..lo] + out[lo..]는 성공, ref[0..hi] + out[hi..]는 실패
        // 경계를 찾는다
        int lo = refRecs.size(); // 전부 ref = 성공
        int hi = 0; // 전부 out = 실패

        // 중간 지점 테스트
        int[] testPoints = new int[20];
        for (int k = 0; k < 20; k++) testPoints[k] = (int)(refRecs.size() * (k + 1.0) / 21);

        for (int n : testPoints) {
            if (n >= refRecs.size() || n >= outRecs.size()) continue;
            // 구성: ref[0..n-1] + out[n..]
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < n; i++) bos.write(refRecs.get(i));
            for (int i = n; i < outRecs.size(); i++) bos.write(outRecs.get(i));
            byte[] combined = SectionBisect.compress(bos.toByteArray());

            // 임시 파일로 쓰기
            String tmpFile = "output/_bisect_test.hwp";
            BinaryIsolator.buildFile(tmpFile, refPoi, refFH, refDI, combined);

            // hwplib으로 테스트
            try {
                HWPReader.fromFile(tmpFile);
                System.out.println("  n=" + n + "/" + refRecs.size() + ": OK");
                lo = Math.min(lo, n);
            } catch (Exception e2) {
                System.out.println("  n=" + n + "/" + refRecs.size() + ": FAIL");
                hi = Math.max(hi, n);
            }
        }

        System.out.println("\nBoundary: somewhere between record " + lo + " and " + hi);
        System.out.println("(ref records before " + lo + " = OK, our records from " + hi + " = FAIL)");

        refPoi.close(); outPoi.close();
    }
}
