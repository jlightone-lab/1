package kr.n.nframe.test;

import kr.dogfoot.hwpxlib.reader.HWPXReader;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import kr.dogfoot.hwpxlib.object.HWPXFile;

/** Test: read existing HWPX with hwpxlib and write back. Check if Hangul still opens. */
public class HwpxRoundTrip {
    public static void main(String[] a) throws Exception {
        if (a.length < 2) { System.err.println("Usage: HwpxRoundTrip <in.hwpx> <out.hwpx>"); return; }
        HWPXFile f = HWPXReader.fromFilepath(a[0]);
        System.out.println("Read OK: " + a[0]);
        HWPXWriter.toFilepath(f, a[1]);
        System.out.println("Wrote: " + a[1]);
    }
}
