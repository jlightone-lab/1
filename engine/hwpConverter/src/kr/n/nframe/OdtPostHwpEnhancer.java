package kr.n.nframe;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

/**
 * v15.1 — Post-Write Enhancer.
 *
 * <p>ODT → HWP/HWPX 변환의 끝단에서 동작하는 사후 보정기. 기존 변환 파이프라인
 * (MdToHwpRich + MdImageInjector) 은 일절 수정하지 않고, 출력된 HWP/HWPX 파일을
 * 다시 열어 시각 보정만 추가한다. 변경 범위가 좁아 회귀 위험이 0 에 가깝다.
 *
 * <h3>현재 구현 task</h3>
 * <ul>
 *   <li><b>task 1</b> — 어두운 배경의 표 셀 안 글자 색을 흰색으로
 *       (배경의 휘도 luminance &lt; 128 인 borderFill 을 가진 cell 의 run 들)</li>
 * </ul>
 *
 * <h3>설계 원칙</h3>
 * <ul>
 *   <li>ZIP/XML 단위로만 수정 — hwpxlib API 가 추상화하지 못하는 미세한 표현
 *       (예: 새 charPr 추가 시 itemCnt 증가) 을 직접 다룬다.</li>
 *   <li>입력 파일은 in-place 갱신. 변환 실패 시 호출 측에서 fallback 해도 무방.</li>
 *   <li>HWP(바이너리) 는 본 클래스의 공개 API 에서 진입점만 두고, 내부적으로는
 *       HwpConverter 가 이미 갖고 있는 hwpx ↔ hwp 라운드트립을 활용해 처리한다
 *       (자체 binary writer 신규 작성 회피 → 단순화).</li>
 * </ul>
 */
public class OdtPostHwpEnhancer {

    /** 휘도 임계값 (Rec. 709). 0.299·R + 0.587·G + 0.114·B 가 이하 → 어두움 */
    private static final double LUMINANCE_DARK_THRESHOLD = 128.0;

    // =========================================================
    //  공개 API
    // =========================================================

    /**
     * HWPX 파일을 in-place 보정한다 (header/footer 정보 없이 호출 가능 — 하위호환).
     *
     * <p>현재 구현된 task: 어두운 배경 셀의 글자 색을 흰색으로.
     */
    public void enhanceHwpx(String hwpxPath) throws IOException {
        enhanceHwpx(hwpxPath, null, null);
    }

    /**
     * HWPX 파일을 in-place 보정한다.
     *
     * <p>구현 task:
     * <ul>
     *   <li>task 1 — 어두운 배경 셀의 글자 색을 흰색으로</li>
     *   <li>task 2/3 — header/footer 텍스트가 주어지면 한/글의 머리말/꼬리말 영역에
     *       native &lt;hp:ctrl&gt;&lt;hp:header/footer&gt; 로 인젝션 + 본문에 출력된
     *       fallback 1×1 표 제거</li>
     * </ul>
     */
    public void enhanceHwpx(String hwpxPath, String headerText, String footerText) throws IOException {
        Path p = Paths.get(hwpxPath);
        if (!Files.exists(p)) throw new IOException("HWPX 파일이 없음: " + hwpxPath);

        Map<String, byte[]> entries = readZip(p);
        byte[] headerBytes = entries.get("Contents/header.xml");
        byte[] sectionBytes = entries.get("Contents/section0.xml");
        if (headerBytes == null || sectionBytes == null) {
            System.out.println("[OdtPostHwpEnhancer] header.xml 또는 section0.xml 부재 - 보정 skip");
            return;
        }
        String headerXml = new String(headerBytes, StandardCharsets.UTF_8);
        String sectionXml = new String(sectionBytes, StandardCharsets.UTF_8);

        // ----- task 1: 어두운 셀 글자 흰색 -----
        Set<String> darkFillIds = findDarkBorderFillIds(headerXml, entries);
        String newHeader = headerXml;
        if (!darkFillIds.isEmpty()) {
            System.out.println("[OdtPostHwpEnhancer] 어두운 borderFill " + darkFillIds.size()
                    + "개 검출: " + darkFillIds);
            EnhancedHeader eh = addWhiteTextCharPr(newHeader);
            if (eh != null) {
                newHeader = eh.xml;
                String whiteCharPrId = eh.newCharPrId;
                System.out.println("[OdtPostHwpEnhancer] 흰색 charPr 추가됨 id=" + whiteCharPrId
                        + " (기존 " + eh.originalCharPrCount + "개 → " + (eh.originalCharPrCount + 1) + "개)");
                RewriteResult rr = rewriteDarkCellRuns(sectionXml, darkFillIds, whiteCharPrId);
                sectionXml = rr.xml;
                System.out.println("[OdtPostHwpEnhancer] 흰색 글자 적용 cell="
                        + rr.cellsTouched + ", run=" + rr.runsRewritten);
            } else {
                System.out.println("[OdtPostHwpEnhancer] charPr 복제 실패 - task1 skip");
            }
        } else {
            System.out.println("[OdtPostHwpEnhancer] 어두운 셀 배경 미검출 - task1 skip");
        }

        // ----- task 1 (inline color/highlight): sentinel sweep + run split -----
        InlineSweepResult isr = applyInlineSentinels(sectionXml, newHeader);
        if (isr != null) {
            sectionXml = isr.section;
            newHeader = isr.header;
            System.out.println("[OdtPostHwpEnhancer] inline sentinel "
                    + isr.totalReplacements + "건 처리, 신규 charPr "
                    + isr.newCharPrCount + "개 추가");
        }

        // ----- task 3/4 (v15.36): H1/H2 단락 아래쪽 테두리 — ODT fo:border-bottom 모방.
        //   center-align 인 paraPr 을 클론해 bottom-only borderFill 을 부착하고,
        //   section0.xml 의 hp:tbl 밖에 있는 해당 paraPrIDRef 단락들을 클론으로 redirect.
        HeadingBorderResult hbr = applyHeadingBottomBorder(sectionXml, newHeader);
        if (hbr != null) {
            sectionXml = hbr.section;
            newHeader  = hbr.header;
            System.out.println("[OdtPostHwpEnhancer] H1/H2 단락 테두리(직선 0.4mm 검정) 적용 — "
                    + "paraPr 클론=" + hbr.clonedParaPrs + ", redirect=" + hbr.redirected);
        }

        // ----- task 2/3: native header/footer 인젝션 + fallback 표 제거 -----
        boolean hf = (headerText != null && !headerText.isEmpty()) ||
                     (footerText != null && !footerText.isEmpty());
        if (hf) {
            String injected = injectNativeHeaderFooter(sectionXml, headerText, footerText);
            if (!injected.equals(sectionXml)) {
                sectionXml = injected;
                System.out.println("[OdtPostHwpEnhancer] native header/footer 인젝션 완료"
                        + (headerText != null ? " (head=\"" + headerText + "\")" : "")
                        + (footerText != null ? " (foot=\"" + footerText + "\")" : ""));
                // fallback 으로 본문에 출력된 1×1 표 제거 — header 또는 footer 텍스트와 정확히
                // 일치하는 단일 셀 표 (OdtToMdConverter 가 emit) 만 매칭.
                int removed = 0;
                if (headerText != null && !headerText.isEmpty()) {
                    String[] r1 = removeFallbackHFTable(sectionXml, headerText);
                    if (r1 != null) { sectionXml = r1[0]; removed += Integer.parseInt(r1[1]); }
                }
                if (footerText != null && !footerText.isEmpty()) {
                    String[] r2 = removeFallbackHFTable(sectionXml, footerText);
                    if (r2 != null) { sectionXml = r2[0]; removed += Integer.parseInt(r2[1]); }
                }
                if (removed > 0) {
                    System.out.println("[OdtPostHwpEnhancer] fallback 본문 표 " + removed + "개 제거");
                }
            } else {
                System.out.println("[OdtPostHwpEnhancer] header/footer 인젝션 위치 미발견 - task2/3 skip");
            }
        }

        // ----- 변경사항이 있을 경우만 ZIP 재기록 -----
        boolean changed = !newHeader.equals(headerXml) || !sectionXml.equals(new String(sectionBytes, StandardCharsets.UTF_8));
        if (!changed) {
            System.out.println("[OdtPostHwpEnhancer] 변경 사항 없음 - ZIP 재기록 skip");
            return;
        }
        entries.put("Contents/header.xml", newHeader.getBytes(StandardCharsets.UTF_8));
        entries.put("Contents/section0.xml", sectionXml.getBytes(StandardCharsets.UTF_8));
        writeZip(p, entries);
        System.out.println("[OdtPostHwpEnhancer] HWPX 보정 완료: " + hwpxPath);
    }

