package kr.n.nframe.test;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.reader.HWPXReader;

public class HwpxLibValidate {
    public static void main(String[] args) throws Exception {
        for (String f : args) {
            try {
                HWPXFile file = HWPXReader.fromFilepath(f);
                System.out.println("[OK] " + f + " sections=" + file.sectionXMLFileList().count());
            } catch (Throwable t) {
                System.out.println("[FAIL] " + f + " : " + t.getClass().getSimpleName() + " - " + t.getMessage());
                t.printStackTrace();
            }
        }
    }
}
