package kr.n.nframe.test;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.docinfo.ParaShape;
import kr.dogfoot.hwplib.reader.HWPReader;

/** 입찰공고문 같은 hwp 의 paragraph 별 ParaShapeId 와 첫 50자 + ParaShape align 출력. */
public class InspectAlignment {
  public static void main(String[] a) throws Exception {
    if (a.length == 0) {
      System.err.println("Usage: InspectAlignment <hwp>");
      return;
    }
    HWPFile h = HWPReader.fromFile(a[0]);
    int psCount = h.getDocInfo().getParaShapeList().size();
    System.out.println("=== Top-level Paragraphs (Section 0) ===");
    Section sec = h.getBodyText().getSectionList().get(0);
    int n = sec.getParagraphCount();
    for (int i = 0; i < n; i++) {
      Paragraph p = sec.getParagraph(i);
      int psId = p.getHeader().getParaShapeId();
      String txt = "";
      if (p.getText() != null) {
        txt = p.getText().getCharList().toString();
        if (txt.length() > 0) {
          StringBuilder sb = new StringBuilder();
          for (int k = 0; k < p.getText().getCharList().size(); k++) {
            kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPChar c = p.getText().getCharList().get(k);
            if (c instanceof kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharNormal) {
              sb.appendCodePoint(((kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharNormal) c).getCode());
            }
          }
          txt = sb.toString();
        }
      }
      String align = "?";
      if (psId >= 0 && psId < psCount) {
        ParaShape ps = h.getDocInfo().getParaShapeList().get(psId);
        align = ps.getProperty1().getAlignment().toString();
      }
      String preview = txt.length() > 50 ? txt.substring(0, 50) + "..." : txt;
      preview = preview.replace("\n", "\\n").replace("\r", "");
      System.out.println(String.format("para[%3d] psId=%2d align=%-8s | %s", i, psId, align, preview));
    }
  }
}
