package kr.n.nframe.newfeature;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import java.util.regex.*;

/**
 * MD → HWP/HWPX 변환 결과의 한컴 손상팝업 + 표 셀 문자겹침 후처리.
 *   - HWP: OLE2 의 _LinkDoc 스트림 제거 + FileHeader[44..48] 정규화.
 *   - HWPX: section0.xml 의 hp:p id 재배정 + linesegarray 제거.
 * App 영역 (HwpMdConverter / HwpConverter / mdlib) 무수정.
 */
public final class MdHwpRepairPostProcessor {

    public static void repair(String outputPath) throws IOException {
        File f = new File(outputPath);
        if (!f.exists() || !f.isFile()) return;
        String low = outputPath.toLowerCase(java.util.Locale.ROOT);
        if (low.endsWith(".hwp"))      repairHwp(f);
        else if (low.endsWith(".hwpx")) repairHwpx(f);
    }

    // ---- HWP (OLE2) ----
    private static void repairHwp(File hwp) throws IOException {
        // v16t18: 손상팝업 원인 = PrvText 스트림에 inline control byte (0x0B/0x0D)
        // 가 raw 로 노출되어 한컴이 alien byte 로 검출. 정상 HWP(B 그룹)는
        // PrvText 가 아예 없음. → PrvText 만 byte[0] 로 비워 미리보기 없는
        // 정상 HWP 로 인식시킨다. BodyText/Section0 의 인라인 컨트롤은 보존.
        File tmp = File.createTempFile("hwp_repair_", ".hwp");
        boolean changed = false;
        try (java.io.FileInputStream fin = new java.io.FileInputStream(hwp);
             org.apache.poi.poifs.filesystem.POIFSFileSystem src =
                 new org.apache.poi.poifs.filesystem.POIFSFileSystem(fin);
             org.apache.poi.poifs.filesystem.POIFSFileSystem dst =
                 new org.apache.poi.poifs.filesystem.POIFSFileSystem()) {

            changed = copyAndFilterPrvText(src.getRoot(), dst.getRoot());

            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                dst.writeFilesystem(fos);
            }
        } catch (Exception e) {
            try { Files.deleteIfExists(tmp.toPath()); } catch (IOException ignore) {}
            System.err.println("[Repair-HWP] 후처리 실패 (원본 유지): " + e.getMessage());
            return;
        }
        if (changed) {
            Files.move(tmp.toPath(), hwp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[Repair-HWP] PrvText 정규화 완료");
        } else {
            Files.deleteIfExists(tmp.toPath());
        }
    }

    /** 재귀 복사. PrvText 만 byte[0] 로 교체. 변경 여부 반환. */
    private static boolean copyAndFilterPrvText(
            org.apache.poi.poifs.filesystem.DirectoryEntry srcDir,
            org.apache.poi.poifs.filesystem.DirectoryEntry dstDir) throws IOException {
        boolean changed = false;
        for (org.apache.poi.poifs.filesystem.Entry e : srcDir) {
            String name = e.getName();
            if (e instanceof org.apache.poi.poifs.filesystem.DocumentEntry) {
                byte[] content;
                try (org.apache.poi.poifs.filesystem.DocumentInputStream dis =
                         new org.apache.poi.poifs.filesystem.DocumentInputStream(
                             (org.apache.poi.poifs.filesystem.DocumentEntry) e)) {
                    content = kr.n.nframe.newfeature.IoCompat.readAllBytes(dis);
                }
                if ("PrvText".equals(name)) {
                    // v16t21 [task1/STEP1]: 정적 placeholder 대신 BodyText 본문에서
                    //  실제 미리보기 텍스트를 추출해 PrvText 를 생성한다. 한컴은 PrvText
                    //  캐시가 본문과 불일치하면 손상으로 판정(3회 재발). 본문에서 normal
                    //  WCHAR(>=32) 만 추출하고 inline/extended 컨트롤(0x0B 등)은 통째로
                    //  건너뛰어 v16t18 의 alien-byte 노출도 동시에 회피. 추출 실패 시에만
                    //  기존 placeholder 로 fallback.
                    String preview = extractPreviewFromBody(srcDir);
                    if (preview == null || preview.isEmpty()) {
                        preview = "변환된 문서입니다.";
                    }
                    content = preview.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
                    changed = true;
                }
                // [v16t22] v16t20 의 DocInfo "totalPageCount" 패치 제거.
                //  근거(바이트): HWP5 DOCUMENT_PROPERTIES(0x10) 는 7×UINT16 + 캐럿 3×UINT32 = 26B
                //  로, totalPageCount 필드 자체가 없다. payload offset 20 은 캐럿 paragraphId
                //  (offset 18..21) 한가운데이며, 거기에 1 을 쓰면 paraId 0→65536, charPos 32→0 로
                //  손상되어 한컴이 '레이아웃/서식 변경될 수 있음' 손상팝업을 띄웠다(task1 5회 재발 원인).
                //  DocInfo 는 다른 스트림과 동일하게 무수정 통과시킨다.
                dstDir.createDocument(name, new java.io.ByteArrayInputStream(content));
            } else if (e instanceof org.apache.poi.poifs.filesystem.DirectoryEntry) {
                org.apache.poi.poifs.filesystem.DirectoryEntry subSrc =
                    (org.apache.poi.poifs.filesystem.DirectoryEntry) e;
                org.apache.poi.poifs.filesystem.DirectoryEntry subDst =
                    dstDir.createDirectory(name);
                if (copyAndFilterPrvText(subSrc, subDst)) changed = true;
            }
        }
        return changed;
    }

