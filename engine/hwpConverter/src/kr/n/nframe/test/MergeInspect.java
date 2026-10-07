package kr.n.nframe.test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Find <hp:tc> with colSpan>1 or rowSpan>1 + their cell text. */
public class MergeInspect {
    public static void main(String[] args) throws Exception {
        Map<String, byte[]> ent = readZip(Paths.get(args[0]));
        String xml = new String(ent.get("Contents/section0.xml"), StandardCharsets.UTF_8);
        Pattern p = Pattern.compile(
                "<hp:tc\\b[^>]*>.*?<hp:cellAddr colAddr=\"(\\d+)\" rowAddr=\"(\\d+)\"/><hp:cellSpan colSpan=\"(\\d+)\" rowSpan=\"(\\d+)\"",
                Pattern.DOTALL);
        Matcher m = p.matcher(xml);
        Pattern tt = Pattern.compile("<hp:t>([^<]{0,40})");
        while (m.find()) {
            int cs = Integer.parseInt(m.group(3));
            int rs = Integer.parseInt(m.group(4));
            if (cs <= 1 && rs <= 1) continue;
            int pos = m.end();
            int end = Math.min(xml.length(), pos + 5000);
            Matcher tm = tt.matcher(xml).region(pos, end);
            String text = tm.find() ? tm.group(1) : "";
            System.out.printf("col=%s row=%s cs=%d rs=%d text=\"%s\"%n",
                    m.group(1), m.group(2), cs, rs, text);
        }
    }
    private static Map<String, byte[]> readZip(Path p) throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<>();
        try (ZipFile z = new ZipFile(p.toFile())) {
            Enumeration<? extends ZipEntry> es = z.entries();
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                if (e.isDirectory()) continue;
                try (InputStream is = z.getInputStream(e); ByteArrayOutputStream b = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n; while ((n = is.read(buf)) > 0) b.write(buf, 0, n);
                    m.put(e.getName(), b.toByteArray());
                }
            }
        }
        return m;
    }
}
