package kr.n.nframe.test;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.docinfo.IDMappings;
import kr.dogfoot.hwplib.reader.HWPReader;
public class InspectIDMappings {
    public static void main(String[] args) throws Exception {
        HWPFile h = HWPReader.fromFile(args[0]);
        IDMappings m = h.getDocInfo().getIDMappings();
        System.out.println("File: " + args[0]);
        System.out.println("  binDataCount       = " + m.getBinDataCount());
        System.out.println("  hangulFaceName     = " + m.getHangulFaceNameCount());
        System.out.println("  charShapeCount     = " + m.getCharShapeCount());
        System.out.println("  paraShapeCount     = " + m.getParaShapeCount());
        System.out.println("  Actual BinData list size = " + h.getDocInfo().getBinDataList().size());
        System.out.println("  Actual CharShape list size = " + h.getDocInfo().getCharShapeList().size());
    }
}
