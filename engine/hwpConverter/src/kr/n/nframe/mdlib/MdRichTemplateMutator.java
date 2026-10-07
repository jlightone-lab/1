package kr.n.nframe.mdlib;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.docinfo.BinData;
import kr.dogfoot.hwplib.object.docinfo.CharShape;
import kr.dogfoot.hwplib.object.docinfo.DocInfo;
import kr.dogfoot.hwplib.object.docinfo.bindata.BinDataCompress;
import kr.dogfoot.hwplib.object.docinfo.bindata.BinDataState;
import kr.dogfoot.hwplib.object.docinfo.bindata.BinDataType;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.writer.HWPWriter;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import kr.n.nframe.mdlib.MdRichParser.ImageEmbed;

/**
 * MdToHwpRich에서 분리된 template.hwp DocInfo mutator + BIN_DATA 슬롯 관리자 (v14.95).
 *
 * <h3>책임</h3>
 * <ul>
 *   <li>template.hwp / template-orig-case1.hwp / template-rich.hwp 리소스 로딩 + 캐싱</li>
 *   <li>이미지 없는 경우 template.hwp DocInfo mutate
 *      (FaceName "맑은 고딕" 통일, CS_CELL/CS_CELL_LEGACY 추가, PS_RIGHT 추가)</li>
 *   <li>이미지 있는 경우 template-orig-case1.hwp 100% byte 보존 (mutate 폐기)</li>
 *   <li>template-rich.hwp 의 BIN_DATA 슬롯 분석 + 작업용 HWPFile 에 mirror</li>
 *   <li>MD 이미지 path → binDataId 매핑 ({@link #pickBinDataIdFor})</li>
 * </ul>
 *
 * <p>{@link #loadMutatedTemplateBytes}은 stateless (static), 나머지 3개는 인스턴스
 * 상태 ({@link #templateBinSlots}, {@link #img7BinId}, {@link #img8BinId}) 에 의존.
 *
 * <p>v14.x 마커 주석은 원본 그대로 보존.
 */
public final class MdRichTemplateMutator {

    // =========================================================
    //  template.hwp CharShape / ParaShape ID 상수 (emit 측 참조용 — public)
    // =========================================================

    /**
     * [v14.28] template.hwp 로 복귀.
     * template.hwp 의 CS[55]: baseSize=1000, isBold=true ← body 와 같은 크기 + bold.
     * (이전 v14.27 의 CS_BOLD=84 는 template-rich.hwp 용이었음)
     */
    public static final int CS_BOLD = 55;

    /** [v14.52] cell 전용 charShape — CS 0 + Hangul/Latin charSpace +5% (자간 약간 넓게).
     *  loadMutatedTemplateBytes() 에서 template.hwp 에 동적으로 추가됨.
     *  section paragraph 는 CS_NORMAL(=0) 그대로, cell paragraph 만 CS_CELL 사용 →
     *  사용자 보고 (center 셀 자간이 section paragraph 대비 좁음) 해소.
     *  [v14.61] CharSpaces 모두 0 (본문과 동일) — 전체 cell 텍스트에 적용. */
    public static final int CS_CELL = 62;

    /** [v14.64] v14.60 legacy charShape — 자간 +50% 유지. 특정 표(예: T15 경기도 공고)
     *  에서 v14.60 시점의 자간/layout 으로 롤백 emit 시 사용. CS_CELL 과 별도 ID. */
    public static final int CS_CELL_LEGACY = 63;

    /** [v14.91 task1] template.hwp 끝에 추가되는 Right 정렬 ParaShape ID.
     *  template.hwp 원본 PS 수 = 45 (0..44). loadMutatedTemplateBytes 에서
     *  PS 0 (Justify) 을 clone 하고 alignment=Right 로 변경한 ParaShape 를 append → ID 45.
     *  paraShapeForAlign("right") 가 이 ID 를 반환. */
    public static final int PS_RIGHT = 45;

