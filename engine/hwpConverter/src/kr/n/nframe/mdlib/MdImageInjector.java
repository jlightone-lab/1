package kr.n.nframe.mdlib;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

import javax.imageio.ImageIO;

import kr.n.nframe.HwpConverter;

/**
 * v14.83~v14.85: MdToHwpRich가 출력한 .hwp의 이미지 placeholder 텍스트를
 * 실제 picture control + BinData로 치환한다.
 *
 * 동작:
 *   1) MD에서 모든 data: URI 이미지(`![alt](data:image/...;base64,...)`)를 추출.
 *      - inline 이미지 (본문)
 *      - 셀 본문에 prepend 된 이미지 (HwpMdConverter v14.85 cellBgImageMd 가 emit)
 *   2) 입력 .hwp → 임시 .hwpx (HwpConverter.convertHwpToHwpx)
 *   3) section0.xml의 "[이미지: alt]" placeholder 텍스트를 sequential 하게 찾아
 *      &lt;hp:pic&gt; element 로 교체 (셀 내부의 placeholder도 동일하게 처리됨).
 *      content.hpf에 binItem 추가, BinData/imageN 엔트리 추가.
 *   4) 수정된 .hwpx → .hwp (HwpConverter.convertHwpxToHwp, 자체 picture 직렬화)
 *
 * 이 후처리 단계는 hwplib HWPWriter의 picture 호환 한계를 우회한다.
 * 한/글 호환이 검증된 자체 SectionWriter.writePicture로 최종 출력된다.
 */
public class MdImageInjector {

    static class ImgEntry {
        String alt;          // MD alt 원본
        String placeholder;  // "[이미지: alt]"
        byte[] data;
        String ext;          // png, jpg, bmp, gif
        String mediaType;    // image/png, ...
        int pixelW, pixelH;
        String binId;        // image1, image2, ...
    }

    /** v14.84 task2: MD &lt;td/th style="...background-image: url(data:...)..."&gt; 셀 배경 정보.
     *  [v14.93 task1] 셀 위치를 (top-level table 인덱스, 그 표 안에서의 top-level 셀 ordinal) 로
     *  추적 — flat ordinal 은 nested 표 압축이나 normalize 로 셀 수가 변하면 누적 어긋남 발생. */
    static class CellBg {
        int tableIdx;        // top-level table index in MD (0-based, only top-level)
        int cellIdxInTable;  // index of this cell within the top-level table (skipping nested cells)
        byte[] data;
        String ext;
        String mediaType;
        String binId;
    }

