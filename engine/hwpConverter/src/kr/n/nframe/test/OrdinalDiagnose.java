package kr.n.nframe.test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * Ordinal mismatch 진단 도구.
 *
 * Usage:
 *   java ... OrdinalDiagnose <mdPath> <hwpxPath>
 *
 *   - mdPath: MdImageInjector 가 입력으로 받는 MD 파일
 *   - hwpxPath: case1.hwp 를 HwpConverter.convertHwpToHwpx 로 변환한 결과 (MdImageInjector 가
 *     중간에 만드는 step1.hwpx 와 동일해야 함). 또는 final case1.hwp 를 변환한 hwpx.
 *
 * 결과:
 *   1) MD 의 모든 td/th 를 sequential ordinal 로 나열, 어느 표(table) 의 어느 row 에 속하는지.
 *   2) MD 의 background-image 가진 td/th 의 ordinal.
 *   3) HWPX section0.xml 의 모든 hp:tc 를 sequential ordinal 로 나열, 어느 hp:tbl 에 속하는지.
 *   4) 두 ordinal 이 어디서 어긋나는지.
 */
public class OrdinalDiagnose {

    static class MdCell {
        int ordinal;
        int tableIdx;
        int rowIdx;
        int colInRow;
        boolean hasBg;
        int line;
        boolean isHeader;
        String previewText;
    }

    static class MdTable {
        int idx;
        int startLine;
        int endLine;
        int rowCount;
        int cellCount;
        boolean nested; // outer table 안에 nested 가 있는지
    }

    static class HwpxCell {
        int ordinal;
        int tableIdx;
        int colAddr;
        int rowAddr;
        int borderFillIDRef;
        int xmlPos;
    }

    static class HwpxTable {
        int idx;
        int xmlPos;
        int rowCnt;
        int colCnt;
        int cellCount;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: OrdinalDiagnose <mdPath> <hwpxPath>");
            System.exit(2);
        }
        String mdPath = args[0];
        String hwpxPath = args[1];

        System.out.println("=== MD 분석: " + mdPath + " ===");
        List<MdCell> mdCells = new ArrayList<>();
        List<MdTable> mdTables = new ArrayList<>();
        analyzeMd(mdPath, mdCells, mdTables);
        System.out.println("MD: " + mdTables.size() + "개 표, " + mdCells.size() + "개 td/th");
        for (MdTable t : mdTables) {
            System.out.printf("  MD Table[%d] line %d~%d  rows=%d cells=%d%s%n",
                    t.idx, t.startLine, t.endLine, t.rowCount, t.cellCount,
                    t.nested ? " [NESTED]" : "");
        }

        System.out.println();
        System.out.println("=== MD 의 background-image td/th ordinal ===");
        for (MdCell c : mdCells) {
            if (c.hasBg) {
                System.out.printf("  MD ord=%d  Table[%d] row=%d col=%d line=%d%n",
                        c.ordinal, c.tableIdx, c.rowIdx, c.colInRow, c.line);
            }
        }

        System.out.println();
        System.out.println("=== HWPX 분석: " + hwpxPath + " ===");
        Map<String, byte[]> entries = readZip(Paths.get(hwpxPath));
        byte[] sec0 = entries.get("Contents/section0.xml");
        if (sec0 == null) {
            System.err.println("section0.xml 못 찾음. zip 엔트리: " + entries.keySet());
            return;
        }
        String xml = new String(sec0, StandardCharsets.UTF_8);
        List<HwpxCell> hwpxCells = new ArrayList<>();
        List<HwpxTable> hwpxTables = new ArrayList<>();
        analyzeHwpx(xml, hwpxCells, hwpxTables);
        System.out.println("HWPX: " + hwpxTables.size() + "개 hp:tbl, " + hwpxCells.size() + "개 hp:tc");
        for (HwpxTable t : hwpxTables) {
            System.out.printf("  HWPX Tbl[%d] rowCnt=%d colCnt=%d cellCount=%d%n",
                    t.idx, t.rowCnt, t.colCnt, t.cellCount);
        }

