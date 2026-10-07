package kr.n.nframe.test;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.docinfo.ParaShape;
import kr.dogfoot.hwplib.reader.HWPReader;

/** Tables 의 셀 내부 paragraph 정렬 출력 */
public class InspectCellAlignment {
  public static void main(String[] a) throws Exception {
    if (a.length == 0) return;
    HWPFile h = HWPReader.fromFile(a[0]);
    int psCount = h.getDocInfo().getParaShapeList().size();
    Section sec = h.getBodyText().getSectionList().get(0);
    int tableIndex = 0;
    for (int pi = 0; pi < sec.getParagraphCount(); pi++) {
      Paragraph p = sec.getParagraph(pi);
      if (p.getControlList() == null) continue;
      for (Control ctrl : p.getControlList()) {
        if (!(ctrl instanceof ControlTable)) continue;
        ControlTable t = (ControlTable) ctrl;
        System.out.println("=== Table[" + tableIndex + "] @ para#" + pi + " rows=" + t.getRowList().size() + " ===");
        int rIdx = 0;
        for (Row row : t.getRowList()) {
          int cIdx = 0;
          for (Cell cell : row.getCellList()) {
            int paraIdx = 0;
            for (Paragraph cp : cell.getParagraphList()) {
              int psId = cp.getHeader().getParaShapeId();
              String align = "?";
              if (psId >= 0 && psId < psCount) {
                ParaShape ps = h.getDocInfo().getParaShapeList().get(psId);
                align = ps.getProperty1().getAlignment().toString();
              }
              StringBuilder sb = new StringBuilder();
              if (cp.getText() != null) {
                for (int k = 0; k < cp.getText().getCharList().size(); k++) {
                  kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPChar c = cp.getText().getCharList().get(k);
                  if (c instanceof kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharNormal) {
                    sb.appendCodePoint(((kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharNormal) c).getCode());
                  }
                }
              }
              String txt = sb.toString();
              String preview = txt.length() > 50 ? txt.substring(0, 50) + "..." : txt;
              preview = preview.replace("\n", "\\n").replace("\r", "");
              System.out.println(String.format("  [%d,%d] cellPara[%d] psId=%2d align=%-8s | %s",
                      rIdx, cIdx, paraIdx, psId, align, preview));
              paraIdx++;
            }
            cIdx++;
          }
          rIdx++;
        }
        tableIndex++;
      }
    }
  }
}
