package kr.n.nframe.test;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.docinfo.CharShape;
import kr.dogfoot.hwplib.object.docinfo.DocInfo;
import kr.dogfoot.hwplib.reader.HWPReader;
public class InspectCellCS {
    public static void main(String[] args) throws Exception {
        HWPFile hwp = HWPReader.fromFile(args[0]);
        DocInfo di = hwp.getDocInfo();
        int n = di.getCharShapeList().size();
        System.out.println("CharShape count: " + n);
        int[] ids = {0, 55, 62};
        for (int id : ids) {
            if (id >= n) continue;
            CharShape cs = di.getCharShapeList().get(id);
            System.out.println("CS[" + id + "] CharSpaces H=" + cs.getCharSpaces().getHangul()
                + " L=" + cs.getCharSpaces().getLatin()
                + " Hanja=" + cs.getCharSpaces().getHanja()
                + " S=" + cs.getCharSpaces().getSymbol()
                + " O=" + cs.getCharSpaces().getOther()
                + " | Ratios H=" + cs.getRatios().getHangul()
                + " L=" + cs.getRatios().getLatin());
        }
    }
}
