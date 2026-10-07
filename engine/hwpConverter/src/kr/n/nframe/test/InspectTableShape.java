package kr.n.nframe.test;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.reader.HWPReader;

/** Table structure dump: rows × cells (cellRow x cellCol → colSpan, rowSpan) */
public class InspectTableShape {
  public static void main(String[] a) throws Exception {
    if (a.length == 0) return;
    HWPFile h = HWPReader.fromFile(a[0]);
    int idxArg = 1;
    Integer onlyTable = (a.length > 1) ? Integer.parseInt(a[1]) : null;
    Section sec = h.getBodyText().getSectionList().get(0);
    int tableIndex = 0;
    for (int pi = 0; pi < sec.getParagraphCount(); pi++) {
      Paragraph p = sec.getParagraph(pi);
      if (p.getControlList() == null) continue;
      for (Control ctrl : p.getControlList()) {
        if (!(ctrl instanceof ControlTable)) continue;
        ControlTable t = (ControlTable) ctrl;
        if (onlyTable != null && tableIndex != onlyTable) { tableIndex++; continue; }
        System.out.println("=== Table[" + tableIndex + "] @ para#" + pi + " rows=" + t.getRowList().size() + " ===");
        System.out.println("  RowCount(rowList)=" + t.getRowList().size());
        int rIdx = 0;
        for (Row row : t.getRowList()) {
          int cIdx = 0;
          StringBuilder sb = new StringBuilder();
          for (Cell cell : row.getCellList()) {
            int cs = cell.getListHeader().getColSpan();
            int rs = cell.getListHeader().getRowSpan();
            int colNo = cell.getListHeader().getColIndex();
            int rowNo = cell.getListHeader().getRowIndex();
            long width = cell.getListHeader().getWidth();
            int paraCount = 0;
            Paragraph firstPara = null;
            for (Paragraph cp0 : cell.getParagraphList()) { if (firstPara == null) firstPara = cp0; paraCount++; }
            // first text only
            String txt = "";
            if (firstPara != null) {
              Paragraph cp = firstPara;
              if (cp.getText() != null) {
                StringBuilder tb = new StringBuilder();
                for (int k = 0; k < cp.getText().getCharList().size(); k++) {
                  kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPChar c = cp.getText().getCharList().get(k);
                  if (c instanceof kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharNormal) {
                    tb.appendCodePoint(((kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharNormal) c).getCode());
                  }
                }
                txt = tb.toString();
                if (txt.length() > 30) txt = txt.substring(0, 30) + "...";
              }
            }
            sb.append(String.format("[r%d,c%d cs=%d rs=%d w=%d paras=%d] ",
                    rowNo, colNo, cs, rs, width, paraCount));
            if (cIdx == 0) sb.append("|").append(txt).append("|");
            cIdx++;
          }
          System.out.println("  row " + rIdx + ": cells=" + row.getCellList().size() + " " + sb);
          rIdx++;
        }
        tableIndex++;
        if (onlyTable != null) return;
      }
    }
  }
}
