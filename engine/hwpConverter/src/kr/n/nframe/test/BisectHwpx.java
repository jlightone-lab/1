package kr.n.nframe.test;

import kr.n.nframe.mdlib.MdToHwpxNative;
import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.reader.HWPXReader;

public class BisectHwpx {
    public static void main(String[] args) throws Exception {
        String md = "hwp/0427/dummy_file/05.단건_hwp_md/case1.md";
        String[] tests = {
                "build/v1413/poc/test_no_table_no_pic.hwpx",
                "build/v1413/poc/test_table_only.hwpx",
                "build/v1413/poc/test_pic_only.hwpx",
                "build/v1413/poc/test_full.hwpx",
                "build/v1413/poc/test_no_heading.hwpx"
        };

        java.nio.file.Files.createDirectories(java.nio.file.Paths.get("build/v1413/poc"));

        for (int i = 0; i < tests.length; i++) {
            String out = tests[i];
            MdToHwpxNative c = new MdToHwpxNative();
            switch (i) {
                case 0: c.enableTables=false; c.enableImages=false; break;
                case 1: c.enableTables=true; c.enableImages=false; break;
                case 2: c.enableTables=false; c.enableImages=true; break;
                case 3: c.enableTables=true; c.enableImages=true; break;
                case 4: c.enableHeadingShapes=false; break;
            }
            try {
                c.convert(md, out);
            } catch (Exception e) {
                System.out.println("[FAIL convert] " + out + ": " + e.getMessage());
                continue;
            }
            try {
                HWPXFile f = HWPXReader.fromFilepath(out);
                System.out.println("[OK     ] " + out + "  size=" + new java.io.File(out).length()
                        + " sections=" + f.sectionXMLFileList().count());
            } catch (Throwable t) {
                System.out.println("[FAIL parse] " + out + ": " + t.getClass().getSimpleName() + " - " + t.getMessage());
                if (i == 2) t.printStackTrace(); // pic_only 의 trace 출력
            }
        }
    }
}
