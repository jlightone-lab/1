package kr.n.nframe.test;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.lineseg.LineSegItem;
import kr.dogfoot.hwplib.reader.HWPReader;

/** Dump outer-cell paragraphs (text + LineSeg) so we can see textBefore/textAfter and nested. */
public class InspectNestedDeep {
  public static void main(String[] a) throws Exception {
    HWPFile h = HWPReader.fromFile(a[0]);
    Section s = h.getBodyText().getLastSection();
    int wantedTable = a.length > 1 ? Integer.parseInt(a[1]) : -1;
    int tIdx = 0;
    for (int i = 0; i < s.getParagraphCount(); i++) {
      Paragraph p = s.getParagraph(i);
      if (p.getControlList() == null) continue;
      for (Control ctrl : p.getControlList()) {
        if (!(ctrl instanceof ControlTable)) continue;
        if (wantedTable >= 0 && tIdx != wantedTable) { tIdx++; continue; }
        ControlTable t = (ControlTable) ctrl;
        System.out.println("=== Outer T" + tIdx + " @ para#" + i + " ===");
        Row row = t.getRowList().get(0);
        Cell cell = row.getCellList().get(0);
        int pi = 0;
        for (Paragraph cp : cell.getParagraphList()) {
          int paraId = cp.getHeader().getParaShapeId();
          long lineH = 0;
          int lineCount = 0;
          long textPartH = 0, distBase = 0, lineSpace = 0, segW = 0;
          if (cp.getLineSeg() != null) {
            for (LineSegItem ls : cp.getLineSeg().getLineSegItemList()) {
              lineCount++; lineH += ls.getLineHeight();
              textPartH = ls.getTextPartHeight();
              distBase = ls.getDistanceBaseLineToLineVerticalPosition();
              lineSpace = ls.getLineSpace();
              segW = ls.getSegmentWidth();
            }
          }
          long chCount = cp.getHeader().getCharacterCount();
          // detect nested controls
          int nestedCount = 0;
          if (cp.getControlList() != null) {
            for (Control c2 : cp.getControlList()) {
              if (c2 instanceof ControlTable) nestedCount++;
            }
          }
          // text preview
          StringBuilder sb = new StringBuilder();
          if (cp.getText() != null) {
            for (int k = 0; k < cp.getText().getCharList().size(); k++) {
              kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPChar c = cp.getText().getCharList().get(k);
              if (c instanceof kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharNormal) {
                sb.appendCodePoint(((kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharNormal) c).getCode());
              } else {
                sb.append("[CTRL:").append(c.getClass().getSimpleName()).append("]");
              }
            }
          }
          String txt = sb.toString();
          if (txt.length() > 50) txt = txt.substring(0, 50) + "...";
          System.out.println(String.format("  cellPara[%d] psId=%d chars=%d lineCount=%d lineH=%d textPartH=%d distBase=%d lineSpace=%d segW=%d nested=%d | %s",
                  pi, paraId, chCount, lineCount, lineH, textPartH, distBase, lineSpace, segW, nestedCount, txt));
          pi++;
        }
        tIdx++;
      }
    }
  }
}