    // ── v16t21 PrvText 본문유래 생성 helper ─────────────────────────
    private static final int PRV_MAX_CHARS = 2000;

    /** root 의 BodyText/Section0..N 에서 미리보기 텍스트(최대 PRV_MAX_CHARS 자) 추출. */
    private static String extractPreviewFromBody(
            org.apache.poi.poifs.filesystem.DirectoryEntry root) {
        try {
            if (!root.hasEntry("BodyText")) return null;
            org.apache.poi.poifs.filesystem.Entry bt = root.getEntry("BodyText");
            if (!(bt instanceof org.apache.poi.poifs.filesystem.DirectoryEntry)) return null;
            org.apache.poi.poifs.filesystem.DirectoryEntry body =
                (org.apache.poi.poifs.filesystem.DirectoryEntry) bt;
            StringBuilder sb = new StringBuilder();
            for (int si = 0; si < 256 && sb.length() < PRV_MAX_CHARS; si++) {
                String sname = "Section" + si;
                if (!body.hasEntry(sname)) break;
                org.apache.poi.poifs.filesystem.Entry se = body.getEntry(sname);
                if (!(se instanceof org.apache.poi.poifs.filesystem.DocumentEntry)) continue;
                byte[] raw;
                try (org.apache.poi.poifs.filesystem.DocumentInputStream dis =
                         new org.apache.poi.poifs.filesystem.DocumentInputStream(
                             (org.apache.poi.poifs.filesystem.DocumentEntry) se)) {
                    raw = kr.n.nframe.newfeature.IoCompat.readAllBytes(dis);
                }
                byte[] decomp = inflateRaw(raw);
                if (decomp == null) decomp = raw; // 비압축 fallback
                extractParaText(decomp, sb, PRV_MAX_CHARS);
            }
            String s = sb.toString().trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            System.err.println("[Repair-HWP] PrvText 본문추출 실패 (placeholder 사용): " + e.getMessage());
            return null;
        }
    }

    /** raw deflate(no zlib wrapper) 해제. 실패/빈 결과면 null. */
    private static byte[] inflateRaw(byte[] in) {
        try {
            java.util.zip.Inflater inf = new java.util.zip.Inflater(true);
            inf.setInput(in);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(tmp);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                }
                bos.write(tmp, 0, n);
            }
            inf.end();
            byte[] out = bos.toByteArray();
            return out.length > 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Section record stream 을 순회하며 PARA_TEXT(tag 0x43) 의 정상 텍스트만 누적. */
    private static void extractParaText(byte[] data, StringBuilder out, int maxChars) {
        int pos = 0;
        while (pos + 4 <= data.length && out.length() < maxChars) {
            long hdr = (data[pos]&0xFFL) | ((data[pos+1]&0xFFL)<<8)
                     | ((data[pos+2]&0xFFL)<<16) | ((data[pos+3]&0xFFL)<<24);
            int tag = (int)(hdr & 0x3FF);
            int size = (int)((hdr >>> 20) & 0xFFF);
            pos += 4;
            if (size == 0xFFF) {
                if (pos + 4 > data.length) break;
                size = (data[pos]&0xFF) | ((data[pos+1]&0xFF)<<8)
                     | ((data[pos+2]&0xFF)<<16) | ((data[pos+3]&0xFF)<<24);
                pos += 4;
            }
            if (size < 0 || pos + size > data.length) break;
            if (tag == 0x43) { // PARA_TEXT
                appendParaText(data, pos, size, out, maxChars);
                if (out.length() > 0 && out.length() < maxChars
                        && out.charAt(out.length()-1) != '\n') {
                    out.append('\n');
                }
            }
            pos += size;
        }
    }