        System.out.println();
        System.out.println("=== 누적 cell 개수 비교 (table 단위) ===");
        int mdCum = 0, hwpxCum = 0;
        int max = Math.max(mdTables.size(), hwpxTables.size());
        System.out.printf("%-10s %-30s %-30s %-15s%n", "TableIdx", "MD(rows×cols=cells, cum)", "HWPX(rowCnt×colCnt=cells, cum)", "Δ누적");
        for (int i = 0; i < max; i++) {
            String mdInfo = "-", hwpxInfo = "-";
            int mdCells_i = 0, hwpxCells_i = 0;
            if (i < mdTables.size()) {
                MdTable t = mdTables.get(i);
                mdCells_i = t.cellCount;
                mdCum += mdCells_i;
                mdInfo = String.format("%d×%d=%d (cum=%d)", t.rowCount, t.cellCount/Math.max(1,t.rowCount),
                        mdCells_i, mdCum);
            }
            if (i < hwpxTables.size()) {
                HwpxTable t = hwpxTables.get(i);
                hwpxCells_i = t.cellCount;
                hwpxCum += hwpxCells_i;
                hwpxInfo = String.format("%d×%d=%d (cum=%d)", t.rowCnt, t.colCnt, hwpxCells_i, hwpxCum);
            }
            System.out.printf("%-10d %-30s %-30s %-15d%n", i, mdInfo, hwpxInfo, hwpxCum - mdCum);
        }

        System.out.println();
        System.out.println("=== 첫 어긋남 ordinal ===");
        // MD td 와 HWPX hp:tc 를 ordinal 로 매핑.
        int n = Math.min(mdCells.size(), hwpxCells.size());
        for (int i = 0; i < n; i++) {
            MdCell mc = mdCells.get(i);
            HwpxCell hc = hwpxCells.get(i);
            if (mc.tableIdx != hc.tableIdx) {
                System.out.printf("  ord=%d  MD Tbl[%d]row%d col%d line=%d  vs  HWPX Tbl[%d] row%d col%d%n",
                        i, mc.tableIdx, mc.rowIdx, mc.colInRow, mc.line,
                        hc.tableIdx, hc.rowAddr, hc.colAddr);
                if (i > 5) {
                    System.out.println("  (생략)");
                    break;
                }
            }
        }