    /** [v15.17 task1/2] TOC 점선 leader 용 ParaShape ID. PS 0 clone + tabDefId = TAB_DEF_TOC
     *  (페이지 우측 41954 hwpunit, RIGHT, DOT leader 정의된 TabDef). 인라인 TAB 컨트롤이
     *  본 ParaShape 의 tab stop 을 만나면 한/글이 DOT leader 를 그려 페이지 번호를
     *  우측 정렬한다. PS_RIGHT(=45) 뒤에 추가되므로 ID=46. */
    public static final int PS_TOC = 46;

    /** [v15.18 task1] TOC 용 TabDef 의 위치 (HWP 바이너리 저장값).
     *  목표 시각 좌표 = template-rich.hwp 의 실제 페이지 본문 폭 42520 hwpunit
     *  (= pagePr width 59528 - margin left 8504 - margin right 8504). v15.17 까지는
     *  41954 (이전 잘못 추정한 값) 로 설정되어 실제 페이지 우측 마진과 566 hwpunit
     *  차이가 발생 → 점선 leader 가 우측 마진보다 짧게 끝나 사용자가 "점선이 너무
     *  짧음" 으로 관측.
     *
     *  hwp2hwpx 의 ForTabProperties 가 HwpUnitChar XML case 를 emit 할 때 binary
     *  값을 /2 하여 &lt;hh:tabItem pos="N" unit="HWPUNIT"/&gt; 를 작성하고, 역방향
     *  HWPX→HWP 변환 시 그 값을 그대로 binary 에 다시 기록한다. 결과적으로 binary
     *  위치값이 round-trip 마다 절반이 됨. 이를 보상하기 위해 binary 저장값을 2× =
     *  85040 로 설정 → HWP→HWPX 시 42520 로 기록 → HWPX→HWP 시 42520 으로 복원
     *  되어 최종 binary 위치 = 42520 (정확히 페이지 본문 폭). */
    public static final int TOC_PAGE_INNER_WIDTH = 42520;
    /** [v15.21 task1] TabDef 의 binary 저장 위치값. hwplib 이 setPosition() 값을 한
     *  단계 halving 하여 binary 에 기록 (inspector 가 보고하는 값이 setPosition 값의
     *  절반). 원본 hand-made case1.hwp 의 inspector 보고값은 100000 (= 페이지 우측
     *  마진을 넘어선 "사실상 무한대" 위치) 이며, 본 stop 은 한/글이 page inner
     *  width 로 자동 clamping. 동일 효과를 위해 setPosition(200000) → inspector
     *  100000 으로 설정. v15.20 까지의 85040 (= 42520*2) 은 inspector 가 42520 = page
     *  inner width 와 정확히 일치하여 한/글이 edge case 로 right-align 오작동 →
     *  다중 자릿수 페이지 번호 (11, 17, 22 ...) 가 "1·1", "71", "2·2" 등 깨짐. */
    public static final int TOC_TAB_STOP_HWPUNIT = 200000;

    // =========================================================
    //  template-rich.hwp BIN_DATA 슬롯 (id, ext)
    // =========================================================

    /** template-rich.hwp 의 BIN_DATA 정보 (binDataId, ext) — 픽처 컨트롤 emit 에 사용 */
    static class BinSlot {
        final int id;
        final String ext;
        BinSlot(int id, String ext) { this.id = id; this.ext = ext; }
    }

    /** template 의 BIN_DATA 슬롯 목록 (id 1..N 순서) */
    private final List<BinSlot> templateBinSlots = new ArrayList<>();
    /** image-7.png → binDataId 7 (예: PNG/BMP 매핑용) */
    private int img7BinId = -1; // PNG
    private int img8BinId = -1; // BMP/JPG/etc

    // =========================================================
    //  공개 API — 인스턴스 메서드 (BIN_DATA 슬롯 상태에 의존)
    // =========================================================

