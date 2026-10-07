package kr.n.nframe.test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * Peek at hp:tbl nesting in section0.xml — confirm whether a nested MD table
 * appears as a separate <hp:tbl> inside an outer <hp:tc>'s subList.
 */
public class NestedTblPeek {
    public static void main(String[] args) throws Exception {
        String hwpxPath = args[0];
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile z = new ZipFile(hwpxPath)) {
            Enumeration<? extends ZipEntry> es = z.entries();
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                if (e.isDirectory()) continue;
                try (InputStream is = z.getInputStream(e);
                     ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                    entries.put(e.getName(), bos.toByteArray());
                }
            }
        }
        String xml = new String(entries.get("Contents/section0.xml"), StandardCharsets.UTF_8);

        // Track nesting depth for hp:tbl
        Pattern openPat = Pattern.compile("<hp:tbl\\b([^>]*)>");
        Pattern closePat = Pattern.compile("</hp:tbl>");
        Pattern dimPat = Pattern.compile("rowCnt=\"(\\d+)\"[^>]*colCnt=\"(\\d+)\"");

        List<int[]> events = new ArrayList<>();
        Matcher om = openPat.matcher(xml);
        while (om.find()) events.add(new int[]{om.start(), 0, om.end()});
        Matcher cm = closePat.matcher(xml);
        while (cm.find()) events.add(new int[]{cm.start(), 1, cm.end()});
        events.sort(Comparator.comparingInt(a -> a[0]));

        Deque<Integer> stack = new ArrayDeque<>();
        int idx = 0;
        int maxDepth = 0;
        int nestedCount = 0;
        for (int[] ev : events) {
            if (ev[1] == 0) {
                stack.push(idx);
                if (stack.size() > 1) nestedCount++;
                if (stack.size() > maxDepth) maxDepth = stack.size();
                String tag = xml.substring(ev[0], ev[2]);
                Matcher dm = dimPat.matcher(tag);
                int rc = -1, cc = -1;
                if (dm.find()) { rc = Integer.parseInt(dm.group(1)); cc = Integer.parseInt(dm.group(2)); }
                System.out.printf("OPEN tbl#%d depth=%d pos=%d  rowCnt=%d colCnt=%d%n",
                        idx, stack.size(), ev[0], rc, cc);
                idx++;
            } else {
                if (!stack.isEmpty()) {
                    int o = stack.pop();
                    System.out.printf("CLOSE tbl#%d depth=%d  pos=%d%n",
                            o, stack.size() + 1, ev[0]);
                }
            }
        }
        System.out.println();
        System.out.println("Total hp:tbl: " + idx);
        System.out.println("Max depth: " + maxDepth);
        System.out.println("Nested (depth>1) opens: " + nestedCount);
    }
}
