package kr.n.nframe.test;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.writer.HWPWriter;

/**
 * 한/글 작성 .hwp → hwplib read → hwplib write 라운드트립이
 * 구조를 보존하는지 검증.
 */
public class HwplibRoundtrip {
    public static void main(String[] args) throws Exception {
        String src = args[0];
        String dst = args.length > 1 ? args[1] : src + ".rt.hwp";
        long srcSize = new java.io.File(src).length();
        HWPFile hwp = HWPReader.fromFile(src);
        HWPWriter.toFile(hwp, dst);
        long dstSize = new java.io.File(dst).length();
        System.out.println("src: " + srcSize + " bytes -> dst: " + dstSize + " bytes  (delta: "
                + (dstSize - srcSize) + ")");
    }
}