    /** template-rich.hwp 을 hwplib 으로 한 번 읽어 BIN_DATA 슬롯을 분석. */
    public void loadTemplateBinSlots() {
        templateBinSlots.clear();
        img7BinId = -1;
        img8BinId = -1;
        Path tmpFile = null;
        try {
            byte[] tplBytes = loadResource("hwp-streams/template-rich.hwp");
            tmpFile = Files.createTempFile("hwp-rich-tpl-", ".hwp");
            Files.write(tmpFile, tplBytes);
            HWPFile tpl = HWPReader.fromFile(tmpFile.toString());
            for (BinData bd : tpl.getDocInfo().getBinDataList()) {
                int id = bd.getBinDataID();
                String ext = bd.getExtensionForEmbedding();
                templateBinSlots.add(new BinSlot(id, ext));
                if (id == 7) img7BinId = id;
                if (id == 8) img8BinId = id;
            }
            System.out.println("[MdToHwpRich] template BIN_DATA 슬롯: " + templateBinSlots.size()
                    + " (img7BinId=" + img7BinId + ", img8BinId=" + img8BinId + ")");
        } catch (Throwable t) {
            System.out.println("[MdToHwpRich] template BIN_DATA 읽기 실패 → 이미지는 placeholder 처리: "
                    + t.getClass().getSimpleName() + " - " + t.getMessage());
        } finally {
            if (tmpFile != null) {
                try { Files.deleteIfExists(tmpFile); } catch (Exception ignored) {}
            }
        }
    }

    /** template 의 BIN_DATA 슬롯과 동일한 레코드를 작업용 HWPFile DocInfo 에 추가. */
    public void mirrorTemplateBinDataIntoDocInfo(DocInfo docInfo) {
        if (templateBinSlots.isEmpty()) return;
        for (BinSlot slot : templateBinSlots) {
            BinData b = docInfo.addNewBinData();
            b.getProperty().setType(BinDataType.Embedding);
            b.getProperty().setCompress(BinDataCompress.ByStorageDefault);
            b.getProperty().setState(BinDataState.NotAccess);
            b.setBinDataID(slot.id);
            b.setExtensionForEmbedding(slot.ext);
        }
        System.out.println("[MdToHwpRich] BIN_DATA mirror 완료: " + templateBinSlots.size() + "개");
    }

    /**
     * MD 이미지 path 에서 binDataId 를 결정.
     * - image-7.* → 7 (PNG)
     * - image-8.* → 8 (BMP)
     * - 외에는 path 의 숫자(image-N.*)에 매칭되는 슬롯 사용. 매칭 실패 시 -1.
     */
    public int pickBinDataIdFor(String path) {
        if (path == null) return -1;
        // v14.27: data: URI 의 경우 mime type 으로 슬롯 매칭
        String low0 = path.toLowerCase();
        if (low0.startsWith("data:")) {
            if (low0.contains("image/png") && img7BinId > 0) return img7BinId;
            if (img8BinId > 0) return img8BinId;
            if (!templateBinSlots.isEmpty()) return templateBinSlots.get(0).id;
            return -1;
        }
        Matcher m = Pattern.compile("image[-_]?(\\d+)", Pattern.CASE_INSENSITIVE).matcher(path);
        if (m.find()) {
            try {
                int n = Integer.parseInt(m.group(1));
                for (BinSlot slot : templateBinSlots) {
                    if (slot.id == n) return n;
                }
            } catch (NumberFormatException ignored) {}
        }
        // 확장자 fallback
        String low = path.toLowerCase();
        if (low.endsWith(".png") && img7BinId > 0) return img7BinId;
        if (low.endsWith(".bmp") && img8BinId > 0) return img8BinId;
        // 마지막 fallback: 첫 슬롯
        if (!templateBinSlots.isEmpty()) return templateBinSlots.get(0).id;
        return -1;
    }

    // =========================================================
    //  공개 API — static (DocInfo mutation, stateless)
    // =========================================================