    /**
     * HWP(바이너리) 파일을 in-place 보정한다.
     *
     * <p>전략: 바이너리 직접 수정 대신 HWP → HWPX → 보정 → HWPX → HWP 라운드트립으로
     * 처리한다. HwpConverter 가 이미 검증된 양방향 변환을 갖고 있으므로 신규 binary
     * writer 가 필요 없고, HWPX 보정 한 곳 만 유지하면 된다.
     *
     * <p>호출 측은 보정 후의 HWP 가 한/글에서 손상 팝업 없이 열려야 함. 회귀 위험을
     * 최소화하기 위해 ODT 경로에서만 호출한다 (case1/입찰공고문 등 영향 0).
     */
    public void enhanceHwp(String hwpPath) throws Exception {
        enhanceHwp(hwpPath, null, null);
    }

    public void enhanceHwp(String hwpPath, String headerText, String footerText) throws Exception {
        Path p = Paths.get(hwpPath);
        if (!Files.exists(p)) throw new IOException("HWP 파일이 없음: " + hwpPath);

        Path tmpDir = Files.createTempDirectory("odt_post_enhance_");
        Path tmpHwpx = tmpDir.resolve("step.hwpx");
        try {
            HwpConverter conv = new HwpConverter();
            // HWP → HWPX
            conv.convertHwpToHwpx(hwpPath, tmpHwpx.toString());
            // HWPX 보정
            enhanceHwpx(tmpHwpx.toString(), headerText, footerText);
            // HWPX → HWP (overwrite)
            conv.convertHwpxToHwp(tmpHwpx.toString(), hwpPath);
            System.out.println("[OdtPostHwpEnhancer] HWP 보정 완료 (HWPX 라운드트립): " + hwpPath);
        } finally {
            try {
                Files.walk(tmpDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(x -> { try { Files.deleteIfExists(x); } catch (Exception ignored) {} });
            } catch (Exception ignored) {}
        }
    }

    // =========================================================
    //  내부 — 어두운 borderFill 검출
    // =========================================================

    private static final Pattern BORDER_FILL_RE = Pattern.compile(
            "<hh:borderFill\\s+id=\"(\\d+)\"[^>]*>(.*?)</hh:borderFill>",
            Pattern.DOTALL);
    private static final Pattern IMG_BRUSH_BIN_RE = Pattern.compile(
            "<hc:img\\s+[^>]*binaryItemIDRef=\"([^\"]+)\"");

    private Set<String> findDarkBorderFillIds(String headerXml, Map<String, byte[]> entries) {
        Set<String> darkIds = new HashSet<>();
        Matcher m = BORDER_FILL_RE.matcher(headerXml);
        while (m.find()) {
            String id = m.group(1);
            String body = m.group(2);
            Matcher mb = IMG_BRUSH_BIN_RE.matcher(body);
            if (!mb.find()) continue; // imgBrush 없는 borderFill 은 검사 대상 아님
            String binRef = mb.group(1);
            byte[] png = entries.get("BinData/" + binRef + ".png");
            if (png == null) {
                // 다른 확장자(jpg/bmp) 가능성 — 첫 매치 사용
                for (Map.Entry<String, byte[]> en : entries.entrySet()) {
                    String key = en.getKey();
                    if (key.startsWith("BinData/" + binRef + ".")) {
                        png = en.getValue();
                        break;
                    }
                }
            }
            if (png == null) continue;
            Double lum = topLeftLuminance(png);
            if (lum != null && lum < LUMINANCE_DARK_THRESHOLD) {
                darkIds.add(id);
            }
        }
        return darkIds;
    }

    /** 1×1 (또는 임의 크기) PNG 의 (0,0) 픽셀 휘도(0~255). 실패 시 null. */
    private Double topLeftLuminance(byte[] data) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img == null || img.getWidth() < 1 || img.getHeight() < 1) return null;
            int rgb = img.getRGB(0, 0);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >>  8) & 0xFF;
            int b = (rgb      ) & 0xFF;
            return 0.299 * r + 0.587 * g + 0.114 * b;
        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================
    //  내부 — header.xml 에 흰색 charPr 추가
    // =========================================================

    private static final Pattern CHAR_PROPS_OPEN_RE = Pattern.compile(
            "<hh:charProperties\\s+itemCnt=\"(\\d+)\">");
    private static final Pattern FIRST_CHARPR_RE = Pattern.compile(
            "(<hh:charPr\\s+id=\"\\d+\"[^>]*>.*?</hh:charPr>)", Pattern.DOTALL);
    private static final Pattern CHAR_PR_TEXTCOLOR_RE = Pattern.compile(
            "(<hh:charPr\\s+id=\")(\\d+)(\")(.*?textColor=\")(#?[0-9A-Fa-f]{6,8})(\".*?>)",
            Pattern.DOTALL);
    private static final Pattern CHAR_PROPS_CLOSE_RE = Pattern.compile(
            "</hh:charProperties>");

    static class EnhancedHeader {
        String xml;
        String newCharPrId;
        int originalCharPrCount;
    }

    private EnhancedHeader addWhiteTextCharPr(String headerXml) {
        Matcher mo = CHAR_PROPS_OPEN_RE.matcher(headerXml);
        if (!mo.find()) return null;
        int itemCnt = Integer.parseInt(mo.group(1));
        // 새 charPr 의 id = 기존 itemCnt (id 는 0-based 일관 가정 — 실제 outputs 에서 확인됨)
        String newId = String.valueOf(itemCnt);

        // 첫 번째 charPr 을 base 로 복제 → textColor 만 #FFFFFF 로 치환
        Matcher mf = FIRST_CHARPR_RE.matcher(headerXml);
        if (!mf.find()) return null;
        String firstCharPrXml = mf.group(1);
        // id 와 textColor 를 변경한 복제본 만들기
        String cloned = firstCharPrXml
                .replaceFirst("id=\"\\d+\"", "id=\"" + newId + "\"")
                .replaceFirst("textColor=\"#?[0-9A-Fa-f]{6,8}\"", "textColor=\"#FFFFFF\"");
        if (cloned.equals(firstCharPrXml)) {
            // textColor 변경이 안 된 경우 — id 변경만으로는 의미가 없으므로 강제로 textColor 를 주입
            cloned = cloned.replaceFirst("(<hh:charPr\\s+id=\"" + newId + "\")",
                    "$1 textColor=\"#FFFFFF\"");
        }

        // itemCnt += 1, </hh:charProperties> 직전에 신규 charPr 삽입
        String updated = mo.replaceFirst("<hh:charProperties itemCnt=\"" + (itemCnt + 1) + "\">");
        Matcher mc = CHAR_PROPS_CLOSE_RE.matcher(updated);
        if (!mc.find()) return null;
        String result = updated.substring(0, mc.start()) + cloned + updated.substring(mc.start());

        EnhancedHeader eh = new EnhancedHeader();
        eh.xml = result;
        eh.newCharPrId = newId;
        eh.originalCharPrCount = itemCnt;
        return eh;
    }

    // =========================================================
    //  내부 — section0.xml 의 어두운 cell run 재타겟
    // =========================================================

    static class RewriteResult {
        String xml;
        int cellsTouched = 0;
        int runsRewritten = 0;
    }

    /**
     * &lt;hp:tc … borderFillIDRef="DARK"…&gt; … &lt;/hp:tc&gt; 블록을 찾아 그 안의
     * &lt;hp:run charPrIDRef="X"&gt; 를 흰색 charPrId 로 교체한다. tc 블록은 중첩되지
     * 않는다고 가정 (HWPX 표는 hp:tbl 단위로 중첩되며 hp:tc 는 항상 leaf).
     */
    private RewriteResult rewriteDarkCellRuns(String xml, Set<String> darkFillIds, String whiteCharPrId) {
        RewriteResult r = new RewriteResult();
        // tc 시작 태그를 찾아 그 안의 borderFillIDRef 를 추출. 어두우면 매칭되는 닫힘
        // 까지의 substring 을 뽑아 run 치환 후 다시 합친다.
        Pattern tcOpen = Pattern.compile(
                "<hp:tc\\b([^>]*?)borderFillIDRef=\"(\\d+)\"([^>]*)>",
                Pattern.DOTALL);
        Pattern runChar = Pattern.compile(
                "<hp:run\\s+charPrIDRef=\"(\\d+)\"");
        StringBuilder out = new StringBuilder(xml.length() + 1024);
        int last = 0;
        Matcher m = tcOpen.matcher(xml);
        while (m.find()) {
            String fillId = m.group(2);
            int openStart = m.start();
            int openEnd = m.end();
            // 매칭 닫힘 </hp:tc> 위치 (중첩 없는 가정)
            int closeIdx = xml.indexOf("</hp:tc>", openEnd);
            if (closeIdx < 0) break;
            int closeEnd = closeIdx + "</hp:tc>".length();
            out.append(xml, last, openEnd);
            String inner = xml.substring(openEnd, closeIdx);
            if (darkFillIds.contains(fillId)) {
                Matcher rm = runChar.matcher(inner);
                StringBuilder ib = new StringBuilder(inner.length());
                int li = 0;
                int cnt = 0;
                while (rm.find()) {
                    ib.append(inner, li, rm.start());
                    ib.append("<hp:run charPrIDRef=\"").append(whiteCharPrId).append("\"");
                    li = rm.end();
                    cnt++;
                }
                ib.append(inner, li, inner.length());
                inner = ib.toString();
                r.cellsTouched++;
                r.runsRewritten += cnt;
            }
            out.append(inner);
            out.append("</hp:tc>");
            last = closeEnd;
        }
        out.append(xml, last, xml.length());
        r.xml = out.toString();
        return r;
    }

    // =========================================================
    //  task 1 — inline color/highlight sentinel sweep
    // =========================================================

    /** sentinel 문자: OdtToMdConverter.SENT_* 와 동일. */
    private static final String SENT_OPEN_PREFIX = "❮F";   // ❮F
    private static final String SENT_OPEN_SUFFIX = "❯";    // ❯
    private static final String SENT_CLOSE       = "❮/F❯"; // ❮/F❯
    /** ❮F<spec>❯CONTENT❮/F❯ . spec=[CH]#RRGGBB. CONTENT 는 sentinel/태그 미포함 가정. */
    private static final Pattern SENT_PAIR_RE = Pattern.compile(
            "❮F([^❯]+)❯([^❮]*)❮/F❯", Pattern.DOTALL);

    static class InlineSweepResult {
        String section;
        String header;
        int totalReplacements = 0;
        int newCharPrCount = 0;
    }

    /**
     * section0.xml 의 모든 hp:t 안에서 sentinel pair 를 찾아 hp:run 을 분할하고
     * 새 charPr 로 redirect 한다. 단순화 — 한 hp:run 안의 한 hp:t 가 sentinel pair
     * 전체를 포함한다고 가정 (현재 변환 파이프라인은 이 조건을 충족시킴).
     */
    private InlineSweepResult applyInlineSentinels(String sectionXml, String headerXml) {
        if (!sectionXml.contains(SENT_OPEN_PREFIX)) return null;
        InlineSweepResult res = new InlineSweepResult();

        // 1) 모든 sentinel spec 수집 → spec → newCharPrId 매핑 (charPr 1개로 통일)
        Map<String, String> specToNewId = new LinkedHashMap<>();
        Matcher mm = SENT_PAIR_RE.matcher(sectionXml);
        while (mm.find()) {
            String spec = mm.group(1);
            specToNewId.putIfAbsent(spec, null);
        }
        if (specToNewId.isEmpty()) return null;

        // 2) header.xml 에 spec 별 신규 charPr 추가
        Matcher cpOpen = CHAR_PROPS_OPEN_RE.matcher(headerXml);
        if (!cpOpen.find()) return null;
        int itemCnt = Integer.parseInt(cpOpen.group(1));
        Matcher firstCp = FIRST_CHARPR_RE.matcher(headerXml);
        if (!firstCp.find()) return null;
        String baseCharPrXml = firstCp.group(1);

        StringBuilder addedCharPrs = new StringBuilder();
        int curId = itemCnt;
        for (Map.Entry<String, String> en : specToNewId.entrySet()) {
            String spec = en.getKey();
            String cloned = cloneCharPrForSpec(baseCharPrXml, curId, spec);
            if (cloned != null) {
                addedCharPrs.append(cloned);
                en.setValue(String.valueOf(curId));
                curId++;
                res.newCharPrCount++;
            }
        }
        if (res.newCharPrCount == 0) return null;

        String updatedHeader = cpOpen.replaceFirst(
                "<hh:charProperties itemCnt=\"" + curId + "\">");
        Matcher cpClose = CHAR_PROPS_CLOSE_RE.matcher(updatedHeader);
        if (!cpClose.find()) return null;
        updatedHeader = updatedHeader.substring(0, cpClose.start())
                + addedCharPrs
                + updatedHeader.substring(cpClose.start());
        res.header = updatedHeader;

        // 3) section0.xml: 각 hp:run 의 hp:t 안의 sentinel pair 를 split + redirect
        StringBuilder out = new StringBuilder(sectionXml.length() + 4096);
        Pattern runPat = Pattern.compile(
                "<hp:run\\s+charPrIDRef=\"(\\d+)\"([^>]*)>(.*?)</hp:run>",
                Pattern.DOTALL);
        Matcher rm = runPat.matcher(sectionXml);
        int last = 0;
        while (rm.find()) {
            String origCharPr = rm.group(1);
            String otherAttrs = rm.group(2);
            String inner = rm.group(3);
            if (!inner.contains(SENT_OPEN_PREFIX)) continue;
            String replaced = splitRunInner(origCharPr, otherAttrs, inner, specToNewId, res);
            if (replaced == null) continue;
            out.append(sectionXml, last, rm.start());
            out.append(replaced);
            last = rm.end();
        }
        out.append(sectionXml, last, sectionXml.length());
        res.section = out.toString();
        return res;
    }

    /**
     * 한 hp:run 의 inner XML 에서 sentinel pair 를 찾아 hp:run 시퀀스로 분할한다.
     * 단순 케이스: inner 가 정확히 &lt;hp:t&gt;...&lt;/hp:t&gt; 한 덩어리.
     */
    private String splitRunInner(String origCharPr, String otherAttrs, String inner,
                                 Map<String, String> specToNewId, InlineSweepResult res) {
        // inner 가 단일 hp:t 형태인지 확인 (단순화)
        Matcher tm = Pattern.compile("^\\s*<hp:t>([^<]*)</hp:t>\\s*$", Pattern.DOTALL).matcher(inner);
        if (!tm.matches()) return null;
        String text = tm.group(1);
        if (!text.contains(SENT_OPEN_PREFIX)) return null;

        StringBuilder runs = new StringBuilder();
        int idx = 0;
        Matcher pm = SENT_PAIR_RE.matcher(text);
        while (pm.find(idx)) {
            String before = text.substring(idx, pm.start());
            String spec = pm.group(1);
            String content = pm.group(2);
            String newId = specToNewId.get(spec);
            if (newId == null) {
                idx = pm.end();
                continue;
            }
            if (!before.isEmpty()) {
                runs.append("<hp:run charPrIDRef=\"").append(origCharPr).append("\"")
                    .append(otherAttrs).append("><hp:t>").append(before).append("</hp:t></hp:run>");
            }
            runs.append("<hp:run charPrIDRef=\"").append(newId).append("\"")
                .append(otherAttrs).append("><hp:t>").append(content).append("</hp:t></hp:run>");
            res.totalReplacements++;
            idx = pm.end();
        }
        String tail = text.substring(idx);
        if (!tail.isEmpty()) {
            runs.append("<hp:run charPrIDRef=\"").append(origCharPr).append("\"")
                .append(otherAttrs).append("><hp:t>").append(tail).append("</hp:t></hp:run>");
        }
        return runs.toString();
    }

    /** baseCharPrXml 을 spec 에 맞게 변형한 hp:charPr XML 생성 */
    private String cloneCharPrForSpec(String baseCharPrXml, int newId, String spec) {
        String cloned = baseCharPrXml.replaceFirst("id=\"\\d+\"", "id=\"" + newId + "\"");
        if (spec.startsWith("C") && spec.length() == 8) {
            // 색상 — textColor 변경
            String hex = spec.substring(1);
            cloned = cloned.replaceFirst("textColor=\"#?[0-9A-Fa-f]{6,8}\"", "textColor=\"" + hex + "\"");
        } else if (spec.startsWith("H") && spec.length() == 8) {
            // 하이라이트 — shadeColor 변경
            String hex = spec.substring(1);
            cloned = cloned.replaceFirst("shadeColor=\"[^\"]*\"", "shadeColor=\"" + hex + "\"");
        } else {
            return null;
        }
        return cloned;
    }

    // =========================================================
    //  task 2/3 — native HWPX header/footer 인젝션
    // =========================================================

    /**
     * 첫 hp:p 의 첫 hp:run 에 있는 hp:secPr 직후에 hp:ctrl &gt; hp:header / hp:footer 를
     * 삽입한다. textHeight=0 (자동), applyPageType=BOTH (양쪽 페이지). 페이지 번호는
     * OdtToMdConverter 가 미리 [현재쪽] / [전체쪽] 텍스트로 치환했으므로 그대로 노출된다.
     *
     * <p>위치를 찾지 못하면 입력 그대로 반환.
     */
    private String injectNativeHeaderFooter(String sectionXml, String headerText, String footerText) {
        int secPrEnd = sectionXml.indexOf("</hp:secPr>");
        if (secPrEnd < 0) return sectionXml;
        int insertAt = secPrEnd + "</hp:secPr>".length();
        StringBuilder ins = new StringBuilder(2048);
        ins.append("<hp:ctrl>");
        if (headerText != null && !headerText.isEmpty()) {
            ins.append(buildHeaderFooterXml("header", headerText));
        }
        if (footerText != null && !footerText.isEmpty()) {
            ins.append(buildHeaderFooterXml("footer", footerText));
        }
        ins.append("</hp:ctrl>");
        return sectionXml.substring(0, insertAt) + ins + sectionXml.substring(insertAt);
    }

    /** hp:header 또는 hp:footer 한 단위 XML 빌드. */
    private String buildHeaderFooterXml(String kind, String text) {
        // textWidth/Height 와 lineseg 값은 한/글이 자체 재계산하므로 큰 영향 없음.
        // 안전 기본값으로 A4 기준 본문 가용 폭 (42520 HWPUNIT) 사용.
        String escaped = xmlEscape(text);
        StringBuilder sb = new StringBuilder(1024);
        sb.append("<hp:").append(kind).append(" id=\"\" applyPageType=\"BOTH\">");
        sb.append("<hp:subList id=\"\" textDirection=\"HORIZONTAL\" lineWrap=\"BREAK\" ")
          .append("vertAlign=\"TOP\" linkListIDRef=\"0\" linkListNextIDRef=\"0\" ")
          .append("textWidth=\"42520\" textHeight=\"0\" hasTextRef=\"0\" hasNumRef=\"0\">");
        // v15.36 (task12/13): footer 의 경우 본문 마지막 줄과 꼬리말 사이에 시각적 여백을
        //   확보하기 위해 실제 텍스트 단락 앞에 빈 단락 1개를 선행 삽입한다.
        //   header 는 기존 동작 유지.
        if ("footer".equalsIgnoreCase(kind)) {
            sb.append("<hp:p id=\"0\" paraPrIDRef=\"0\" styleIDRef=\"0\" pageBreak=\"0\" ")
              .append("columnBreak=\"0\" merged=\"0\">");
            sb.append("<hp:run charPrIDRef=\"0\"></hp:run>");
            sb.append("<hp:linesegarray>");
            sb.append("<hp:lineseg textpos=\"0\" vertpos=\"0\" vertsize=\"1000\" textheight=\"1000\" ")
              .append("baseline=\"850\" spacing=\"600\" horzpos=\"0\" horzsize=\"42520\" flags=\"393216\"/>");
            sb.append("</hp:linesegarray>");
            sb.append("</hp:p>");
        }
        sb.append("<hp:p id=\"0\" paraPrIDRef=\"0\" styleIDRef=\"0\" pageBreak=\"0\" ")
          .append("columnBreak=\"0\" merged=\"0\">");
        sb.append("<hp:run charPrIDRef=\"0\">");
        sb.append("<hp:t>").append(escaped).append("</hp:t>");
        sb.append("</hp:run>");
        sb.append("<hp:linesegarray>");
        sb.append("<hp:lineseg textpos=\"0\" vertpos=\"0\" vertsize=\"1000\" textheight=\"1000\" ")
          .append("baseline=\"850\" spacing=\"600\" horzpos=\"0\" horzsize=\"42520\" flags=\"393216\"/>");
        sb.append("</hp:linesegarray>");
        sb.append("</hp:p>");
        sb.append("</hp:subList>");
        sb.append("</hp:").append(kind).append(">");
        return sb.toString();
    }

    /**
     * 본문에 fallback 으로 출력된 1×1 표 (OdtToMdConverter 가 머리글/꼬리글 가시화 용도로
     * emit) 를 제거. 셀 텍스트가 주어진 text 와 정확히 일치하는 hp:tbl 블록만 삭제한다.
     *
     * @return [newXml, removedCount] 또는 null (변경 없음)
     */
    private String[] removeFallbackHFTable(String sectionXml, String text) {
        if (text == null || text.isEmpty()) return null;
        String escaped = xmlEscape(text);
        // hp:tc 의 hp:t 안에 정확히 escaped 텍스트가 들어있는 1×1 표를 검색.
        // 단순화 — 표 시작/종료 매칭은 가장 가까운 <hp:tbl ...> ... </hp:tbl> 쌍 사용.
        int searchFrom = 0;
        StringBuilder out = new StringBuilder(sectionXml.length());
        int last = 0;
        int removed = 0;
        while (true) {
            int textIdx = sectionXml.indexOf(">" + escaped + "<", searchFrom);
            if (textIdx < 0) break;
            // 이 텍스트 위치를 포함하는 가장 가까운 <hp:tbl  ... > 와 그에 매칭되는 </hp:tbl>
            int tblStart = sectionXml.lastIndexOf("<hp:tbl ", textIdx);
            if (tblStart < 0) {
                searchFrom = textIdx + escaped.length();
                continue;
            }
            int tblEnd = sectionXml.indexOf("</hp:tbl>", textIdx);
            if (tblEnd < 0) break;
            int tblEndAfter = tblEnd + "</hp:tbl>".length();
            // 단일-셀 표 검증 — 이 hp:tbl 안에 hp:tc 가 1개만 있어야 하고 nested table 없음
            String tblBody = sectionXml.substring(tblStart, tblEndAfter);
            int tcCount = countOcc(tblBody, "<hp:tc ");
            int nestedTbl = countOcc(tblBody, "<hp:tbl ") - 1; // 자기 자신 제외
            if (tcCount == 1 && nestedTbl <= 0) {
                // 표를 감싸는 hp:p 가 있으면 그것까지 같이 제거 — 단순화 위해 표만 제거
                out.append(sectionXml, last, tblStart);
                last = tblEndAfter;
                removed++;
                searchFrom = tblEndAfter;
            } else {
                searchFrom = tblEndAfter;
            }
        }
        if (removed == 0) return null;
        out.append(sectionXml, last, sectionXml.length());
        return new String[]{ out.toString(), String.valueOf(removed) };
    }

    private static int countOcc(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }

    // =========================================================
    //  task 3/4 (v15.36) — H1/H2 단락 아래쪽 테두리 인젝션
    // =========================================================

    static class HeadingBorderResult {
        String header;
        String section;
        int clonedParaPrs = 0;
        int redirected = 0;
    }

    private static final Pattern PARA_PR_RE = Pattern.compile(
            "<hh:paraPr\\s+id=\"(\\d+)\"[^>]*>(.*?)</hh:paraPr>", Pattern.DOTALL);
    private static final Pattern PARA_PROPS_OPEN_RE = Pattern.compile(
            "<hh:paraProperties\\s+itemCnt=\"(\\d+)\">");
    private static final Pattern PARA_PROPS_CLOSE_RE = Pattern.compile(
            "</hh:paraProperties>");
    private static final Pattern BORDER_FILLS_OPEN_RE = Pattern.compile(
            "<hh:borderFills\\s+itemCnt=\"(\\d+)\">");
    private static final Pattern BORDER_FILLS_CLOSE_RE = Pattern.compile(
            "</hh:borderFills>");
    private static final Pattern HP_TBL_BLOCK_RE = Pattern.compile(
            "<hp:tbl\\b.*?</hp:tbl>", Pattern.DOTALL);
    private static final Pattern HP_P_OPEN_RE = Pattern.compile(
            "<hp:p\\s+id=\"[^\"]*\"\\s+paraPrIDRef=\"(\\d+)\"[^>]*>");

    /**
     * H1/H2 단락에 아래쪽 단락 테두리 (직선 0.4mm 검정) 를 부착.
     *
     * <p>탐지 기준 — paraPr alignment 가 아니라 <b>charPr.height</b>:
     * 본문 글꼴(10pt = 1000 HWPUNIT) 보다 충분히 큰 (≥ 1300 = 13pt) charPr 을 사용하는
     * 단락을 H1/H2 로 간주. (v15.36 — 표지/캡션 오탐지 fix)
     *
     * <ol>
     *   <li>header.xml 에 bottom-only borderFill 추가</li>
     *   <li>section0.xml 에서 hp:tbl 밖 + run charPrIDRef ∈ {heading charPrs} 인 hp:p 들을 식별</li>
     *   <li>이 hp:p 들의 paraPrIDRef 값들을 모아 각각 클론 (bottom-border borderFill 부착)</li>
     *   <li>해당 hp:p 들만 클론 ID 로 redirect (paraPr 자체를 sweep 하지 않고 단락 단위로 정확히)</li>
     * </ol>
     */
    private HeadingBorderResult applyHeadingBottomBorder(String sectionXml, String headerXml) {
        // 1) header.xml 의 charPr 들을 스캔 — 본문보다 큰 글자 크기를 가진 것을 heading charPr 로 분류
        Set<String> headingCharPrs = collectHeadingCharPrs(headerXml);
        if (headingCharPrs.isEmpty()) return null;

        // 2) section0.xml 에서 hp:tbl 밖 + run charPrIDRef ∈ headingCharPrs 인 hp:p 위치 식별
        //    그 hp:p 들이 쓰는 paraPrIDRef 모음
        Map<String, String> paraPrUsedByHeadings = collectHeadingParaPrRefs(sectionXml, headingCharPrs);
        if (paraPrUsedByHeadings.isEmpty()) return null;

        // 3) header.xml 에 bottom-only borderFill 추가
        //    v15.36 fix: 신규 ID 는 itemCnt 가 아닌 max(existing borderFill id) + 1 로 산정 —
        //    실제 HWPX 의 borderFill id 는 sparse 하며 itemCnt < max(id) 일 때 ID 충돌 발생.
        Matcher bfOpen = BORDER_FILLS_OPEN_RE.matcher(headerXml);
        if (!bfOpen.find()) return null;
        int bfCnt = Integer.parseInt(bfOpen.group(1));
        int maxBfId = -1;
        Matcher bfIdScan = Pattern.compile("<hh:borderFill\\s+id=\"(\\d+)\"").matcher(headerXml);
        while (bfIdScan.find()) {
            int n = Integer.parseInt(bfIdScan.group(1));
            if (n > maxBfId) maxBfId = n;
        }
        String newBorderFillId = String.valueOf(Math.max(bfCnt, maxBfId + 1));
        String newBorderFillXml =
                "<hh:borderFill id=\"" + newBorderFillId + "\" threeD=\"0\" shadow=\"0\""
                        + " centerLine=\"NONE\" breakCellSeparateLine=\"0\">"
                        + "<hh:slash type=\"NONE\" Crooked=\"0\" isCounter=\"0\"/>"
                        + "<hh:backSlash type=\"NONE\" Crooked=\"0\" isCounter=\"0\"/>"
                        + "<hh:leftBorder type=\"NONE\" width=\"0.4 mm\" color=\"#000000\"/>"
                        + "<hh:rightBorder type=\"NONE\" width=\"0.4 mm\" color=\"#000000\"/>"
                        + "<hh:topBorder type=\"NONE\" width=\"0.4 mm\" color=\"#000000\"/>"
                        + "<hh:bottomBorder type=\"SOLID\" width=\"0.4 mm\" color=\"#000000\"/>"
                        + "<hh:diagonal type=\"NONE\" width=\"0.4 mm\" color=\"#000000\"/>"
                        + "</hh:borderFill>";
        String newHeader = bfOpen.replaceFirst(
                "<hh:borderFills itemCnt=\"" + (bfCnt + 1) + "\">");
        Matcher bfClose = BORDER_FILLS_CLOSE_RE.matcher(newHeader);
        if (!bfClose.find()) return null;
        newHeader = newHeader.substring(0, bfClose.start())
                + newBorderFillXml
                + newHeader.substring(bfClose.start());

        // 4) heading 단락들이 쓰는 paraPr 들을 클론 — borderFillIDRef 만 새 ID 로 교체
        //    v15.36 fix: 신규 paraPr ID 는 max(existing paraPr id) + 1 로 산정 (충돌 회피).
        Matcher ppOpen = PARA_PROPS_OPEN_RE.matcher(newHeader);
        if (!ppOpen.find()) return null;
        int ppCnt = Integer.parseInt(ppOpen.group(1));
        int maxPpId = -1;
        Matcher ppIdScan = Pattern.compile("<hh:paraPr\\s+id=\"(\\d+)\"").matcher(newHeader);
        while (ppIdScan.find()) {
            int n = Integer.parseInt(ppIdScan.group(1));
            if (n > maxPpId) maxPpId = n;
        }
        Map<String, String> origToCloneId = new LinkedHashMap<>();
        StringBuilder clonedXml = new StringBuilder();
        int curId = Math.max(ppCnt, maxPpId + 1);
        for (String origId : paraPrUsedByHeadings.keySet()) {
            // 원본 paraPr 본문 추출
            Pattern oneParaPr = Pattern.compile(
                    "<hh:paraPr\\s+id=\"" + origId + "\"[^>]*>.*?</hh:paraPr>", Pattern.DOTALL);
            Matcher one = oneParaPr.matcher(newHeader);
            if (!one.find()) continue;
            String orig = one.group(0);
            String cloneId = String.valueOf(curId);
            // id 교체
            String cloned = orig.replaceFirst("id=\"" + origId + "\"", "id=\"" + cloneId + "\"");
            // borderFillIDRef 교체
            if (cloned.contains("<hh:border ")) {
                cloned = cloned.replaceFirst(
                        "<hh:border\\s+borderFillIDRef=\"\\d+\"",
                        "<hh:border borderFillIDRef=\"" + newBorderFillId + "\"");
            } else {
                // border 요소가 없으면 paraPr 종료 직전에 삽입
                cloned = cloned.replaceFirst("</hh:paraPr>",
                        "<hh:border borderFillIDRef=\"" + newBorderFillId
                                + "\" offsetLeft=\"0\" offsetRight=\"0\" offsetTop=\"0\" offsetBottom=\"0\""
                                + " connect=\"0\" ignoreMargin=\"0\"/></hh:paraPr>");
            }
            clonedXml.append(cloned);
            origToCloneId.put(origId, cloneId);
            curId++;
        }
        if (origToCloneId.isEmpty()) return null;
        // itemCnt 업데이트 + 클론들 삽입 — itemCnt 는 (원본 ppCnt + 클론 개수).
        newHeader = newHeader.replaceFirst(
                "<hh:paraProperties\\s+itemCnt=\"" + ppCnt + "\">",
                "<hh:paraProperties itemCnt=\"" + (ppCnt + origToCloneId.size()) + "\">");
        Matcher ppClose = PARA_PROPS_CLOSE_RE.matcher(newHeader);
        if (!ppClose.find()) return null;
        newHeader = newHeader.substring(0, ppClose.start())
                + clonedXml
                + newHeader.substring(ppClose.start());

        // 5) section0.xml redirect — hp:tbl 밖 + heading charPr 사용 단락의 paraPrIDRef 만 교체
        //    (paraPr 통째 sweep 이 아니라 단락 단위 정확 매칭)
        String newSection = redirectHeadingParagraphs(sectionXml, headingCharPrs, origToCloneId);

        HeadingBorderResult r = new HeadingBorderResult();
        r.header = newHeader;
        r.section = newSection;
        r.clonedParaPrs = origToCloneId.size();
        r.redirected = countRedirects(sectionXml, newSection);
        return r;
    }

    /** header.xml 의 charPr 들 중 본문 글꼴보다 충분히 큰 글자 크기를 갖는 id 모음 (H1/H2 후보). */
    private Set<String> collectHeadingCharPrs(String headerXml) {
        // 본문 charPrIDRef="0" 의 height 를 기준점으로 잡고 +300 (3pt) 이상이면 heading 으로 본다.
        int bodyHeight = 1000; // default fallback (10pt)
        Pattern cp0 = Pattern.compile("<hh:charPr\\s+id=\"0\"[^>]*?height=\"(\\d+)\"");
        Matcher m0 = cp0.matcher(headerXml);
        if (m0.find()) bodyHeight = Integer.parseInt(m0.group(1));
        int threshold = bodyHeight + 300;

        Set<String> ids = new LinkedHashSet<>();
        Pattern p = Pattern.compile("<hh:charPr\\s+id=\"(\\d+)\"[^>]*?height=\"(\\d+)\"");
        Matcher m = p.matcher(headerXml);
        while (m.find()) {
            int h = Integer.parseInt(m.group(2));
            if (h >= threshold) ids.add(m.group(1));
        }
        return ids;
    }

    /**
     * section0.xml 에서 hp:tbl 밖 + 첫 hp:run 의 charPrIDRef ∈ headingCharPrs 인 hp:p 들을 찾아,
     * 그 hp:p 들이 사용하는 paraPrIDRef 집합 반환 (LinkedHashMap 으로 삽입 순서 보존, value 미사용).
     */
    private Map<String, String> collectHeadingParaPrRefs(String sectionXml, Set<String> headingCharPrs) {
        Map<String, String> ids = new LinkedHashMap<>();
        // hp:tbl 마스킹
        Matcher tbl = HP_TBL_BLOCK_RE.matcher(sectionXml);
        StringBuilder masked = new StringBuilder(sectionXml.length());
        int last = 0;
        while (tbl.find()) {
            masked.append(sectionXml, last, tbl.start());
            for (int i = tbl.start(); i < tbl.end(); i++) masked.append(' ');
            last = tbl.end();
        }
        masked.append(sectionXml, last, sectionXml.length());
        String outside = masked.toString();
        // hp:p 시작 ~ 첫 hp:run charPrIDRef 패턴 매칭
        Pattern pat = Pattern.compile(
                "<hp:p\\s+id=\"[^\"]*\"\\s+paraPrIDRef=\"(\\d+)\"[^>]*>\\s*<hp:run\\s+charPrIDRef=\"(\\d+)\"");
        Matcher m = pat.matcher(outside);
        while (m.find()) {
            String paraPrId = m.group(1);
            String charPrId = m.group(2);
            if (headingCharPrs.contains(charPrId)) {
                ids.putIfAbsent(paraPrId, "");
            }
        }
        return ids;
    }

    /**
     * section0.xml 의 heading 단락 (hp:tbl 밖 + 첫 run charPrIDRef ∈ headingCharPrs) 만
     * paraPrIDRef 를 redirect 맵에 따라 교체. paraPr 전체 sweep 이 아니라 단락 단위.
     */
    private String redirectHeadingParagraphs(String sectionXml, Set<String> headingCharPrs,
                                             Map<String, String> origToCloneId) {
        StringBuilder out = new StringBuilder(sectionXml.length() + 256);
        Matcher tbl = HP_TBL_BLOCK_RE.matcher(sectionXml);
        int last = 0;
        while (tbl.find()) {
            out.append(rewriteHeadingParaInFragment(
                    sectionXml.substring(last, tbl.start()), headingCharPrs, origToCloneId));
            out.append(sectionXml, tbl.start(), tbl.end());
            last = tbl.end();
        }
        out.append(rewriteHeadingParaInFragment(
                sectionXml.substring(last), headingCharPrs, origToCloneId));
        return out.toString();
    }

    private String rewriteHeadingParaInFragment(String fragment, Set<String> headingCharPrs,
                                                Map<String, String> origToCloneId) {
        Pattern pat = Pattern.compile(
                "(<hp:p\\s+id=\"[^\"]*\"\\s+)paraPrIDRef=\"(\\d+)\"([^>]*>\\s*<hp:run\\s+charPrIDRef=\")(\\d+)(\")");
        Matcher m = pat.matcher(fragment);
        StringBuffer sb = new StringBuffer(fragment.length());
        while (m.find()) {
            String paraPrId = m.group(2);
            String charPrId = m.group(4);
            String clone = origToCloneId.get(paraPrId);
            if (clone != null && headingCharPrs.contains(charPrId)) {
                String repl = m.group(1) + "paraPrIDRef=\"" + clone + "\"" + m.group(3) + charPrId + m.group(5);
                m.appendReplacement(sb, Matcher.quoteReplacement(repl));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 변경 전후 paraPrIDRef diff 카운트 — 디버그용. */
    private int countRedirects(String before, String after) {
        int diff = 0;
        Matcher b = HP_P_OPEN_RE.matcher(before);
        Matcher a = HP_P_OPEN_RE.matcher(after);
        while (b.find() && a.find()) {
            if (!b.group(1).equals(a.group(1))) diff++;
        }
        return diff;
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    // =========================================================
    //  ZIP I/O
    // =========================================================

    private static Map<String, byte[]> readZip(Path p) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipFile z = new ZipFile(p.toFile())) {
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            byte[] buf = new byte[16 * 1024];
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                try (InputStream is = z.getInputStream(e); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                    int n;
                    while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                    out.put(e.getName(), bos.toByteArray());
                }
            }
        }
        return out;
    }

    private static void writeZip(Path p, Map<String, byte[]> entries) throws IOException {
        Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmp))) {
            // mimetype 은 STORED 로 첫 번째 엔트리여야 한다 (HWPX 규약)
            byte[] mt = entries.remove("mimetype");
            if (mt != null) {
                ZipEntry mtEntry = new ZipEntry("mimetype");
                mtEntry.setMethod(ZipEntry.STORED);
                mtEntry.setSize(mt.length);
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(mt);
                mtEntry.setCrc(crc.getValue());
                zos.putNextEntry(mtEntry);
                zos.write(mt);
                zos.closeEntry();
            }
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry ze = new ZipEntry(e.getKey());
                zos.putNextEntry(ze);
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
