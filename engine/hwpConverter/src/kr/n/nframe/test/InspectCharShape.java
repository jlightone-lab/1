package kr.n.nframe.test;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.docinfo.CharShape;
import kr.dogfoot.hwplib.reader.HWPReader;

/**
 * Inspect CharShape entries (focus on letterSpace = 자간 / 글자너비 fields)
 * to find which CharShape ID has wider 자간 than the default 0 used for cells.
 *
 *   java kr.n.nframe.test.InspectCharShape [hwp-path]
 */
public class InspectCharShape {
  public static void main(String[] a) throws Exception {
    String path = a.length > 0 ? a[0] : "src/kr/n/nframe/resources/hwp-streams/template.hwp";
    HWPFile h = HWPReader.fromFile(path);
    int n = h.getDocInfo().getCharShapeList().size();
    System.out.println("CharShape count: " + n);
    // Inspect CS 62 (CharSpaces + Ratios + property bits)
    if (n > 62) {
      CharShape cs = h.getDocInfo().getCharShapeList().get(62);
      System.out.println("=== CS[62] ===");
      System.out.println("  baseSize = " + cs.getBaseSize());
      System.out.println("  property = 0x" + Long.toHexString(cs.getProperty().getValue()));
      Object cspaces = cs.getCharSpaces();
      Object ratios  = cs.getRatios();
      System.out.println("  CharSpaces: H=" + cspaces.getClass().getMethod("getHangul").invoke(cspaces)
        + " L=" + cspaces.getClass().getMethod("getLatin").invoke(cspaces)
        + " S=" + cspaces.getClass().getMethod("getSymbol").invoke(cspaces));
      System.out.println("  Ratios: H=" + ratios.getClass().getMethod("getHangul").invoke(ratios)
        + " L=" + ratios.getClass().getMethod("getLatin").invoke(ratios));
    }
    System.out.println("(scan done)");
  }

  static void dumpFields(String label, Object obj) {
    if (obj == null) return;
    System.out.println("  " + label + ":");
    java.lang.reflect.Method[] ms = obj.getClass().getMethods();
    for (java.lang.reflect.Method m : ms) {
      String name = m.getName();
      if (m.getParameterCount() == 0 && name.startsWith("get")
          && !name.equals("getClass")) {
        try {
          Object v = m.invoke(obj);
          if (v != null) System.out.println("    " + name + "() = " + v);
        } catch (Exception ignore) {}
      }
    }
  }
}
