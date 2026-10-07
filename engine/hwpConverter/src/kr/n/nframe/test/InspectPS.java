package kr.n.nframe.test;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.docinfo.ParaShape;
import kr.dogfoot.hwplib.reader.HWPReader;
public class InspectPS {
  public static void main(String[] a) throws Exception {
    String path = a.length > 0 ? a[0] : "src/kr/n/nframe/resources/hwp-streams/template.hwp";
    HWPFile h = HWPReader.fromFile(path);
    int[] focus = {0, 12, 28, 30, 31, 34, 36, 41};  // PS 0 + various Center
    for (int idx : focus) {
      if (idx >= h.getDocInfo().getParaShapeList().size()) continue;
      ParaShape ps = h.getDocInfo().getParaShapeList().get(idx);
      System.out.println("=== PS[" + idx + "] ===");
      System.out.println("  align       = " + ps.getProperty1().getAlignment());
      System.out.println("  prop1.value = 0x" + Long.toHexString(ps.getProperty1().getValue() & 0xFFFFFFFFL));
      System.out.println("  prop2       = " + ps.getProperty2().getValue());
      System.out.println("  prop3       = " + ps.getProperty3().getValue());
      System.out.println("  leftMargin  = " + ps.getLeftMargin());
      System.out.println("  rightMargin = " + ps.getRightMargin());
      System.out.println("  indent      = " + ps.getIndent());
      System.out.println("  paraShapeId/borderFill = " + ps.getBorderFillId());
      // Java reflection — list all getter methods to find letterSpace etc.
      java.lang.reflect.Method[] methods = ps.getClass().getMethods();
      for (java.lang.reflect.Method m : methods) {
        if (m.getName().startsWith("get") && m.getParameterCount() == 0
            && (m.getName().toLowerCase().contains("letter")
             || m.getName().toLowerCase().contains("space")
             || m.getName().toLowerCase().contains("kern"))) {
          try {
            Object v = m.invoke(ps);
            System.out.println("  " + m.getName() + "() = " + v);
          } catch (Exception ignore) {}
        }
      }
    }
  }
}