    /** PARA_TEXT payload(UTF-16LE WCHAR 배열)에서 정상 글자만 추출.
     *  inline/extended 컨트롤(16byte) 은 건너뛰고, 줄/문단 구분(10/13)은 \n 으로. */
    private static void appendParaText(byte[] data, int off, int size,
                                       StringBuilder out, int maxChars) {
        int end = off + size;
        int i = off;
        while (i + 2 <= end && out.length() < maxChars) {
            int wc = (data[i]&0xFF) | ((data[i+1]&0xFF)<<8);
            if (controlWidthBytes(wc) == 16) { i += 16; continue; }
            if (wc == 13 || wc == 10) {
                if (out.length() > 0 && out.charAt(out.length()-1) != '\n') out.append('\n');
            } else if (wc >= 32) {
                out.append((char) wc);
            }
            i += 2;
        }
    }

    /** HWP5 PARA_TEXT 컨트롤 WCHAR 폭(byte). inline/extended=16, 그 외=2. */
    private static int controlWidthBytes(int wc) {
        switch (wc) {
            case 1: case 2: case 3: case 4: case 5: case 6: case 7: case 8: case 9:
            case 11: case 12: case 14: case 15: case 16: case 17: case 18:
            case 19: case 20: case 21: case 22: case 23:
                return 16;
            default:
                return 2;
        }
    }


    // ---- HWPX (zip) ----
    private static final Pattern HP_P_ID = Pattern.compile("(<hp:p\\s[^>]*\\bid=\")(\\d+)(\")");
    private static final Pattern LINESEG_ARRAY_OPEN_CLOSE =
        Pattern.compile("<hp:linesegarray>.*?</hp:linesegarray>", Pattern.DOTALL);
    private static final Pattern LINESEG_ARRAY_SELF =
        Pattern.compile("<hp:linesegarray\\s*/>");
    // [task2] 표 위치(hp:tbl→hp:sz→hp:pos)의 첫 treatAsChar 매칭(값 0/1 무관). 그림(hp:pic)은 미매칭.
    private static final Pattern TBL_POS_TAC =
        Pattern.compile("(<hp:tbl\\b[^>]*>\\s*<hp:sz\\b[^>]*/>\\s*<hp:pos\\b[^>]*?)treatAsChar=\"[01]\"", Pattern.DOTALL);
    // [task2] 표 중첩깊이 계산용 토큰: 여는 <hp:tbl (group1) / 닫는 </hp:tbl> (group2).
    private static final Pattern TBL_TOKEN =
        Pattern.compile("(<hp:tbl\\b)|(</hp:tbl>)");

