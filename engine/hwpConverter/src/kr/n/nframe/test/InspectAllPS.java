package kr.n.nframe.test;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.docinfo.ParaShape;
import kr.dogfoot.hwplib.reader.HWPReader;
public class InspectAllPS {
  public static void main(String[] a) throws Exception {
    String path = a.length > 0 ? a[0] : "src/kr/n/nframe/resources/hwp-streams/template.hwp";
    HWPFile h = HWPReader.fromFile(path);
    int n = h.getDocInfo().getParaShapeList().size();
    System.out.println("Total ParaShapes: " + n);
    for (int i = 0; i < n; i++) {
      ParaShape ps = h.getDocInfo().getParaShapeList().get(i);
      System.out.println("PS[" + i + "] align=" + ps.getProperty1().getAlignment()
          + " prop1=0x" + Long.toHexString(ps.getProperty1().getValue() & 0xFFFFFFFFL)
          + " borderFill=" + ps.getBorderFillId());
    }
  }
}