    /** [v14.94] MD → HWPX 라운드트립용. inject() 와 동일하게 MD 의 data URI 이미지/셀 배경을
     *  HWPX 에 주입하지만, 최종 결과를 .hwp 가 아닌 .hwpx 로 저장한다. MD → HWP (MdToHwpRich)
     *  → HWP → HWPX (convertHwpToHwpx) → 이미지/셀 배경 주입 → HWPX 저장 흐름의 마지막 단계.
     *  inject() 의 마지막 step (HWPX → HWP 재변환) 을 생략 — MD → HWP 와 모든 fix 가 그대로 적용된
     *  HWP 를 source 로 사용하므로 표 구조/정렬/nested margin/cell 배경 등이 일관되게 보존됨. */
    public void injectAsHwpx(String mdPath, String hwpPath, String hwpxOutPath) throws Exception {
        List<ImgEntry> images = extractDataUriImages(mdPath);
        List<CellBg> cellBgs = extractCellBgs(mdPath, images.size());
        Path tmpDir = Files.createTempDirectory("md_img_inject_hwpx_");
        try {
            Path tempHwpx = tmpDir.resolve("step1.hwpx");
            new HwpConverter().convertHwpToHwpx(hwpPath, tempHwpx.toString());
            Path outHwpx = Paths.get(hwpxOutPath);
            if (images.isEmpty() && cellBgs.isEmpty()) {
                Files.copy(tempHwpx, outHwpx, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[MdImageInjector] data URI 이미지/셀 배경 없음 — temp HWPX 그대로 저장: "
                        + hwpxOutPath + " (" + Files.size(outHwpx) + " bytes)");
            } else {
                int[] counts = modifyHwpxWithImages(tempHwpx, outHwpx, images, cellBgs);
                System.out.println("[MdImageInjector] inline 치환: " + counts[0] + "/" + images.size()
                        + ", 셀 배경 적용: " + counts[1] + "/" + cellBgs.size());
                System.out.println("[MdImageInjector] 최종 .hwpx 저장: " + hwpxOutPath
                        + " (" + Files.size(outHwpx) + " bytes)");
            }
            // [v15.29 task1] HWPX 측 PageNumberPosition 보강 시도 → 원본 HWPX 도
            //   hp:pageNumberPosition 을 사용하지 않음을 확인 (OWPML 은 hp:secPr 의
            //   하위 hp:startNum / hp:visibility hideFirstPageNum 으로 페이지 번호를
            //   제어). HWP binary 와 OWPML 의 구조 차이 — HWPX 측은 추가 보강 불요.
        } finally {
            try {
                Files.walk(tmpDir).sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException e) { /* ignore */ } });
            } catch (Exception ignored) { }
        }
    }

    public void inject(String mdPath, String hwpPath) throws Exception {
        List<ImgEntry> images = extractDataUriImages(mdPath);
        // [v14.85 task2] 셀 배경 (background-image style) → HWPX BorderFill image fill 주입.
        //   ordinal flat matching 사용 — MdToHwpRich 가 셀 순서를 보존하므로 MD 의 N번째
        //   td/th 가 HWPX 의 N번째 hp:tc 에 매핑됨. nested 펼치기로 일부 어긋날 수 있음.
        List<CellBg> cellBgs = extractCellBgs(mdPath, images.size());
        if (images.isEmpty() && cellBgs.isEmpty()) {
            System.out.println("[MdImageInjector] data URI 이미지 / 셀 배경 없음 — skip");
            return;
        }
        System.out.println("[MdImageInjector] inline 이미지 " + images.size()
                + "개, 셀 배경 " + cellBgs.size() + "개 주입 시작");

        Path tmpDir = Files.createTempDirectory("md_img_inject_");
        try {
            Path tempHwpx = tmpDir.resolve("step1.hwpx");
            new HwpConverter().convertHwpToHwpx(hwpPath, tempHwpx.toString());

            Path modifiedHwpx = tmpDir.resolve("step2.hwpx");
            int[] counts = modifyHwpxWithImages(tempHwpx, modifiedHwpx, images, cellBgs);
            System.out.println("[MdImageInjector] inline 치환: " + counts[0] + "/" + images.size()
                    + ", 셀 배경 적용: " + counts[1] + "/" + cellBgs.size());

            // [v15.33 task1/2] HWPX → HWP 라운드트립 직전에 입력 HWP 의 PrvImage 바이트를
            //   캡처. HwpWriter (kr.n.nframe.hwplib.writer) 는 HWPX → HWP 변환 시 PrvImage
            //   스트림을 0 byte 로 작성하는데 (HWPX 에는 OLE PrvImage 가 없기 때문), 한/글이
            //   "문서에 손상을 줄 수 있는 내용이 포함되어 있습니다" 손상 경고 팝업을 트리거.
            //   working reference (입찰공고문.hwp 44420 byte, case1_tap제거.hwp 42594 byte)
            //   는 모두 PrvImage 스트림을 비어 있지 않게 유지함. 입력 HWP 는 MdToHwpRich 가
            //   template-rich.hwp 를 mutate 한 것이라 항상 valid PrvImage (~36438 byte 이상)
            //   을 보유 → 라운드트립 후 final HWP 에 다시 주입한다.
            byte[] prvImageBytes = readOleStreamSafe(Paths.get(hwpPath), "PrvImage");

            Path tempOut = tmpDir.resolve("step3.hwp");
            new HwpConverter().convertHwpxToHwp(modifiedHwpx.toString(), tempOut.toString());

            // [v15.30 task2] v15.29 의 ensurePageNumberPositionInHwp 는 HWP 를 다시
            //   read → modify → write 하는 라운드트립을 수행 → hwplib HWPWriter 가
            //   read 한 일부 record (특히 우리 SectionWriter 로 작성한 custom record)
            //   를 정확히 다시 직렬화하지 못해 한/글이 "파일이 손상되었습니다" 로 거부.
            //   본 라운드트립 제거 — para[0] ControlPageNumberPosition 추가는 v15.29 의
            //   MdToHwpRich.ensureSectionPara0Controls 단계에서만 처리하고 final HWP
            //   에서는 추가 수정하지 않는다.

            Files.copy(tempOut, Paths.get(hwpPath), StandardCopyOption.REPLACE_EXISTING);

            // [v15.33 task1/2] 캡처한 PrvImage 를 final HWP 에 재주입 — POI 로 OLE 스트림만
            //   교체 (body content 는 건드리지 않음). 한/글 손상 경고 회귀 방지.
            if (prvImageBytes != null && prvImageBytes.length > 0) {
                int restored = writeOleStream(Paths.get(hwpPath), "PrvImage", prvImageBytes);
                if (restored > 0) {
                    System.out.println("[MdImageInjector] PrvImage 재주입: " + restored + " bytes");
                }
            }
            System.out.println("[MdImageInjector] 최종 .hwp 저장: " + hwpPath
                    + " (" + Files.size(Paths.get(hwpPath)) + " bytes)");
        } finally {
            try {
                Files.walk(tmpDir).sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException e) { /* ignore */ } });
            } catch (Exception ignored) { }
        }
    }

    private List<CellBg> extractCellBgs(String mdPath, int inlineImgCount) throws IOException {
        String md = new String(Files.readAllBytes(Paths.get(mdPath)), StandardCharsets.UTF_8);
        List<CellBg> list = new ArrayList<>();
        // [v14.93 task1] tag 단위로 walk 하며 <table> 깊이 추적. depth==1 (top-level table 안)
        //   에서만 cell 카운트. nested table 안의 td/th 는 무시 — MdToHwpRich 의 nested 압축으로
        //   인한 cell 수 mismatch 회피.
        Pattern tagPat = Pattern.compile(
                "<(/?)(table|td|th)\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
        Pattern bgPat = Pattern.compile(
                "background-image\\s*:\\s*url\\(\\s*data:image/([a-zA-Z0-9]+);base64,([^)\\s]+)\\s*\\)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = tagPat.matcher(md);
        int seq = inlineImgCount + 1;
        int tableDepth = 0;
        int topLevelTableIdx = -1;
        int cellIdxInTable = 0;
        while (m.find()) {
            boolean isClose = !m.group(1).isEmpty();
            String tag = m.group(2).toLowerCase();
            String attrs = m.group(3);
            if ("table".equals(tag)) {
                if (isClose) {
                    if (tableDepth > 0) tableDepth--;
                } else {
                    if (tableDepth == 0) {
                        topLevelTableIdx++;
                        cellIdxInTable = 0;
                    }
                    tableDepth++;
                }
                continue;
            }
            // td/th: only count when at top-level table depth
            if (isClose) continue;
            if (tableDepth != 1) continue;
            Matcher bm = bgPat.matcher(attrs);
            if (bm.find()) {
                CellBg cb = new CellBg();
                cb.tableIdx = topLevelTableIdx;
                cb.cellIdxInTable = cellIdxInTable;
                String type = bm.group(1).toLowerCase();
                String b64 = bm.group(2).replaceAll("\\s+", "");
                cb.data = Base64.getDecoder().decode(b64);
                switch (type) {
                    case "png":  cb.ext = "png"; cb.mediaType = "image/png";  break;
                    case "jpg":
                    case "jpeg": cb.ext = "jpg"; cb.mediaType = "image/jpeg"; break;
                    case "bmp":  cb.ext = "bmp"; cb.mediaType = "image/bmp";  break;
                    case "gif":  cb.ext = "gif"; cb.mediaType = "image/gif";  break;
                    default:     cb.ext = type; cb.mediaType = "image/" + type; break;
                }
                cb.binId = "image" + seq;
                seq++;
                list.add(cb);
            }
            cellIdxInTable++;
        }
        return list;
    }

    private List<ImgEntry> extractDataUriImages(String mdPath) throws IOException {
        String md = new String(Files.readAllBytes(Paths.get(mdPath)), StandardCharsets.UTF_8);
        List<ImgEntry> list = new ArrayList<>();
        Pattern p = Pattern.compile("!\\[([^\\]]*)\\]\\(data:image/([a-zA-Z0-9]+);base64,([^)]+)\\)");
        Matcher m = p.matcher(md);
        int idx = 1;
        while (m.find()) {
            ImgEntry e = new ImgEntry();
            e.alt = m.group(1);
            String type = m.group(2).toLowerCase();
            String b64 = m.group(3).replaceAll("\\s+", "");
            e.data = Base64.getDecoder().decode(b64);
            switch (type) {
                case "png":  e.ext = "png"; e.mediaType = "image/png";  break;
                case "jpg":
                case "jpeg": e.ext = "jpg"; e.mediaType = "image/jpeg"; break;
                case "bmp":  e.ext = "bmp"; e.mediaType = "image/bmp";  break;
                case "gif":  e.ext = "gif"; e.mediaType = "image/gif";  break;
                default:     e.ext = type; e.mediaType = "image/" + type; break;
            }
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(e.data));
                if (img != null) { e.pixelW = img.getWidth(); e.pixelH = img.getHeight(); }
            } catch (Throwable t) { /* fallback below */ }
            if (e.pixelW <= 0) e.pixelW = 600;
            if (e.pixelH <= 0) e.pixelH = 400;
            e.placeholder = "[이미지: " + e.alt + "]";
            e.binId = "image" + idx;
            idx++;
            list.add(e);
        }
        return list;
    }

    /** @return [inlineReplaced, cellBgsApplied] */
    private int[] modifyHwpxWithImages(Path inHwpx, Path outHwpx,
                                        List<ImgEntry> images, List<CellBg> cellBgs) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile z = new ZipFile(inHwpx.toFile())) {
            Enumeration<? extends ZipEntry> es = z.entries();
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                if (e.isDirectory()) continue;
                try (InputStream is = z.getInputStream(e)) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                    entries.put(e.getName(), bos.toByteArray());
                }
            }
        }

        int existingBfCount = countBorderFills(entries.get("Contents/header.xml"));
        // [v14.93 task1] (tableIdx, cellIdxInTable) → bfId 매핑.
        Map<Long, Integer> coordToBfId = new HashMap<>();
        int newBfId = existingBfCount;
        for (CellBg cb : cellBgs) {
            long key = ((long) cb.tableIdx << 32) | (cb.cellIdxInTable & 0xFFFFFFFFL);
            coordToBfId.put(key, newBfId);
            newBfId++;
        }

        int replaced = 0;
        int bgApplied = 0;
        byte[] sec0 = entries.get("Contents/section0.xml");
        if (sec0 != null) {
            String xml = new String(sec0, StandardCharsets.UTF_8);
            int imgIdx0 = 0;
            for (ImgEntry img : images) {
                // [표 셀 이미지 역방향] 표 셀 내부 이미지는 MdRichParser 가 짧은 토큰
                //   "[[IMG#k]]" (k = 0-based 이미지 순번) 로 남긴다. 긴 "[이미지: alt]"
                //   placeholder 는 좁은 셀 폭에서 여러 문단으로 wrap 되어 매칭이 깨지므로,
                //   먼저 이 짧은 셀 토큰을 index 로 찾아 치환한다. 셀 토큰이 없으면(=본문
                //   이미지) 기존 "[이미지: alt]" placeholder 를 찾는다.
                int textIdx = xml.indexOf("[[IMG#" + imgIdx0 + "]]</hp:t>");
                if (textIdx < 0) {
                    String escaped = xmlEscape(img.placeholder);
                    textIdx = xml.indexOf(escaped + "</hp:t>");
                }
                imgIdx0++;
                if (textIdx < 0) continue;
                int tagOpen = xml.lastIndexOf("<hp:t", textIdx);
                if (tagOpen < 0) continue;
                int tagEnd = xml.indexOf("</hp:t>", textIdx) + "</hp:t>".length();
                // [표 셀 이미지 폭 clamp] 토큰이 표 셀 내부이면 이미지 최대폭을 셀
                //   사용가능폭으로 제한한다. 셀 사용가능폭 = 토큰을 감싸는 가장 가까운
                //   hp:subList 의 textWidth (한컴이 이미 셀 여백을 반영해 계산한 값).
                //   표 밖 본문 이미지는 cellMaxW=-1 로 종전(페이지폭 clamp) 그대로 — 무접촉.
                int cellMaxW = -1;
                int tcOpen = xml.lastIndexOf("<hp:tc", tagOpen);
                int tcClose = xml.lastIndexOf("</hp:tc>", tagOpen);
                if (tcOpen > tcClose) {
                    int slOpen = xml.lastIndexOf("<hp:subList", tagOpen);
                    if (slOpen > tcOpen) {
                        int slEnd = xml.indexOf('>', slOpen);
                        if (slEnd > slOpen) {
                            Matcher tw = CELL_TEXTWIDTH_RE.matcher(xml.substring(slOpen, slEnd));
                            if (tw.find()) cellMaxW = Integer.parseInt(tw.group(1));
                        }
                    }
                }
                String pic = buildPicXml(img, cellMaxW);
                xml = xml.substring(0, tagOpen) + pic + xml.substring(tagEnd);
                // [task1] 그림 주입 문단의 stale linesegarray 를 진짜원작(한컴) 패턴으로 정합.
                //   hwp2hwpx 가 placeholder 텍스트("[이미지: alt]")용으로 계산한 2-세그먼트
                //   (textpos=0/66·줄높이 1500·둘째 dangling)가 <hp:t>→<hp:pic> 교체 후에도 남아
                //   최종 .hwp 에 nchars(9) 초과 textpos 와 그림높이 아닌 줄높이로 기록 → 한/글
                //   "문서 손상/레이아웃 변경" 팝업. 한컴 원작은 1세그(textpos=0, 줄높이=그림높이)이므로
                //   동일하게 collapse 한다. 줄높이/textheight=그림height, baseline=height×0.85.
                int picH = parsePicHeight(pic);
                if (picH > 0) xml = collapsePictureLineSeg(xml, tagOpen, picH);
                replaced++;
            }
            if (!coordToBfId.isEmpty()) {
                // [v14.93 task1] hp:tbl 깊이 추적 → top-level table 안의 hp:tc 만 카운트.
                //   nested table 내부 cells 는 무시 (MdToHwpRich 가 nested 압축할 수 있어 cell 수
                //   안정성 보장 어려움).
                StringBuilder out = new StringBuilder(xml.length() + 1024);
                Pattern tagPat = Pattern.compile(
                        "<(/?)(hp:tbl|hp:tc)\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
                Matcher mm = tagPat.matcher(xml);
                int last = 0;
                int tblDepth = 0;
                int topTableIdx = -1;
                int cellIdxInTable = 0;
                while (mm.find()) {
                    out.append(xml, last, mm.start());
                    boolean isClose = !mm.group(1).isEmpty();
                    String tag = mm.group(2).toLowerCase();
                    String attrs = mm.group(3);
                    if ("hp:tbl".equals(tag)) {
                        if (isClose) {
                            if (tblDepth > 0) tblDepth--;
                        } else {
                            if (tblDepth == 0) {
                                topTableIdx++;
                                cellIdxInTable = 0;
                            }
                            tblDepth++;
                        }
                        out.append(mm.group());
                    } else { // hp:tc
                        if (isClose || tblDepth != 1) {
                            out.append(mm.group());
                        } else {
                            long key = ((long) topTableIdx << 32) | (cellIdxInTable & 0xFFFFFFFFL);
                            Integer bfId = coordToBfId.get(key);
                            if (bfId != null) {
                                out.append("<hp:tc").append(replaceBorderFillRef(attrs, bfId)).append(">");
                                bgApplied++;
                            } else {
                                out.append(mm.group());
                            }
                            cellIdxInTable++;
                        }
                    }
                    last = mm.end();
                }
                out.append(xml, last, xml.length());
                xml = out.toString();
            }
            entries.put("Contents/section0.xml", xml.getBytes(StandardCharsets.UTF_8));
        }

        if (!cellBgs.isEmpty()) {
            byte[] hdr = entries.get("Contents/header.xml");
            if (hdr != null) {
                String hStr = new String(hdr, StandardCharsets.UTF_8);
                StringBuilder bfXml = new StringBuilder();
                int currentId = existingBfCount;
                for (CellBg cb : cellBgs) {
                    bfXml.append(buildBorderFillImageXml(currentId, cb.binId));
                    currentId++;
                }
                int closeIdx = hStr.indexOf("</hh:borderFills>");
                if (closeIdx >= 0) {
                    hStr = hStr.substring(0, closeIdx) + bfXml + hStr.substring(closeIdx);
                    hStr = updateBorderFillCount(hStr, existingBfCount + cellBgs.size());
                    entries.put("Contents/header.xml", hStr.getBytes(StandardCharsets.UTF_8));
                }
            }
        }

        byte[] hpf = entries.get("Contents/content.hpf");
        if (hpf != null) {
            String hpfStr = new String(hpf, StandardCharsets.UTF_8);
            StringBuilder items = new StringBuilder();
            for (ImgEntry img : images) {
                items.append("<opf:item id=\"").append(img.binId)
                     .append("\" href=\"BinData/").append(img.binId).append(".").append(img.ext)
                     .append("\" media-type=\"").append(img.mediaType)
                     .append("\" isEmbeded=\"1\"/>");
            }
            for (CellBg cb : cellBgs) {
                items.append("<opf:item id=\"").append(cb.binId)
                     .append("\" href=\"BinData/").append(cb.binId).append(".").append(cb.ext)
                     .append("\" media-type=\"").append(cb.mediaType)
                     .append("\" isEmbeded=\"1\"/>");
            }
            int pos = hpfStr.indexOf("</opf:manifest>");
            if (pos >= 0) {
                hpfStr = hpfStr.substring(0, pos) + items.toString() + hpfStr.substring(pos);
                entries.put("Contents/content.hpf", hpfStr.getBytes(StandardCharsets.UTF_8));
            }
        }

        for (ImgEntry img : images) {
            entries.put("BinData/" + img.binId + "." + img.ext, img.data);
        }
        for (CellBg cb : cellBgs) {
            entries.put("BinData/" + cb.binId + "." + cb.ext, cb.data);
        }

        try (OutputStream os = Files.newOutputStream(outHwpx);
             ZipOutputStream zo = new ZipOutputStream(os, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> en : entries.entrySet()) {
                if ("mimetype".equals(en.getKey())) {
                    ZipEntry mz = new ZipEntry("mimetype");
                    mz.setMethod(ZipEntry.STORED);
                    mz.setSize(en.getValue().length);
                    mz.setCompressedSize(en.getValue().length);
                    CRC32 crc = new CRC32();
                    crc.update(en.getValue());
                    mz.setCrc(crc.getValue());
                    zo.putNextEntry(mz);
                } else {
                    zo.putNextEntry(new ZipEntry(en.getKey()));
                }
                zo.write(en.getValue());
                zo.closeEntry();
            }
        }
        return new int[]{replaced, bgApplied};
    }

    private int countBorderFills(byte[] headerBytes) {
        if (headerBytes == null) return 0;
        String s = new String(headerBytes, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<hh:borderFills\\b[^>]*itemCnt\\s*=\\s*\"(\\d+)\"").matcher(s);
        if (m.find()) return Integer.parseInt(m.group(1));
        return 0;
    }

    private String updateBorderFillCount(String headerXml, int newCount) {
        return headerXml.replaceFirst(
                "(<hh:borderFills\\b[^>]*itemCnt\\s*=\\s*\")\\d+(\")",
                "$1" + newCount + "$2");
    }

    private String replaceBorderFillRef(String attrs, int newId) {
        String idStr = String.valueOf(newId + 1);
        if (attrs.contains("borderFillIDRef")) {
            return attrs.replaceFirst(
                    "borderFillIDRef\\s*=\\s*\"\\d+\"",
                    "borderFillIDRef=\"" + idStr + "\"");
        }
        return attrs + " borderFillIDRef=\"" + idStr + "\"";
    }

    private String buildBorderFillImageXml(int id, String binId) {
        StringBuilder sb = new StringBuilder();
        sb.append("<hh:borderFill id=\"").append(id + 1)
          .append("\" threeD=\"0\" shadow=\"0\" slash=\"NONE\" backSlash=\"NONE\""
                + " breakCellSeparateLine=\"0\">");
        sb.append("<hh:slash type=\"NONE\" Crooked=\"0\" isCounter=\"0\"/>");
        sb.append("<hh:backSlash type=\"NONE\" Crooked=\"0\" isCounter=\"0\"/>");
        sb.append("<hh:leftBorder type=\"SOLID\" width=\"0.1 mm\" color=\"#000000\"/>");
        sb.append("<hh:rightBorder type=\"SOLID\" width=\"0.1 mm\" color=\"#000000\"/>");
        sb.append("<hh:topBorder type=\"SOLID\" width=\"0.1 mm\" color=\"#000000\"/>");
        sb.append("<hh:bottomBorder type=\"SOLID\" width=\"0.1 mm\" color=\"#000000\"/>");
        sb.append("<hh:diagonal type=\"SOLID\" width=\"0.1 mm\" color=\"#000000\"/>");
        sb.append("<hc:fillBrush>");
        sb.append("<hc:imgBrush mode=\"TOTAL\">");
        sb.append("<hc:img bright=\"0\" contrast=\"0\" effect=\"REAL_PIC\" binaryItemIDRef=\"")
          .append(binId).append("\"/>");
        sb.append("</hc:imgBrush>");
        sb.append("</hc:fillBrush>");
        sb.append("</hh:borderFill>");
        return sb.toString();
    }

    /** buildPicXml 이 기록한 &lt;hp:orgSz height="..."&gt; 에서 그림 표시높이(HWPUNIT)를 읽는다.
     *  collapsePictureLineSeg 의 줄높이가 실제 기록된 그림높이와 항상 일치하도록 pic 문자열에서 직접 파싱. */
    private int parsePicHeight(String picXml) {
        Matcher m = Pattern.compile("<hp:orgSz\\b[^>]*\\bheight=\"(\\d+)\"").matcher(picXml);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignore) { }
        }
        return -1;
    }

    /** 그림이 주입된 문단의 linesegarray 를 한컴 원작식 단일 세그먼트로 collapse 한다.
     *  - picPos 가 포함된 &lt;hp:p&gt;…&lt;/hp:p&gt; 안의 linesegarray 만 대상(그림 외 문단 불변).
     *  - 기존 첫 lineseg 의 위치/폭/줄간격(vertpos/horzpos/horzsize/spacing/tag) 은 유지하고
     *    줄높이(vertsize/textheight)=그림높이, baseline=그림높이×0.85 로만 보정한 뒤
     *    나머지 세그먼트(옛 placeholder 텍스트용 dangling 세그)를 버린다. */
    private String collapsePictureLineSeg(String xml, int picPos, int picH) {
        int pEnd = xml.indexOf("</hp:p>", picPos);
        if (pEnd < 0) return xml;
        int pStart = -1;
        for (int i = Math.min(picPos, xml.length() - 1); i >= 0; i--) {
            if (xml.startsWith("<hp:p ", i) || xml.startsWith("<hp:p>", i)) { pStart = i; break; }
        }
        if (pStart < 0) return xml;
        int lsStart = xml.indexOf("<hp:linesegarray", pStart);
        if (lsStart < 0 || lsStart > pEnd) return xml;          // 이 문단에 linesegarray 없음
        int lsClose = xml.indexOf("</hp:linesegarray>", lsStart);
        if (lsClose < 0 || lsClose > pEnd) return xml;
        int lsEnd = lsClose + "</hp:linesegarray>".length();
        // 주의: "<hp:linesegarray" 가 "<hp:lineseg" 를 접두로 포함하므로 trailing space 로 내부 세그만 매칭.
        int segStart = xml.indexOf("<hp:lineseg ", lsStart);
        if (segStart < 0 || segStart >= lsClose) return xml;
        int segEnd = xml.indexOf(">", segStart);
        if (segEnd < 0 || segEnd > lsClose) return xml;
        String seg = xml.substring(segStart, segEnd + 1);       // 첫 세그(self-closing <hp:lineseg .../>)
        int baseline = picH * 85 / 100;
        seg = setSegAttr(seg, "vertsize", picH);
        seg = setSegAttr(seg, "textheight", picH);
        seg = setSegAttr(seg, "baseline", baseline);
        String newLsa = "<hp:linesegarray>" + seg + "</hp:linesegarray>";
        return xml.substring(0, lsStart) + newLsa + xml.substring(lsEnd);
    }

    private String setSegAttr(String segTag, String name, int val) {
        Matcher m = Pattern.compile(name + "\\s*=\\s*\"[^\"]*\"").matcher(segTag);
        if (m.find()) return m.replaceFirst(name + "=\"" + val + "\"");
        int sc = segTag.lastIndexOf("/>");
        if (sc >= 0) return segTag.substring(0, sc) + " " + name + "=\"" + val + "\"" + segTag.substring(sc);
        return segTag;
    }

    /** 셀 subList 의 textWidth (셀 여백 반영 사용가능폭) 추출용. */
    private static final Pattern CELL_TEXTWIDTH_RE =
            Pattern.compile("textWidth=\"(\\d+)\"");

    /**
     * @param maxCellW 표 셀 내부 이미지면 셀 사용가능폭(HWPUNIT), 표 밖 본문 이미지면 -1.
     *   셀 이미지는 이 폭을 넘지 않도록 비율 유지 축소해 셀 경계 밖 삐짐을 막는다.
     */
    private String buildPicXml(ImgEntry img, int maxCellW) {
        int picW = img.pixelW * 60;
        int picH = img.pixelH * 60;
        int maxW = 41954 * 9 / 10;
        // [표 셀 이미지 폭 clamp] 셀 사용가능폭이 페이지폭보다 좁으면 그 폭까지만.
        if (maxCellW > 0 && maxCellW < maxW) maxW = maxCellW;
        if (picW > maxW) {
            double scale = (double) maxW / picW;
            picW = maxW;
            picH = (int) (picH * scale);
        }
        if (picW < 100) picW = 100;
        if (picH < 100) picH = 100;
        int instId = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        StringBuilder sb = new StringBuilder();
        sb.append("<hp:pic id=\"").append(instId)
          .append("\" zOrder=\"0\" numberingType=\"PICTURE\" textWrap=\"TOP_AND_BOTTOM\" textFlow=\"BOTH_SIDES\""
                + " lock=\"0\" dropcapstyle=\"None\" href=\"\" groupLevel=\"0\" instid=\"").append(instId)
          .append("\" reverse=\"0\">");
        sb.append("<hp:offset x=\"0\" y=\"0\"/>");
        sb.append("<hp:orgSz width=\"").append(picW).append("\" height=\"").append(picH).append("\"/>");
        sb.append("<hp:curSz width=\"").append(picW).append("\" height=\"").append(picH).append("\"/>");
        sb.append("<hp:flip horizontal=\"0\" vertical=\"0\"/>");
        sb.append("<hp:rotationInfo angle=\"0\" centerX=\"").append(picW/2)
          .append("\" centerY=\"").append(picH/2).append("\" rotateimage=\"1\"/>");
        sb.append("<hp:renderingInfo>");
        sb.append("<hc:transMatrix e1=\"1\" e2=\"0\" e3=\"0\" e4=\"0\" e5=\"1\" e6=\"0\"/>");
        sb.append("<hc:scaMatrix e1=\"1\" e2=\"0\" e3=\"0\" e4=\"0\" e5=\"1\" e6=\"0\"/>");
        sb.append("<hc:rotMatrix e1=\"1\" e2=\"0\" e3=\"0\" e4=\"0\" e5=\"1\" e6=\"0\"/>");
        sb.append("</hp:renderingInfo>");
        sb.append("<hp:imgRect>");
        sb.append("<hc:pt0 x=\"0\" y=\"0\"/>");
        sb.append("<hc:pt1 x=\"").append(picW).append("\" y=\"0\"/>");
        sb.append("<hc:pt2 x=\"").append(picW).append("\" y=\"").append(picH).append("\"/>");
        sb.append("<hc:pt3 x=\"0\" y=\"").append(picH).append("\"/>");
        sb.append("</hp:imgRect>");
        sb.append("<hp:imgClip left=\"0\" right=\"0\" top=\"0\" bottom=\"0\"/>");
        sb.append("<hp:inMargin left=\"0\" right=\"0\" top=\"0\" bottom=\"0\"/>");
        sb.append("<hp:imgDim dimwidth=\"").append(picW).append("\" dimheight=\"").append(picH).append("\"/>");
        sb.append("<hc:img binaryItemIDRef=\"").append(img.binId)
          .append("\" bright=\"0\" contrast=\"0\" effect=\"REAL_PIC\" alpha=\"0\"/>");
        sb.append("<hp:effects/>");
        sb.append("<hp:sz width=\"").append(picW).append("\" widthRelTo=\"ABSOLUTE\" height=\"")
          .append(picH).append("\" heightRelTo=\"ABSOLUTE\" protect=\"0\"/>");
        sb.append("<hp:pos treatAsChar=\"1\" affectLSpacing=\"0\" flowWithText=\"1\" allowOverlap=\"0\""
                + " holdAnchorAndSO=\"0\" vertRelTo=\"PARA\" horzRelTo=\"COLUMN\" vertAlign=\"TOP\""
                + " horzAlign=\"LEFT\" vertOffset=\"0\" horzOffset=\"0\"/>");
        sb.append("<hp:outMargin left=\"0\" right=\"0\" top=\"0\" bottom=\"0\"/>");
        sb.append("</hp:pic>");
        return sb.toString();
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // =========================================================
    //  [v15.29 task1/2] Section paragraph PageNumberPosition 보강 (손상 경고 회피)
    // =========================================================

    /** HWP 파일을 hwplib 으로 열어 section paragraph (para[0]) 에 ControlPageNumberPosition
     *  이 없으면 추가하고 다시 저장. 한/글 sanity check 통과용. */
    private static void ensurePageNumberPositionInHwp(Path hwpPath) {
        try {
            kr.dogfoot.hwplib.object.HWPFile hwp =
                    kr.dogfoot.hwplib.reader.HWPReader.fromFile(hwpPath.toString());
            boolean modified = false;
            for (kr.dogfoot.hwplib.object.bodytext.Section sec
                    : hwp.getBodyText().getSectionList()) {
                kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph[] paras = sec.getParagraphs();
                if (paras == null || paras.length == 0) continue;
                kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph p0 = paras[0];
                if (p0.getControlList() == null) continue;
                boolean hasSecDef = false, hasPnp = false;
                for (kr.dogfoot.hwplib.object.bodytext.control.Control c : p0.getControlList()) {
                    if (c instanceof kr.dogfoot.hwplib.object.bodytext.control
                            .ControlSectionDefine) hasSecDef = true;
                    if (c instanceof kr.dogfoot.hwplib.object.bodytext.control
                            .ControlPageNumberPosition) hasPnp = true;
                }
                if (hasSecDef && !hasPnp) {
                    kr.dogfoot.hwplib.object.bodytext.control.ControlPageNumberPosition pnp =
                            new kr.dogfoot.hwplib.object.bodytext.control.ControlPageNumberPosition();
                    pnp.getHeader().getProperty().setNumberShape(
                            kr.dogfoot.hwplib.object.bodytext.control.sectiondefine.NumberShape.Number);
                    pnp.getHeader().getProperty().setNumberPosition(
                            kr.dogfoot.hwplib.object.bodytext.control.ctrlheader
                                    .pagenumberposition.NumberPosition.None);
                    pnp.getHeader().setNumber(0);
                    p0.getControlList().add(pnp);
                    if (p0.getHeader().getInstanceID() == 0L) {
                        p0.getHeader().setInstanceID(0xa4ce6df0L);
                    }
                    modified = true;
                }
            }
            if (modified) {
                kr.dogfoot.hwplib.writer.HWPWriter.toFile(hwp, hwpPath.toString());
            }
        } catch (Exception e) {
            System.out.println("[MdImageInjector v15.29] HWP PageNumberPosition 보강 실패: "
                    + e.getClass().getSimpleName() + " — " + e.getMessage());
        }
    }

    /**
     * [v15.33] HWP OLE compound file 에서 지정한 root-level 스트림의 바이트를 읽는다.
     * 스트림이 없거나 읽기 실패 시 null 반환 (예외는 삼킴).
     */
    private static byte[] readOleStreamSafe(java.nio.file.Path hwp, String streamName) {
        try (org.apache.poi.poifs.filesystem.POIFSFileSystem poi =
                     new org.apache.poi.poifs.filesystem.POIFSFileSystem(hwp.toFile())) {
            org.apache.poi.poifs.filesystem.DirectoryNode root = poi.getRoot();
            if (!root.hasEntry(streamName)) return null;
            org.apache.poi.poifs.filesystem.Entry e = root.getEntry(streamName);
            if (!(e instanceof org.apache.poi.poifs.filesystem.DocumentNode)) return null;
            org.apache.poi.poifs.filesystem.DocumentNode dn =
                    (org.apache.poi.poifs.filesystem.DocumentNode) e;
            byte[] buf = new byte[(int) dn.getSize()];
            try (org.apache.poi.poifs.filesystem.DocumentInputStream in =
                         new org.apache.poi.poifs.filesystem.DocumentInputStream(dn)) {
                in.readFully(buf);
            }
            return buf;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * [v15.33] HWP OLE compound file 의 root-level 스트림을 지정 바이트로 덮어쓴다.
     *  기존 스트림이 있으면 삭제 후 재생성. 실패 시 0 반환 (예외 삼킴 — 본 단계는
     *  best-effort 손상 경고 회피용이며 실패해도 본문은 영향 없음).
     */
    private static int writeOleStream(java.nio.file.Path hwp, String streamName, byte[] data) {
        // Windows + POI memory-mapped file 충돌 회피: 입력 스트림으로 읽고, 메모리에서
        // mutate 후 새 파일로 write 한 다음 원자적 move 로 교체.
        java.nio.file.Path tmp = null;
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(hwp);
             org.apache.poi.poifs.filesystem.POIFSFileSystem poi =
                     new org.apache.poi.poifs.filesystem.POIFSFileSystem(in)) {
            org.apache.poi.poifs.filesystem.DirectoryNode root = poi.getRoot();
            if (root.hasEntry(streamName)) {
                root.getEntry(streamName).delete();
            }
            root.createDocument(streamName, new java.io.ByteArrayInputStream(data));
            // [v16t22] 출력경로가 단일 파일명(부모디렉터리 없음)이면 hwp.getParent()==null →
            //  createTempFile NPE → PrvImage 재주입 실패 → 0바이트 → 한컴 손상경고 팝업.
            //  toAbsolutePath()로 항상 부모(작업디렉터리)를 확보해 동일 FS 내 원자이동 유지.
            java.nio.file.Path parent = hwp.toAbsolutePath().getParent();
            tmp = java.nio.file.Files.createTempFile(parent, "prvimg_", ".hwp");
            try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(tmp,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE)) {
                poi.writeFilesystem(out);
            }
        } catch (Exception e) {
            if (tmp != null) try { java.nio.file.Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            System.out.println("[MdImageInjector v15.33] " + streamName + " 재주입 실패: "
                    + e.getClass().getSimpleName() + " — " + e.getMessage());
            return 0;
        }
        try {
            java.nio.file.Files.move(tmp, hwp,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return data.length;
        } catch (java.nio.file.AtomicMoveNotSupportedException amns) {
            try {
                java.nio.file.Files.move(tmp, hwp,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return data.length;
            } catch (Exception e2) {
                try { java.nio.file.Files.deleteIfExists(tmp); } catch (Exception ignored) {}
                System.out.println("[MdImageInjector v15.33] " + streamName + " move 실패: "
                        + e2.getClass().getSimpleName() + " — " + e2.getMessage());
                return 0;
            }
        } catch (Exception e) {
            try { java.nio.file.Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            System.out.println("[MdImageInjector v15.33] " + streamName + " move 실패: "
                    + e.getClass().getSimpleName() + " — " + e.getMessage());
            return 0;
        }
    }

}