    /**
     * template.hwp / template-orig-case1.hwp 를 base 로 가져와 (이미지 유무에 따라)
     * DocInfo 를 mutate 한 바이트 배열을 반환한다.
     *
     * <p>이미지가 있으면 case1.hwp 원본 (mutate 없이) 반환 — v14.81 결정.
     * 이미지가 없으면 template.hwp 로딩 후 다음 mutation 적용:
     * <ul>
     *   <li>모든 FaceName "맑은 고딕" 통일 (v14.89 task2)</li>
     *   <li>CS_CELL (자간 0%) + CS_CELL_LEGACY (자간 +50%) 추가 (v14.52/v14.61/v14.64)</li>
     *   <li>PS_RIGHT (Right 정렬 ParaShape) 추가 (v14.91 task1)</li>
     * </ul>
     */
    public static byte[] loadMutatedTemplateBytes(List<ImageEmbed> imageEmbeds) {
        try {
            // [v14.81] 이미지 있으면 사용자 원본 case1.hwp (template-orig-case1.hwp) base.
            //   한/글이 만든 byte 100% 보존 + DocInfo mutate 안 함. 모든 charShape/paraShape 가
            //   case1.hwp 의 998/792개 안에 valid → 우리 Section0 의 ID 참조 모두 정상.
            //   이미지 없으면 기존 template.hwp.
            boolean hasImages = (imageEmbeds != null && !imageEmbeds.isEmpty());
            byte[] orig = hasImages
                    ? loadResource("hwp-streams/template-orig-case1.hwp")
                    : loadResource("hwp-streams/template.hwp");
            // [v14.81] 이미지 있는 경우 — case1.hwp 그대로 사용 (mutate 폐기). 한/글 100% 보존.
            if (hasImages) {
                System.out.println("[MdToHwpRich] 이미지 있음 — case1.hwp 원본 그대로 base 사용 (DocInfo mutate 폐기)");
                return orig;
            }
            // [v14.52] HWPReader 로 DocInfo 만 mutate (CS 62 추가) → POIFS 로 DocInfo
            //   stream 만 교체. PrvImage/PrvText/Summary/Scripts 등 원본 streams 유지.
            // [v14.70] imageEmbeds 가 있을 때 BinData record + EmbeddedBinaryData 도 추가
            //   하고 BinData 디렉토리도 mutated stream 들로 교체.
            try {
                Path tmp = Files.createTempFile("hwp-rich-mutate-", ".hwp");
                Files.write(tmp, orig);
                HWPFile tpl = HWPReader.fromFile(tmp.toString());
                DocInfo di = tpl.getDocInfo();
                if (CS_BOLD < di.getCharShapeList().size()) {
                    CharShape cs = di.getCharShapeList().get(CS_BOLD);
                    System.out.println("[MdToHwpRich] template CS[" + CS_BOLD + "] baseSize="
                            + cs.getBaseSize()
                            + ", prop=0x" + Long.toHexString(cs.getProperty().getValue())
                            + ", isBold=" + cs.getProperty().isBold());
                }
                // [v14.89 task2] 모든 FaceName 의 모든 entry 를 "맑은 고딕"으로 통일.
                //   v14.88 까지는 첫 entry (id=0) 만 변경했으나, CharShape 가 fontId=2~20 등을 참조하면
                //   여전히 "함초롱바탕"(id=6) 등이 노출되었음. 모든 entry 를 일괄 치환해 100% 맑은 고딕화.
                //   substituteFontName / baseFontName 도 함께 변경 — Hangul OS 가 fallback 시 사용.
                final String FONT = "맑은 고딕";
                java.util.List<java.util.ArrayList<kr.dogfoot.hwplib.object.docinfo.FaceName>> all =
                        java.util.Arrays.asList(
                                di.getHangulFaceNameList(),
                                di.getEnglishFaceNameList(),
                                di.getHanjaFaceNameList(),
                                di.getJapaneseFaceNameList(),
                                di.getEtcFaceNameList(),
                                di.getSymbolFaceNameList(),
                                di.getUserFaceNameList());
                int total = 0;
                for (java.util.ArrayList<kr.dogfoot.hwplib.object.docinfo.FaceName> list : all) {
                    if (list == null) continue;
                    for (kr.dogfoot.hwplib.object.docinfo.FaceName fn : list) {
                        fn.setName(FONT);
                        try {
                            fn.setSubstituteFontName(FONT);
                            fn.setBaseFontName(FONT);
                        } catch (Throwable ignore) { /* substitute / base 는 nullable */ }
                        total++;
                    }
                }
                System.out.println("[MdToHwpRich] 모든 폰트 → " + FONT + " (" + total + "개 변경)");

                int existingSize = di.getCharShapeList().size();
                if (existingSize <= CS_CELL_LEGACY) {
                    CharShape proto = di.getCharShapeList().get(0);
                    // [v14.64] CS_CELL (자간 0%) + CS_CELL_LEGACY (자간 +50%) 둘 다 추가.
                    //   두 charShape ID 사이의 빈 슬롯도 prototype clone 으로 채움.
                    while (di.getCharShapeList().size() <= CS_CELL_LEGACY) {
                        int newId = di.getCharShapeList().size();
                        CharShape clone = proto.clone();
                        try {
                            if (newId == CS_CELL) {
                                // 자간 0% (본문과 동일) — v14.61 도입.
                                clone.getCharSpaces().setHangul((byte) 0);
                                clone.getCharSpaces().setLatin((byte) 0);
                                clone.getCharSpaces().setHanja((byte) 0);
                                clone.getCharSpaces().setSymbol((byte) 0);
                                clone.getCharSpaces().setOther((byte) 0);
                            } else if (newId == CS_CELL_LEGACY) {
                                // 자간 +50% (v14.60 legacy) — 특정 표 롤백용.
                                clone.getCharSpaces().setHangul((byte) 50);
                                clone.getCharSpaces().setLatin((byte) 50);
                                clone.getCharSpaces().setHanja((byte) 50);
                                clone.getCharSpaces().setSymbol((byte) 50);
                                clone.getCharSpaces().setOther((byte) 50);
                            }
                        } catch (Throwable ignore) {}
                        di.getCharShapeList().add(clone);
                    }
                    System.out.println("[MdToHwpRich] CS_CELL=" + CS_CELL
                            + " (자간 0% — 본문) + CS_CELL_LEGACY=" + CS_CELL_LEGACY
                            + " (자간 +50% — v14.60 롤백) 추가 — 총 CharShapes="
                            + di.getCharShapeList().size());
                }

                // [v14.91 task1] Right 정렬 ParaShape 추가 — template.hwp 원본에 Right PS 가
                //   없어서 <div align="right">/<p align="right"> 가 PS 0 (Justify) 로 fallback
                //   되어 좌측 정렬되던 회귀 수정. PS 0 clone → alignment=Right 로만 변경.
                //   PS_RIGHT (=45) 슬롯을 보장. 이미 존재하면 skip.
                while (di.getParaShapeList().size() < PS_RIGHT) {
                    di.getParaShapeList().add(di.getParaShapeList().get(0).clone());
                }
                if (di.getParaShapeList().size() == PS_RIGHT) {
                    kr.dogfoot.hwplib.object.docinfo.ParaShape rightPs =
                            di.getParaShapeList().get(0).clone();
                    rightPs.getProperty1().setAlignment(
                            kr.dogfoot.hwplib.object.docinfo.parashape.Alignment.Right);
                    di.getParaShapeList().add(rightPs);
                    System.out.println("[MdToHwpRich] PS_RIGHT=" + PS_RIGHT
                            + " (Right 정렬 ParaShape) 추가 — 총 ParaShapes="
                            + di.getParaShapeList().size());
                }

                // [v15.17 task1/2] TOC 점선 leader 용 TabDef + ParaShape 추가.
                //   참고 case1.hwp 분석 결과 한/글이 직접 만든 TOC 단락은
                //   ParaShape.tabDefId → TabDef (TabInfo: pos=N, sort=Right, fill=Dot)
                //   를 참조해야 한/글이 인라인 TAB 컨트롤 (leader=3) 의 점선을 실제로
                //   렌더한다. v15.16 까지 PS 0 (tabDefId → 기본 LEFT/None 39 stops) 로
                //   emit 되어 dots 가 사라짐. PS_TOC = PS 0 clone + tabDefId = 새 TabDef.
                if (di.getParaShapeList().size() == PS_TOC) {
                    kr.dogfoot.hwplib.object.docinfo.TabDef tocTabDef = di.addNewTabDef();
                    // [v15.17] TabDefProperty.value=0 default ok. 첫 TabInfo 만 추가.
                    kr.dogfoot.hwplib.object.docinfo.tabdef.TabInfo ti = tocTabDef.addNewTabInfo();
                    ti.setPosition(TOC_TAB_STOP_HWPUNIT);
                    ti.setTabSort(kr.dogfoot.hwplib.object.docinfo.tabdef.TabSort.Right);
                    ti.setFillSort(kr.dogfoot.hwplib.object.docinfo.borderfill.BorderType.Dot);
                    int tocTabDefId = di.getTabDefList().size() - 1;
                    kr.dogfoot.hwplib.object.docinfo.ParaShape tocPs =
                            di.getParaShapeList().get(0).clone();
                    tocPs.setTabDefId(tocTabDefId);
                    // [v15.20 task1] PS_TOC align = Left (원본 case1.hwp 의 TOC ParaShape
                    //   align=Left 미러). PS 0 의 default Justify 가 클론에 그대로 따라와
                    //   v15.19 까지 JUSTIFY 였으나, JUSTIFY + 인라인 TAB 은 한/글이 leader 를
                    //   짧게 그리는 회귀 원인. Left 로 변경하면 leader 가 RIGHT tab stop 까지
                    //   균일하게 채워짐.
                    tocPs.getProperty1().setAlignment(
                            kr.dogfoot.hwplib.object.docinfo.parashape.Alignment.Left);
                    // [v15.23 task1] 원본 hand-made paraPr 매칭 — 다중 자릿수 페이지번호
                    //   "1·1", "71" 회귀 해소 시도. 원본 paraPr 200 은 :
                    //     breakLatinWord = KEEP_WORD  (영문/숫자 단어를 줄 끝에서 분리 안 함)
                    //     breakNonLatinWord = BREAK_WORD  (Hangul 은 글자 단위 break 허용)
                    //   PS 0 default 는 반대 (Latin=BREAK_WORD, NonLatin=KEEP_WORD).
                    //   가설 : breakLatinWord=BREAK_WORD 가 페이지번호 "11", "17" 등 다중
                    //   자릿수를 RIGHT-tab 정렬 시 분리/뒤집힘 회귀의 원인 가능. 원본 매칭.
                    tocPs.getProperty1().setLineDivideForEnglish(
                            kr.dogfoot.hwplib.object.docinfo.parashape.LineDivideForEnglish.ByWord);
                    // hwplib LineDivideForHangul enum 이름이 OWPML 과 반대 :
                    //   ByLetter (byte 0) → OWPML KEEP_WORD
                    //   ByWord   (byte 1) → OWPML BREAK_WORD
                    //   원본 paraPr 200 의 breakNonLatinWord=BREAK_WORD 매칭에는 ByWord 사용.
                    tocPs.getProperty1().setLineDivideForHangul(
                            kr.dogfoot.hwplib.object.docinfo.parashape.LineDivideForHangul.ByWord);
                    di.getParaShapeList().add(tocPs);
                    // IDMappings tabDefCount 보정 (HWPWriter 가 list size 를 신뢰하지만
                    // 안전망으로 동기화).
                    di.getIDMappings().setTabDefCount(di.getTabDefList().size());
                    System.out.println("[MdToHwpRich] PS_TOC=" + PS_TOC
                            + " (TOC tabDef pos=" + TOC_TAB_STOP_HWPUNIT
                            + " RIGHT/DOT, tabDefId=" + tocTabDefId
                            + ") 추가 — 총 ParaShapes=" + di.getParaShapeList().size()
                            + ", 총 TabDefs=" + di.getTabDefList().size());
                }

                // [v14.79] 정상 sample (template-with-images.hwp) 의 BinData record + stream
                //   그대로 사용. 추가 안 함. picture control 이 binItemID=7, 8 으로 정상 sample
                //   의 BIN0007.png/BIN0008.bmp stream 을 참조 → 한/글 손상 회피.
                if (imageEmbeds != null && !imageEmbeds.isEmpty()) {
                    System.out.println("[MdToHwpRich] base64 이미지 → 정상 sample BinData 슬롯 매핑: "
                            + imageEmbeds.size() + "개 (BinData 추가 없음, IDMappings 그대로)");
                }

                // HWPWriter 로 전체 직렬화 후 그 안에서 DocInfo stream 만 추출
                Path full = Files.createTempFile("hwp-rich-full-", ".hwp");
                HWPWriter.toFile(tpl, full.toString());
                byte[] mutatedDocInfo;
                java.util.Map<String, byte[]> mutatedBinDataStreams = new java.util.HashMap<>();
                try (POIFSFileSystem mutPoi = new POIFSFileSystem(new FileInputStream(full.toFile()))) {
                    mutatedDocInfo = readEntryBytes(mutPoi.getRoot(), "DocInfo");
                    // [v14.70] BinData 디렉토리 stream 들도 추출
                    if (mutPoi.getRoot().hasEntry("BinData")) {
                        DirectoryEntry binDir = (DirectoryEntry) mutPoi.getRoot().getEntry("BinData");
                        for (org.apache.poi.poifs.filesystem.Entry e : binDir) {
                            if (e instanceof org.apache.poi.poifs.filesystem.DocumentEntry) {
                                byte[] data = readEntryBytes(binDir, e.getName());
                                mutatedBinDataStreams.put(e.getName(), data);
                            }
                        }
                    }
                }
                Files.deleteIfExists(tmp);
                Files.deleteIfExists(full);
                // 원본 POIFS 에서 DocInfo 교체 + (이미지 있으면) BinData 디렉토리 갱신
                try (POIFSFileSystem origPoi = new POIFSFileSystem(new ByteArrayInputStream(orig))) {
                    DirectoryEntry root = origPoi.getRoot();
                    for (org.apache.poi.poifs.filesystem.Entry e : root) {
                        if (e.getName().equals("DocInfo")) { e.delete(); break; }
                    }
                    root.createDocument("DocInfo", new ByteArrayInputStream(mutatedDocInfo));

                    // [v14.79] BinData 디렉토리 갱신 안 함 — 정상 sample 의 BIN0001-8 stream 그대로
                    //   사용 (orig POIFS 의 BinData 그대로 보존). picture control 이 binItemID=7, 8
                    //   참조 → BIN0007.png / BIN0008.bmp 매칭.

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    origPoi.writeFilesystem(baos);
                    return baos.toByteArray();
                }
            } catch (Throwable t) {
                System.err.println("[MdToHwpRich] template mutate 실패 — orig 반환: " + t.getMessage());
                return orig;
            }
        } catch (Exception e) {
            throw new RuntimeException("template.hwp resource load fail", e);
        }
    }

    // =========================================================
    //  POI / 리소스 헬퍼 (MdToHwpRich Section0 splice 등에서도 사용)
    // =========================================================

    /** OLE2 디렉터리에서 지정 이름 스트림의 바이트를 통째로 읽음. */
    public static byte[] readEntryBytes(DirectoryEntry dir, String name) throws Exception {
        DocumentEntry de = (DocumentEntry) dir.getEntry(name);
        byte[] data = new byte[de.getSize()];
        try (DocumentInputStream is = new DocumentInputStream(de)) {
            int t = 0;
            while (t < data.length) {
                int r = is.read(data, t, data.length - t);
                if (r < 0) break;
                t += r;
            }
        }
        return data;
    }

    /** classpath 리소스 `/kr/n/nframe/resources/{relPath}` 를 byte[] 로 적재. */
    public static byte[] loadResource(String relPath) throws java.io.IOException {
        String full = "/kr/n/nframe/resources/" + relPath;
        try (InputStream in = MdRichTemplateMutator.class.getResourceAsStream(full)) {
            if (in == null) throw new java.io.IOException("Resource missing: " + full);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) baos.write(buf, 0, n);
            return baos.toByteArray();
        }
    }
}
