package kr.n.nframe.test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

public class BgCellInspect {
    public static void main(String[] args) throws Exception {
        String hwpxPath = args[0];
        Map<String, byte[]> entries = readZip(Paths.get(hwpxPath));
        String xml = new String(entries.get("Contents/section0.xml"), StandardCharsets.UTF_8);

        // 모든 <hp:tbl ... rowCnt="N" colCnt="M"> 의 시작 위치 + 차원
        List<int[]> tbls = new ArrayList<>(); // [startPos, rowCnt, colCnt, idx]
        Pattern tblPat = Pattern.compile("<hp:tbl\\b[^>]*rowCnt=\"(\\d+)\"[^>]*colCnt=\"(\\d+)\"");
        Matcher tm = tblPat.matcher(xml);
        int tblIdx = 0;
        while (tm.find()) {
            tbls.add(new int[]{tm.start(), Integer.parseInt(tm.group(1)),
                    Integer.parseInt(tm.group(2)), tblIdx});
            tblIdx++;
        }
        System.out.println("총 <hp:tbl> 개수: " + tbls.size());

        // 셀별 매칭
        Pattern cellPat = Pattern.compile(
                "<hp:tc\\b[^>]*borderFillIDRef=\"(\\d+)\"[^>]*>");
        Pattern addrPat = Pattern.compile(
                "<hp:cellAddr colAddr=\"(\\d+)\" rowAddr=\"(\\d+)\"");
        Set<String> imageRefs;
        if (args.length > 1) {
            imageRefs = new HashSet<>(Arrays.asList(args[1].split(",")));
        } else {
            imageRefs = new HashSet<>(Arrays.asList("233", "234", "235", "236", "237", "239"));
        }
        Matcher cm = cellPat.matcher(xml);
        while (cm.find()) {
            String bf = cm.group(1);
            if (!imageRefs.contains(bf)) continue;
            int pos = cm.start();
            // cellAddr 검색 (이 hp:tc 시작 이후 가장 가까운 cellAddr)
            Matcher am = addrPat.matcher(xml);
            am.region(pos, Math.min(xml.length(), pos + 4000));
            int col = -1, row = -1;
            if (am.find()) {
                col = Integer.parseInt(am.group(1));
                row = Integer.parseInt(am.group(2));
            }
            // 가장 가까운 (앞에 있는) hp:tbl 찾기
            int[] tbl = null;
            for (int[] t : tbls) {
                if (t[0] < pos) tbl = t;
                else break;
            }
            String tblInfo = tbl == null ? "(no tbl)"
                    : "tblIdx=" + tbl[3] + " rowCnt=" + tbl[1] + " colCnt=" + tbl[2];
            System.out.printf("  bf=%s row=%d col=%d  %s%n", bf, row, col, tblInfo);
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
                    int n;
                    while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                    m.put(e.getName(), bos.toByteArray());
                }
            }
        }
        return m;
    }
}