        System.out.println();
        System.out.println("=== 주변 표지판 표 (background-image 가진 표) cell 매핑 ===");
        List<Integer> bgTableIdxs = new ArrayList<>();
        for (MdCell c : mdCells) {
            if (c.hasBg && !bgTableIdxs.contains(c.tableIdx)) bgTableIdxs.add(c.tableIdx);
        }
        for (int tIdx : bgTableIdxs) {
            System.out.println();
            System.out.println("MD Table[" + tIdx + "] cells:");
            int firstOrd = -1, lastOrd = -1;
            for (MdCell c : mdCells) {
                if (c.tableIdx == tIdx) {
                    if (firstOrd < 0) firstOrd = c.ordinal;
                    lastOrd = c.ordinal;
                    System.out.printf("  ord=%d row=%d col=%d %s line=%d %s%n",
                            c.ordinal, c.rowIdx, c.colInRow,
                            c.hasBg ? "[BG]" : "    ", c.line,
                            c.previewText == null ? "" : c.previewText);
                }
            }
            System.out.println("  → MD ordinal 범위: " + firstOrd + "~" + lastOrd);
            // HWPX 같은 ordinal 범위 cell
            System.out.println("HWPX ordinal " + firstOrd + "~" + lastOrd + " 에 해당하는 hp:tc:");
            for (HwpxCell hc : hwpxCells) {
                if (hc.ordinal >= firstOrd && hc.ordinal <= lastOrd) {
                    System.out.printf("  ord=%d HwpxTbl[%d] row=%d col=%d bf=%d%n",
                            hc.ordinal, hc.tableIdx, hc.rowAddr, hc.colAddr, hc.borderFillIDRef);
                }
            }
        }
    }

    private static void analyzeMd(String mdPath, List<MdCell> cells, List<MdTable> tables) throws IOException {
        String md = new String(Files.readAllBytes(Paths.get(mdPath)), StandardCharsets.UTF_8);
        // line offset map
        int[] lineOffsets = computeLineOffsets(md);

        // table boundaries
        Pattern openTbl = Pattern.compile("<table\\b[^>]*>", Pattern.CASE_INSENSITIVE);
        Pattern closeTbl = Pattern.compile("</table>", Pattern.CASE_INSENSITIVE);
        // collect table open/close events
        Matcher openM = openTbl.matcher(md);
        Matcher closeM = closeTbl.matcher(md);
        List<int[]> events = new ArrayList<>(); // [pos, type 0=open 1=close]
        while (openM.find()) events.add(new int[]{openM.start(), 0, openM.end()});
        while (closeM.find()) events.add(new int[]{closeM.start(), 1, closeM.end()});
        events.sort(Comparator.comparingInt(a -> a[0]));

        // Track depth, top-level table starts get an idx.
        // Use stack of table records.
        Deque<MdTable> stack = new ArrayDeque<>();
        int topLevelIdx = 0;
        // also map cell-position-range to enclosing top-level idx
        List<int[]> topLevelRanges = new ArrayList<>(); // [start,end,idx]
        for (int[] ev : events) {
            int pos = ev[0];
            int type = ev[1];
            if (type == 0) {
                MdTable t = new MdTable();
                t.startLine = posToLine(lineOffsets, pos);
                if (stack.isEmpty()) {
                    t.idx = topLevelIdx++;
                    tables.add(t);
                    topLevelRanges.add(new int[]{pos, -1, t.idx});
                } else {
                    t.idx = -1; // nested
                    stack.peek().nested = true;
                }
                stack.push(t);
            } else {
                if (!stack.isEmpty()) {
                    MdTable t = stack.pop();
                    t.endLine = posToLine(lineOffsets, pos);
                    if (t.idx >= 0) {
                        // close range
                        for (int[] r : topLevelRanges) {
                            if (r[2] == t.idx) { r[1] = ev[2]; break; }
                        }
                    }
                }
            }
        }

        // walk all <td|th>
        Pattern cellOpen = Pattern.compile("<(td|th)\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
        Pattern bgPat = Pattern.compile(
                "background-image\\s*:\\s*url\\(\\s*data:image",
                Pattern.CASE_INSENSITIVE);
        Pattern trOpen = Pattern.compile("<tr\\b[^>]*>", Pattern.CASE_INSENSITIVE);
        Pattern trClose = Pattern.compile("</tr>", Pattern.CASE_INSENSITIVE);

        // Build structure: per top-level table, walk cells linearly using row counter and col-in-row counter,
        // counting only within enclosing top-level (nested cells are still added with same tableIdx
        // because MdImageInjector regex counts all <td|th> regardless of nesting).
        Matcher m = cellOpen.matcher(md);
        int ord = 0;
        while (m.find()) {
            MdCell c = new MdCell();
            c.ordinal = ord;
            c.line = posToLine(lineOffsets, m.start());
            c.isHeader = m.group(1).equalsIgnoreCase("th");
            String attrs = m.group(2);
            c.hasBg = bgPat.matcher(attrs).find();
            // determine enclosing top-level table
            c.tableIdx = -1;
            for (int[] r : topLevelRanges) {
                if (r[0] <= m.start() && (r[1] < 0 || m.start() < r[1])) {
                    c.tableIdx = r[2];
                }
            }
            // determine row index within table by counting <tr>...</tr> before pos in same range
            int tStart = -1, tEnd = -1;
            for (int[] r : topLevelRanges) {
                if (r[2] == c.tableIdx) { tStart = r[0]; tEnd = r[1]; break; }
            }
            if (tStart >= 0) {
                int rowIdx = -1;
                int rowStart = -1;
                Matcher trM = trOpen.matcher(md);
                trM.region(tStart, Math.min(md.length(), tEnd > 0 ? tEnd : md.length()));
                while (trM.find()) {
                    if (trM.start() > m.start()) break;
                    rowIdx++;
                    rowStart = trM.end();
                }
                c.rowIdx = rowIdx;
                // count cells before this one within the same row
                if (rowStart >= 0) {
                    Matcher cm = cellOpen.matcher(md);
                    cm.region(rowStart, m.start());
                    int colIdx = 0;
                    while (cm.find()) colIdx++;
                    c.colInRow = colIdx;
                }
            }
            // preview text
            int closeCellTagPos = md.indexOf("</" + m.group(1) + ">", m.end());
            if (closeCellTagPos > 0 && closeCellTagPos - m.end() < 200) {
                String pv = md.substring(m.end(), closeCellTagPos)
                        .replaceAll("<[^>]+>", "").replace("\n", " ").trim();
                if (pv.length() > 30) pv = pv.substring(0, 30) + "...";
                c.previewText = pv;
            }
            cells.add(c);
            // count for table
            if (c.tableIdx >= 0 && c.tableIdx < tables.size()) {
                tables.get(c.tableIdx).cellCount++;
            }
            ord++;
        }
        // count rows per table
        for (MdTable t : tables) {
            int tStart = -1, tEnd = -1;
            for (int[] r : topLevelRanges) {
                if (r[2] == t.idx) { tStart = r[0]; tEnd = r[1]; break; }
            }
            if (tStart >= 0) {
                Matcher trM = trOpen.matcher(md);
                trM.region(tStart, Math.min(md.length(), tEnd > 0 ? tEnd : md.length()));
                int n = 0;
                while (trM.find()) n++;
                t.rowCount = n;
            }
        }
    }

    private static void analyzeHwpx(String xml, List<HwpxCell> cells, List<HwpxTable> tables) {
        // Find table positions and dimensions
        Pattern tblOpenPat = Pattern.compile("<hp:tbl\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
        Pattern tblClosePat = Pattern.compile("</hp:tbl>", Pattern.CASE_INSENSITIVE);
        Pattern dimPat = Pattern.compile("rowCnt=\"(\\d+)\"[^>]*colCnt=\"(\\d+)\"");

        // events: [pos, type, end, tblIdx-allocated]
        List<int[]> events = new ArrayList<>();
        Matcher openM = tblOpenPat.matcher(xml);
        while (openM.find()) events.add(new int[]{openM.start(), 0, openM.end()});
        Matcher closeM = tblClosePat.matcher(xml);
        while (closeM.find()) events.add(new int[]{closeM.start(), 1, closeM.end()});
        events.sort(Comparator.comparingInt(a -> a[0]));

        Deque<HwpxTable> stack = new ArrayDeque<>();
        int topIdx = 0;
        List<int[]> topRanges = new ArrayList<>(); // [start,end,idx]
        for (int[] ev : events) {
            if (ev[1] == 0) {
                HwpxTable t = new HwpxTable();
                t.xmlPos = ev[0];
                // parse rowCnt/colCnt from open tag
                int tagEnd = ev[2];
                String tag = xml.substring(ev[0], tagEnd);
                Matcher dm = dimPat.matcher(tag);
                if (dm.find()) {
                    t.rowCnt = Integer.parseInt(dm.group(1));
                    t.colCnt = Integer.parseInt(dm.group(2));
                }
                if (stack.isEmpty()) {
                    t.idx = topIdx++;
                    tables.add(t);
                    topRanges.add(new int[]{ev[0], -1, t.idx});
                } else {
                    t.idx = -1;
                }
                stack.push(t);
            } else {
                if (!stack.isEmpty()) {
                    HwpxTable t = stack.pop();
                    if (t.idx >= 0) {
                        for (int[] r : topRanges) {
                            if (r[2] == t.idx) { r[1] = ev[2]; break; }
                        }
                    }
                }
            }
        }

        // Walk all <hp:tc>
        Pattern tcOpen = Pattern.compile("<hp:tc\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
        Pattern bfPat = Pattern.compile("borderFillIDRef=\"(\\d+)\"");
        Pattern addrPat = Pattern.compile("<hp:cellAddr\\s+colAddr=\"(\\d+)\"\\s+rowAddr=\"(\\d+)\"");

        Matcher m = tcOpen.matcher(xml);
        int ord = 0;
        while (m.find()) {
            HwpxCell c = new HwpxCell();
            c.ordinal = ord;
            c.xmlPos = m.start();
            String attrs = m.group(1);
            Matcher bfm = bfPat.matcher(attrs);
            c.borderFillIDRef = bfm.find() ? Integer.parseInt(bfm.group(1)) : -1;
            // tableIdx
            c.tableIdx = -1;
            for (int[] r : topRanges) {
                if (r[0] <= m.start() && (r[1] < 0 || m.start() < r[1])) c.tableIdx = r[2];
            }
            // cellAddr
            int searchEnd = Math.min(xml.length(), m.start() + 4000);
            Matcher am = addrPat.matcher(xml);
            am.region(m.start(), searchEnd);
            if (am.find()) {
                c.colAddr = Integer.parseInt(am.group(1));
                c.rowAddr = Integer.parseInt(am.group(2));
            } else {
                c.colAddr = -1; c.rowAddr = -1;
            }
            cells.add(c);
            if (c.tableIdx >= 0 && c.tableIdx < tables.size()) {
                tables.get(c.tableIdx).cellCount++;
            }
            ord++;
        }
    }

    private static int[] computeLineOffsets(String s) {
        List<Integer> offs = new ArrayList<>();
        offs.add(0);
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') offs.add(i + 1);
        }
        int[] arr = new int[offs.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = offs.get(i);
        return arr;
    }

    private static int posToLine(int[] lineOffsets, int pos) {
        int lo = 0, hi = lineOffsets.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (lineOffsets[mid] <= pos) lo = mid;
            else hi = mid - 1;
        }
        return lo + 1;
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
