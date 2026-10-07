package kr.n.nframe.test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Dump cell text in a specific table by index. */
public class TableContentDump {
    public static void main(String[] args) throws Exception {
        Map<String, byte[]> entries = readZip(Paths.get(args[0]));
        int targetIdx = Integer.parseInt(args[1]);
        String xml = new String(entries.get("Contents/section0.xml"), StandardCharsets.UTF_8);

        Pattern tblPat = Pattern.compile("<hp:tbl\\b[^>]*>");
        Matcher tm = tblPat.matcher(xml);
        int idx = 0;
        int tblStart = -1;
        while (tm.find()) {
            if (idx == targetIdx) { tblStart = tm.start(); break; }
            idx++;
        }
        if (tblStart < 0) { System.out.println("not found"); return; }

        // find balanced </hp:tbl>
        int depth = 1;
        int pos = tblStart + 1;
        Pattern openCl = Pattern.compile("<(/?)hp:tbl\\b");
        Matcher mm = openCl.matcher(xml);
        mm.region(pos, xml.length());
        int tblEnd = xml.length();
        while (mm.find()) {
            if (mm.group(1).isEmpty()) depth++;
            else { depth--; if (depth == 0) { tblEnd = mm.end(); break; } }
        }
        String tblXml = xml.substring(tblStart, tblEnd);

        // extract <hp:tc> blocks at top level only
        Pattern tcPat = Pattern.compile(
                "<hp:tc\\b[^>]*borderFillIDRef=\"(\\d+)\"[^>]*>");
        Matcher cm = tcPat.matcher(tblXml);
        Pattern addrPat = Pattern.compile(
                "<hp:cellAddr colAddr=\"(\\d+)\" rowAddr=\"(\\d+)\"");
        Pattern textPat = Pattern.compile("<hp:t>([^<]*)</hp:t>");
        while (cm.find()) {
            int p = cm.start();
            // find first cellAddr after p
            Matcher am = addrPat.matcher(tblXml);
            am.region(p, Math.min(tblXml.length(), p + 4000));
            int col = -1, row = -1;
            if (am.find()) { col = Integer.parseInt(am.group(1)); row = Integer.parseInt(am.group(2)); }
            // first hp:t in this cell
            Matcher tt = textPat.matcher(tblXml);
            tt.region(p, Math.min(tblXml.length(), p + 4000));
            String text = tt.find() ? tt.group(1) : "";
            if (text.length() > 30) text = text.substring(0, 30);
            System.out.printf("  bf=%s row=%d col=%d  text=\"%s\"%n",
                    cm.group(1), row, col, text);
        }
    }

    private static Map<String, byte[]> readZip(Path p) throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<>();
        try (ZipFile z = new ZipFile(p.toFile())) {
            Enumeration<? extends ZipEntry> es = z.entries();
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                if (e.isDirectory()) continue;
                try (InputStream is = z.getInputStream(e); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n; while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                    m.put(e.getName(), bos.toByteArray());
                }
            }
        }
        return m;
    }
}
