package kr.n.nframe.test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Show all tables with rowCnt and a snippet of first cell text. */
public class TableInspect {
    public static void main(String[] args) throws Exception {
        Map<String, byte[]> entries = readZip(Paths.get(args[0]));
        String xml = new String(entries.get("Contents/section0.xml"), StandardCharsets.UTF_8);

        Pattern tblPat = Pattern.compile(
                "<hp:tbl\\b[^>]*rowCnt=\"(\\d+)\"[^>]*colCnt=\"(\\d+)\"[^>]*>");
        Matcher tm = tblPat.matcher(xml);
        int idx = 0;
        while (tm.find()) {
            int rc = Integer.parseInt(tm.group(1));
            int cc = Integer.parseInt(tm.group(2));
            // Find first <hp:t>...</hp:t> after this position within next 4000 chars
            int searchEnd = Math.min(xml.length(), tm.end() + 4000);
            String snippet = "";
            Matcher tt = Pattern.compile("<hp:t>([^<]*)</hp:t>").matcher(xml);
            tt.region(tm.end(), searchEnd);
            if (tt.find()) snippet = tt.group(1);
            if (snippet.length() > 40) snippet = snippet.substring(0, 40);
            System.out.printf("tbl[%d] rowCnt=%d colCnt=%d  pos=%d  text=\"%s\"%n",
                    idx, rc, cc, tm.start(), snippet);
            idx++;
        }
    }

    private static Map<String, byte[]> readZip(Path p) throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<>();
        try (ZipFile z = new ZipFile(p.toFile())) {
            Enumeration<? extends ZipEntry> es = z.entries();
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                if (e.isDirectory()) continue;
                try (InputStream is = z.getInputStream(e);
                     ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n; while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                    m.put(e.getName(), bos.toByteArray());
                }
            }
        }
        return m;
    }
}