    private static void repairHwpx(File hwpx) throws IOException {
        File tmp = File.createTempFile("hwpx_repair_", ".hwpx");
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(hwpx));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(tmp))) {
            byte[] buf = new byte[8192];
            ZipEntry entry;
            boolean mimetypeFirst = false;
            int idStripped = 0;
            int linesegStripped = 0;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                int n; while ((n = zin.read(buf)) > 0) bo.write(buf, 0, n);
                byte[] content = bo.toByteArray();

                // mimetype 첫 entry 보존 (STORED, no compress)
                ZipEntry out;
                if (name.equals("mimetype") && !mimetypeFirst) {
                    out = new ZipEntry(name);
                    out.setMethod(ZipEntry.STORED);
                    out.setSize(content.length);
                    java.util.zip.CRC32 c = new java.util.zip.CRC32();
                    c.update(content);
                    out.setCrc(c.getValue());
                    mimetypeFirst = true;
                } else {
                    out = new ZipEntry(name);
                    out.setMethod(ZipEntry.DEFLATED);
                }

                // section*.xml 만 후처리
                if (name.endsWith(".xml") && name.contains("section")) {
                    String xml = new String(content, "UTF-8");

                    // 1) hp:p id 재배정 (0..N)
                    Matcher m = HP_P_ID.matcher(xml);
                    StringBuffer sb = new StringBuffer();
                    int idx = 0;
                    while (m.find()) {
                        m.appendReplacement(sb, "$1" + (idx++) + "$3");
                        idStripped++;
                    }
                    m.appendTail(sb);
                    xml = sb.toString();

                    // 2) linesegarray 전체 제거
                    String before = xml;
                    xml = LINESEG_ARRAY_OPEN_CLOSE.matcher(xml).replaceAll("");
                    xml = LINESEG_ARRAY_SELF.matcher(xml).replaceAll("");
                    if (!xml.equals(before)) linesegStripped++;

                    // [task2] 표 글자처럼취급을 깊이기반으로: 최상위표(depth0)=tac0(=한컴 '나눔'으로 페이지분할),
                    // 중첩표(표안의표, depth>=1)=tac1(인라인, 겹침 해소 — 원본도 중첩표는 인라인). + pageBreak 전부 CELL.
                    // 그림(hp:pic→hp:pos)의 treatAsChar=1 은 13팝업(그림 lineseg) 수정 보존을 위해 절대 미접촉.
                    String before2 = xml;
                    // 1) pageBreak 통일 → 전부 CELL (한컴 UI '나눔'에 매핑. 실증: HWPX CELL=한컴'나눔',
                    //    TABLE=한컴'셀단위로', NONE=한컴'나누지않음'. 표 속성이라 전역치환 안전)
                    xml = xml.replace("pageBreak=\"TABLE\"", "pageBreak=\"CELL\"");
                    xml = xml.replace("pageBreak=\"NONE\"", "pageBreak=\"CELL\"");
                    // 2) 표 중첩깊이 맵: 여는 <hp:tbl offset → 그 시점 depth. (pageBreak 치환 후 문자열 기준)
                    java.util.HashMap<Integer,Integer> tblDepth = new java.util.HashMap<>();
                    Matcher tok = TBL_TOKEN.matcher(xml);
                    int depth = 0;
                    while (tok.find()) {
                        if (tok.group(1) != null) { tblDepth.put(tok.start(), depth); depth++; }
                        else { depth--; }
                    }
                    // 3) 표 첫 hp:pos 의 treatAsChar 을 깊이기반 값으로. 그림 hp:pos 는 패턴에 안 걸려 미접촉.
                    Matcher pm = TBL_POS_TAC.matcher(xml);
                    StringBuffer sb2 = new StringBuffer();
                    while (pm.find()) {
                        Integer d = tblDepth.get(pm.start());
                        String tacVal = (d != null && d == 0) ? "0" : "1";
                        pm.appendReplacement(sb2, Matcher.quoteReplacement(pm.group(1) + "treatAsChar=\"" + tacVal + "\""));
                    }
                    pm.appendTail(sb2);
                    xml = sb2.toString();
                    if (!xml.equals(before2)) {
                        System.out.println("[Repair-HWPX] 최상위표 tac=0 / 중첩표 tac=1 / pageBreak=CELL 적용");
                    }

                    content = xml.getBytes("UTF-8");
                    if (out.getMethod() == ZipEntry.STORED) {
                        out.setSize(content.length);
                        java.util.zip.CRC32 c = new java.util.zip.CRC32();
                        c.update(content);
                        out.setCrc(c.getValue());
                    }
                }

                zout.putNextEntry(out);
                zout.write(content);
                zout.closeEntry();
            }
            if (idStripped > 0 || linesegStripped > 0) {
                System.out.println("[Repair-HWPX] hp:p id 재배정="+idStripped+", linesegarray 제거="+(linesegStripped>0));
            }
        }
        Files.move(tmp.toPath(), hwpx.toPath(),
            StandardCopyOption.REPLACE_EXISTING);
    }

    private MdHwpRepairPostProcessor() {} // util only
}
