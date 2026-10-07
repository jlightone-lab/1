package kr.n.nframe.test;

import kr.dogfoot.hwpxlib.reader.HWPXReader;
import kr.dogfoot.hwpxlib.object.HWPXFile;

/** hwpxlib 으로 HWPX 파일 로드 시도 → Hangul 비호환 원인 단서 확보. */
public class HwpxValidate {
    public static void main(String[] a) throws Exception {
        if (a.length == 0) { System.err.println("Usage: HwpxValidate <hwpx>"); return; }
        try {
            HWPXFile f = HWPXReader.fromFilepath(a[0]);
            System.out.println("OK: hwpxlib loaded successfully");
            System.out.println("  sections: " + (f.sectionXMLFileList() == null ? -1 : f.sectionXMLFileList().count()));
            System.out.println("  paraShapes: " + (f.headerXMLFile() == null ? -1
                    : f.headerXMLFile().refList().paraProperties().count()));
            System.out.println("  charShapes: " + (f.headerXMLFile() == null ? -1
                    : f.headerXMLFile().refList().charProperties().count()));
            System.out.println("  borderFills: " + (f.headerXMLFile() == null ? -1
                    : f.headerXMLFile().refList().borderFills().count()));
        } catch (Throwable t) {
            System.err.println("FAIL: " + t.getClass().getSimpleName() + " - " + t.getMessage());
            t.printStackTrace();
        }
    }
}
