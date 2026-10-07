package kr.n.nframe.test;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.charshape.CharPositionShapeIdPair;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.object.docinfo.CharShape;
public class InspectCharShapes {
  public static void main(String[] a) throws Exception {
    HWPFile h = HWPReader.fromFile(a[0]);
    Section s = h.getBodyText().getLastSection();
    System.out.println("CharShape List:");
    for (int i = 0; i < h.getDocInfo().getCharShapeList().size(); i++) {
      CharShape cs = h.getDocInfo().getCharShapeList().get(i);
      System.out.println("  CS["+i+"] baseSize="+cs.getBaseSize()+" property=0x"+Long.toHexString(cs.getProperty().getValue()));
    }
    int from = a.length>1?Integer.parseInt(a[1]):0;
    int to = a.length>2?Integer.parseInt(a[2]):60;
    for (int i = from; i < Math.min(to, s.getParagraphCount()); i++) {
      Paragraph p = s.getParagraph(i);
      String t = p.getNormalString();
      StringBuilder sb = new StringBuilder();
      for (CharPositionShapeIdPair pair: p.getCharShape().getPositonShapeIdPairList()) {
        sb.append("[pos=").append(pair.getPosition()).append(",cs=").append(pair.getShapeId()).append("] ");
      }
      System.out.println("["+i+"] "+sb+" :: "+(t.length()>60?t.substring(0,60):t));
    }
  }
}
