package kr.n.nframe.test;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.writer.HWPWriter;
public class RoundTripCheck {
    public static void main(String[] args) throws Exception {
        String src = args[0];
        String dst = args.length > 1 ? args[1] : src + ".roundtrip.hwp";
        System.out.println("=== Read: " + src + " ===");
        HWPFile h = HWPReader.fromFile(src);
        System.out.println("BinData record list size = " + h.getDocInfo().getBinDataList().size());
        System.out.println("EmbeddedBinaryData list size = " + h.getBinData().getEmbeddedBinaryDataList().size());
        System.out.println("=== Write to: " + dst + " ===");
        HWPWriter.toFile(h, dst);
        System.out.println("Done. Re-reading round-trip output...");
        HWPFile h2 = HWPReader.fromFile(dst);
        System.out.println("Round-trip: BinData record list size = " + h2.getDocInfo().getBinDataList().size());
        System.out.println("Round-trip: EmbeddedBinaryData list size = " + h2.getBinData().getEmbeddedBinaryDataList().size());
    }
}
