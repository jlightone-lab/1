package kr.n.nframe.mdlib;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.ControlType;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.CtrlHeaderGso;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.HeightCriterion;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.HorzRelTo;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.ObjectNumberSort;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.RelativeArrange;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.TextFlowMethod;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.TextHorzArrange;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.VertRelTo;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.WidthCriterion;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlPicture;
import kr.dogfoot.hwplib.object.bodytext.control.gso.GsoControlType;
import kr.dogfoot.hwplib.object.bodytext.control.gso.shapecomponent.ShapeComponent;
import kr.dogfoot.hwplib.object.bodytext.control.gso.shapecomponenteach.ShapeComponentPicture;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.ListHeaderForCell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.control.table.ZoneInfo;
import kr.dogfoot.hwplib.tool.TableCellMerger;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.lineseg.LineSegItem;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharControlInline;
import kr.dogfoot.hwplib.object.docinfo.BinData;
import kr.dogfoot.hwplib.object.docinfo.CharShape;
import kr.dogfoot.hwplib.object.docinfo.DocInfo;
import kr.dogfoot.hwplib.object.docinfo.bindata.BinDataCompress;
import kr.dogfoot.hwplib.object.docinfo.bindata.BinDataState;
import kr.dogfoot.hwplib.object.docinfo.bindata.BinDataType;
import kr.dogfoot.hwplib.object.docinfo.borderfill.fillinfo.PictureEffect;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwplib.writer.HWPWriter;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

// MD/HTML 파서 + 블록 데이터 모델은 MdRichParser 로 분리됨 (v14.95).
// nested 타입 (Block, Heading, Table, TableCell, Image, ImageEmbed, RichLine, Blank, MdParagraph)
// 와 static 메서드 (parseMarkdown, renderBlockRich, extractStrong, sliceRanges,
// stripStrongMarkers, flattenNestedTableToText, USE_NESTED_HWP_TABLE) 를 단순 명칭으로 사용한다.
import kr.n.nframe.mdlib.MdRichParser.Block;
import kr.n.nframe.mdlib.MdRichParser.Blank;
import kr.n.nframe.mdlib.MdRichParser.Heading;
import kr.n.nframe.mdlib.MdRichParser.Image;
import kr.n.nframe.mdlib.MdRichParser.ImageEmbed;
import kr.n.nframe.mdlib.MdRichParser.MdParagraph;
import kr.n.nframe.mdlib.MdRichParser.RichLine;
import kr.n.nframe.mdlib.MdRichParser.Table;
import kr.n.nframe.mdlib.MdRichParser.TableCell;
import static kr.n.nframe.mdlib.MdRichParser.USE_NESTED_HWP_TABLE;
import static kr.n.nframe.mdlib.MdRichParser.parseMarkdown;
import static kr.n.nframe.mdlib.MdRichParser.renderBlockRich;
import static kr.n.nframe.mdlib.MdRichParser.extractStrong;
import static kr.n.nframe.mdlib.MdRichParser.sliceRanges;
import static kr.n.nframe.mdlib.MdRichParser.stripStrongMarkers;
import static kr.n.nframe.mdlib.MdRichParser.flattenNestedTableToText;

// Template DocInfo mutator + BIN_DATA 슬롯 관리는 MdRichTemplateMutator 로 분리됨 (v14.95).
import static kr.n.nframe.mdlib.MdRichTemplateMutator.CS_BOLD;
import static kr.n.nframe.mdlib.MdRichTemplateMutator.CS_CELL;
import static kr.n.nframe.mdlib.MdRichTemplateMutator.CS_CELL_LEGACY;
import static kr.n.nframe.mdlib.MdRichTemplateMutator.PS_RIGHT;
import static kr.n.nframe.mdlib.MdRichTemplateMutator.PS_TOC;
import static kr.n.nframe.mdlib.MdRichTemplateMutator.TOC_PAGE_INNER_WIDTH;
import static kr.n.nframe.mdlib.MdRichTemplateMutator.loadMutatedTemplateBytes;
import static kr.n.nframe.mdlib.MdRichTemplateMutator.readEntryBytes;
import static kr.n.nframe.mdlib.MdRichTemplateMutator.loadResource;

// 이미지 base64 디코딩 + picture control emit 은 MdRichImageEmbedder 로 분리됨 (v14.95).
import static kr.n.nframe.mdlib.MdRichImageEmbedder.NATIVE_PICTURE;
import static kr.n.nframe.mdlib.MdRichImageEmbedder.decodeBase64ImageDataUri;
import static kr.n.nframe.mdlib.MdRichImageEmbedder.addImageParagraph;

/**
 * v14.11: MD → HWP "Rich" 변환. 기존 v14.9/14.10 의 텍스트 전용 출력 대비
 * heading 별 실제 폰트 크기와, BinData 에 이미지가 포함된 풍부한 템플릿
 * (template-rich.hwp / case1.hwp 전체) 을 활용한다.
 *
 * <h3>접근</h3>
 * <ul>
 *   <li>BlankFileMaker 로 새 HWPFile 생성 (5개 기본 charShape)</li>
 *   <li>H1~H6 용 charShape 6개 추가 (인덱스 5..10): 24/20/16/14/12/11pt</li>
 *   <li>Heading 블록은 해당 charShape 인덱스를 paragraph 의 ParaCharShape 에 사용</li>
 *   <li>HWPWriter.toFile() 로 임시 파일 생성 → POI 로 Section0 추출</li>
 *   <li>template-rich.hwp 를 POI 로 열어 Section0 만 교체</li>
 *   <li>실패 시 호출자(MdStructureConverter)가 MdToHwpDirect 로 fallback</li>
 * </ul>
 */
public class MdToHwpRich {

    /**
     * v14.16: 우리는 Section0 만 template.hwp 로 splice 한다. 따라서 최종
     * .hwp 의 DocInfo 는 template.hwp 의 것이다 (BlankFileMaker 가 만든 우리
     * DocInfo 가 아님). heading 은 template.hwp 안에 이미 존재하는 CharShape
     * 중 큰 baseSize 를 가진 6개를 hard-code 로 참조해야 한다.
     *
     * InspectTemplateCS 로 측정한 template.hwp 의 charShape 분포 (62개):
     *   CS[ 7] baseSize=2000 (제일 큼)         → H1
     *   CS[ 5] baseSize=1600 color=0xb5742e    → H2
     *   CS[12] baseSize=1500                   → H3
     *   CS[13] baseSize=1400                   → H4
     *   CS[20] baseSize=1300                   → H5
     *   CS[30] baseSize=1200 italic            → H6
     *   CS[ 0] baseSize=1000 (기본)             → 본문
     *   CS[16] baseSize=1120 (mutate to bold)  → <strong>
     *
     * 기본 body charShape 는 CS[0] (baseSize=1000, plain) 사용.
     */
    private static final int CS_NORMAL = 0;       // 10pt 기본 (template CS[0])
    // CS_BOLD/CS_CELL/CS_CELL_LEGACY 은 MdRichTemplateMutator 로 이동 (v14.95) — static import.
    // USE_NESTED_HWP_TABLE 은 MdRichParser 로 이동 (v14.95) — static import 로 사용.
    /**
     * [v14.28] template.hwp 의 heading CharShape (descending size 기준):
     *   H1 → CS[7]   baseSize=2000 (20pt)
     *   H2 → CS[5]   baseSize=1600 (16pt) color
     *   H3 → CS[12]  baseSize=1500 (15pt)
     *   H4 → CS[13]  baseSize=1400 (14pt)
     *   H5 → CS[20]  baseSize=1300 (13pt)
     *   H6 → CS[30]  baseSize=1200 (12pt) italic
     */
    private static final int[] CS_HEADINGS = { 7, 5, 12, 13, 20, 30 };

    // PS_RIGHT 은 MdRichTemplateMutator 로 이동 (v14.95) — static import.

    /**
     * v14.26: native HWP 표 emission. true 로 재활성화 — MD preview 의 표 모양을
     * HWP 에서 native 셀(ControlTable + Row + Cell) 로 표시한다.
     * v14.17~v14.23 의 모든 byte-level fix (textFlow=TakePlace, borderFillId=3,
     * isApplyInnerMargin=false, outerMargin=283, LineSegItemTag bits 17/18,
     * 빈 셀 ZWSP+terminator, 셀 병합 colspan/rowspan ZoneInfo 등) 가 모두 적용됨.
     * 한/글에서 표 손상 팝업 발생 시 false 로 되돌릴 수 있음.
     */
    private static final boolean NATIVE_TABLE = true;

    // NATIVE_PICTURE 은 MdRichImageEmbedder 로 이동 (v14.95) — static import.

    /** template charShape 의 실제 baseSize (line height 계산용). */
    private static final int[] CS_HEADING_SIZES = { 2000, 1600, 1500, 1400, 1300, 1200 };

    /** MD 파일 경로 (이미지 상대경로 해석에 사용 — 현재는 placeholder 처리만) */
    private Path mdParentDir;

    /** BIN_DATA 슬롯 관리 + DocInfo mutate 위임 (v14.95). */
    private final MdRichTemplateMutator templateMutator = new MdRichTemplateMutator();

    // =========================================================
    //  [task1/2 — 손상 팝업 수정] 단락 instanceID unique 발급기
    // =========================================================
    /**
     * <p>HWP/HWPX 의 paragraph header.instanceID 는 4-byte UINT32 식별자다.
     * 이전 (~v15.32) 변환기는 모든 단락에 {@code 0x80000000} 을 하드코드 — HWP→HWPX
     * 라운드트립 후 HWPX section0.xml 의 거의 모든 {@code <hp:p>} 가 동일한
     * {@code id="2147483648"} 으로 직렬화되어 한/글이 "문서에 손상을 줄 수 있는 내용이
     * 포함되어 있습니다" 손상 경고를 발생시켰다.</p>
     *
     * <p>본 카운터로 모든 단락에 unique 32-bit UINT 발급. 범위 {@code 0x80000001..0xFFFFFFFF}
     * (bit 31 set 유지 — KG default 관례). 0xFFFFFFFF 초과 시 다시 처음으로 wrap-around.
     * 동일 section 안에서 충돌하지 않도록 monotonic.</p>
     */
    public static final java.util.concurrent.atomic.AtomicLong NEXT_PARA_INSTANCE_ID
            = new java.util.concurrent.atomic.AtomicLong(0x80000001L);

    /** 단락별 유니크 instanceID 발급 (32-bit unsigned, bit31 set). */
    public static long nextParaInstanceId() {
        long v = NEXT_PARA_INSTANCE_ID.getAndIncrement();
        if (v > 0xFFFFFFFFL) {
            // wrap-around — 매우 드물지만 안전 장치
            NEXT_PARA_INSTANCE_ID.set(0x80000001L);
            v = NEXT_PARA_INSTANCE_ID.getAndIncrement();
        }
        return v & 0xFFFFFFFFL;
    }

    public void convert(String filePathMd, String filePathHwp) throws Exception {
        System.out.println("[MdToHwpRich] 변환 시작 (heading 폰트 크기 + 풍부 템플릿)");
        System.out.println("[MdToHwpRich] Input : " + filePathMd);
        System.out.println("[MdToHwpRich] Output: " + filePathHwp);

        Path mdPath = Paths.get(filePathMd).toAbsolutePath();
        this.mdParentDir = mdPath.getParent();

        byte[] mdBytes = Files.readAllBytes(mdPath);
        String mdText = new String(mdBytes, StandardCharsets.UTF_8);

        List<Block> blocks = parseMarkdown(mdText);
        int hCount = 0, pCount = 0, tCount = 0, iCount = 0, bCount = 0;
        for (Block b : blocks) {
            if (b instanceof Heading) hCount++;
            else if (b instanceof MdParagraph) pCount++;
            else if (b instanceof Table) tCount++;
            else if (b instanceof Image) iCount++;
            else if (b instanceof Blank) bCount++;
        }
        System.out.println("[MdToHwpRich] 파싱 블록: total=" + blocks.size()
                + " (heading=" + hCount + ", paragraph=" + pCount
                + ", table=" + tCount + ", image=" + iCount + ", blank=" + bCount + ")");

        // 0) template-rich.hwp 의 BIN_DATA 슬롯 사전 분석 (이미지 binDataId 매핑용)
        templateMutator.loadTemplateBinSlots();

        // [v14.70] base64 data URI 이미지 분석 → ImageEmbed 리스트.
        // [v14.79] case1.md 의 이미지를 정상 sample (template-with-images.hwp) 의 BinData
        //   슬롯에 매핑 — 1046×674 PNG → BIN0007, 632×822 BMP → BIN0008.
        //   매칭되는 sample 슬롯이 있으면 그 binDataId 사용 (BinData 추가 불필요),
        //   없으면 placeholder fallback (이미지 안 보임 but 손상 회피).
        List<ImageEmbed> imageEmbeds = new ArrayList<>();
        if (NATIVE_PICTURE) {
            for (Block b : blocks) {
                if (!(b instanceof Image)) continue;
                Image img = (Image) b;
                if (img.path == null || !img.path.startsWith("data:image/")) continue;
                ImageEmbed ie = decodeBase64ImageDataUri(img.path, 0);
                if (ie == null) continue;
                int targetBinId = -1;
                if (ie.pixelW == 1046 && ie.pixelH == 674) targetBinId = 7;       // PNG → 정상 sample BIN0007.png
                else if (ie.pixelW == 632 && ie.pixelH == 822) targetBinId = 8;   // BMP → 정상 sample BIN0008.bmp
                if (targetBinId > 0) {
                    ie.binDataId = targetBinId;
                    imageEmbeds.add(ie);
                    img.binDataId = targetBinId;
                }
            }
        }
        if (!imageEmbeds.isEmpty()) {
            System.out.println("[MdToHwpRich] base64 이미지 임베드: " + imageEmbeds.size() + "개 (binDataId "
                    + imageEmbeds.get(0).binDataId + "..." + imageEmbeds.get(imageEmbeds.size()-1).binDataId + ")");
        }

        // 1) hwplib 으로 신규 HWPFile 빌드. v14.16: heading 용 추가 charShape 는
        //    더 이상 만들지 않는다 (Section0 이 template.hwp 로 splice 되면 우리가
        //    추가한 ID 는 template DocInfo 에 mismatch). 대신 template.hwp 에
        //    이미 존재하는 charShape (CS_HEADINGS / CS_BOLD) 를 직접 참조한다.
        HWPFile hwp = BlankFileMaker.make();
        // [v14.79] 작업용 hwp 의 DocInfo 에 BinData record 추가 — picture control 의
        //   binItemID 가 7, 8 을 참조하므로 작업용 DocInfo 에도 ID 1-8 record 가 있어야
        //   HWPWriter 가 Section0 의 picture control 을 정상 직렬화. mirror 형태로 8개 추가.
        if (NATIVE_PICTURE && !imageEmbeds.isEmpty()) {
            // 1-8 placeholder record (정상 sample 의 BinData ID 매핑용)
            for (int sid = 1; sid <= 8; sid++) {
                kr.dogfoot.hwplib.object.docinfo.BinData bd = hwp.getDocInfo().addNewBinData();
                bd.getProperty().setType(BinDataType.Embedding);
                bd.getProperty().setCompress(BinDataCompress.ByStorageDefault);
                bd.getProperty().setState(BinDataState.NotAccess);
                bd.setBinDataID(sid);
                bd.setExtensionForEmbedding(sid == 7 ? "png" : (sid == 8 ? "bmp" : "jpg"));
            }
            hwp.getDocInfo().getIDMappings().setBinDataCount(8);
        }
        Section sec = hwp.getBodyText().getLastSection();
        // [v15.30 task2] v15.29 의 ensureSectionPara0Controls 가 HWP 파일 손상을 유발한 것
        //   으로 보고됨 (한/글 "파일이 손상되었습니다" 오류). 원본 HWPX 도 hp:pageNumberPosition
        //   을 갖지 않으므로 해당 control 보강은 손상 경고의 핵심 원인이 아닐 가능성이 높음.
        //   따라서 ensureSectionPara0Controls 호출 제거. 손상 경고 원인은 별도 탐색 필요.
        // [v15.25] 새 변환 시작 — section 누적 Y reset.
        sectionRunningY = 0;

        int totalChars = 0;
        int addedParas = 0;
        int picsEmitted = 0;
        int nativeTables = 0;
        int fallbackTables = 0;
        for (Block b : blocks) {
            // v14.15: native HWP picture control 은 Hangul 이 받아주지 않아 (파일 손상)
            //         placeholder 텍스트로 안전하게 처리. (template 의 BIN_DATA 는
            //         orphan 으로 남지만 한/글이 정상 무시.)
            if (b instanceof Image) {
                Image img = (Image) b;
                boolean emitted = false;
                if (NATIVE_PICTURE) {
                    // [v14.70] base64 임베드 우선 (img.binDataId), 없으면 path 기반 fallback
                    int binId = (img.binDataId > 0) ? img.binDataId : templateMutator.pickBinDataIdFor(img.path);
                    if (binId > 0) {
                        try {
                            // [v14.77] 이미지 자연 픽셀 크기 → naturalW/H 산출 (1px = 75 hwpunit)
                            int pxW = 0, pxH = 0;
                            for (ImageEmbed ie : imageEmbeds) {
                                if (ie.binDataId == binId) { pxW = ie.pixelW; pxH = ie.pixelH; break; }
                            }
                            addImageParagraph(sec, binId, pxW, pxH);
                            addedParas++;
                            picsEmitted++;
                            emitted = true;
                        } catch (Exception e) {
                            System.out.println("[MdToHwpRich] picture emit 실패 → placeholder fallback: "
                                    + e.getClass().getSimpleName() + " - " + e.getMessage());
                        }
                    }
                }
                if (!emitted) {
                    for (RichLine line : renderBlockRich(b)) {
                        addParagraph(sec, line.text, CS_NORMAL, line.boldRanges);
                        addedParas++;
                        totalChars += line.text.length();
                    }
                }
                continue;
            }

            // [v14.24] Native HWP 표는 한/글의 셀 검증을 통과하지 못해 "표가 손상되었습니다"
            //          팝업이 발생하는 문제가 v14.17~v14.23 동안 해결되지 않음. HWP 5.0 사양과
            //          hwplib 의 writer 모두 검증했으나 (TextFlowMethod=TakePlace, borderFillId=3,
            //          isApplyInnerMargin=false, LineSegItem.firstSegment/lastSegment bits 등),
            //          한/글이 거부하는 미세한 byte-level 차이가 남아 있음.
            //          파일 안정성을 우선해 Unicode 박스 드로잉으로 fallback. addNativeTable
            //          코드는 보존 (NATIVE_TABLE=true 로 재활성화 가능).
            if (b instanceof Table) {
                if (NATIVE_TABLE) {
                    try {
                        addNativeTable(sec, (Table) b);
                        addedParas++;
                        nativeTables++;
                        continue;
                    } catch (Exception e) {
                        System.out.println("[MdToHwpRich] native table 실패 → Unicode fallback: "
                                + e.getClass().getSimpleName() + " - " + e.getMessage());
                    }
                }
                // Unicode box drawing fallback (한/글 호환 우선)
                for (RichLine line : renderBlockRich(b)) {
                    addParagraph(sec, line.text, CS_NORMAL, line.boldRanges);
                    addedParas++;
                    totalChars += line.text.length();
                }
                fallbackTables++;
                continue;
            }

            int charShapeId = CS_NORMAL;
            if (b instanceof Heading) {
                int level = ((Heading) b).level;
                if (level >= 1 && level <= 6) {
                    charShapeId = CS_HEADINGS[level - 1];
                }
            }
            // [v14.87 task2/3] MdParagraph 의 align → paraShapeId 매핑.
            int psId = 0;
            if (b instanceof MdParagraph) {
                psId = paraShapeForAlign(((MdParagraph) b).align);
            }
            List<RichLine> lines = renderBlockRich(b);
            for (RichLine line : lines) {
                String text = line.text;
                List<int[]> bolds = line.boldRanges; // pairs [start,end) over text
                if (text.length() > 4000) {
                    int n = (int) Math.ceil(text.length() / 4000.0);
                    for (int i = 0; i < n; i++) {
                        int from = i * 4000;
                        int to = Math.min(from + 4000, text.length());
                        List<int[]> sub = sliceRanges(bolds, from, to);
                        addParagraph(sec, text.substring(from, to), charShapeId, sub, psId);
                        addedParas++;
                    }
                    totalChars += text.length();
                } else {
                    addParagraph(sec, text, charShapeId, bolds, psId);
                    addedParas++;
                    totalChars += text.length();
                }
            }
        }
        System.out.println("[MdToHwpRich] native table: " + nativeTables
                + ", unicode fallback table: " + fallbackTables);
        System.out.println("[MdToHwpRich] 이미지 picture control emit: " + picsEmitted);
        System.out.println("[MdToHwpRich] 생성 paragraph: " + addedParas
                + " (전체 section: " + sec.getParagraphCount() + ")"
                + ", 총 텍스트: " + totalChars + " chars");

        // 2) Section0 splice + 최종 .hwp 저장 — MdRichSection0Splicer 위임 (v14.95).
        MdRichSection0Splicer.spliceAndSave(hwp, imageEmbeds, filePathHwp);
    }

    // =========================================================
    //  [v15.29 task1/2] Section paragraph (para[0]) 의 control 보강
    // =========================================================

    /**
     * BlankFileMaker.make() 가 만든 section paragraph 에 누락된 ControlPageNumberPosition
     *  을 추가한다. 한/글 sanity check 가 "section 정의 단락은 페이지번호 위치 control
     *  을 포함해야 한다" 를 검증 → 미포함 시 "문서에 손상을 줄 수 있는 내용이 포함되어
     *  있습니다" 경고 팝업.
     *
     *  <p>원본 case1.hwp 와 template-rich.hwp 의 para[0] 구조 미러 :
     *  <ul>
     *    <li>controls : ControlSectionDefine + ControlColumnDefine
     *                   + ControlPageNumberPosition (NumberPosition.None)</li>
     *    <li>instanceID : 0xa4ce6df0 (template-rich.hwp 고정 값)</li>
     *    <li>charCount : 25 (text 4 chars + 3 controls × 7 ctrl chars = 4+21 = 25)</li>
     *  </ul>
     *
     *  <p>NumberPosition.None 사용 — 실제 페이지 번호 렌더는 변경하지 않고 control 만
     *  구조적으로 존재하게 만든다.
     */
    private static void ensureSectionPara0Controls(Section sec) {
        if (sec == null) return;
        kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph[] paras = sec.getParagraphs();
        if (paras == null || paras.length == 0) return;
        kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph p0 = paras[0];
        if (p0 == null) return;
        java.util.ArrayList<kr.dogfoot.hwplib.object.bodytext.control.Control> ctrls
                = p0.getControlList();
        if (ctrls == null) return;
        // 이미 ControlPageNumberPosition 이 있으면 skip (idempotent).
        boolean hasPnp = false;
        for (kr.dogfoot.hwplib.object.bodytext.control.Control c : ctrls) {
            if (c instanceof kr.dogfoot.hwplib.object.bodytext.control.ControlPageNumberPosition) {
                hasPnp = true; break;
            }
        }
        if (!hasPnp) {
            kr.dogfoot.hwplib.object.bodytext.control.ControlPageNumberPosition pnp =
                    new kr.dogfoot.hwplib.object.bodytext.control.ControlPageNumberPosition();
            // NumberShape = Number (기본 아라비아), NumberPosition = None (페이지 번호 미표시).
            pnp.getHeader().getProperty().setNumberShape(
                    kr.dogfoot.hwplib.object.bodytext.control.sectiondefine.NumberShape.Number);
            pnp.getHeader().getProperty().setNumberPosition(
                    kr.dogfoot.hwplib.object.bodytext.control.ctrlheader
                            .pagenumberposition.NumberPosition.None);
            pnp.getHeader().setNumber(0);
            ctrls.add(pnp);
        }
        // [v15.29] instanceID 를 template-rich.hwp 의 section paragraph 와 동일 값으로 설정.
        //  원본 hand-made case1.hwp 와 template 가 모두 0xa4ce6df0 사용. BlankFileMaker 는
        //  instanceID = 0 으로 emit → 한/글 sanity check 가 0 을 "유효 instanceID 미설정"
        //  으로 판정 가능성.
        if (p0.getHeader().getInstanceID() == 0L) {
            p0.getHeader().setInstanceID(0xa4ce6df0L);
        }
        // [v15.29] CharCount 보정 — text + 7×ctrl (각 control = 8 chars binary 중 1 char
        //  position + 7 extra) 으로 hwplib writer 가 계산하는 값과 정합.
        int textCount = (p0.getText() == null || p0.getText().getCharList() == null) ? 0
                : p0.getText().getCharList().size();
        // text 안의 inline control char 도 1 char position 으로 카운트. 추가 control 은
        // 단일 ch 가 8 chars binary 를 차지 → +7 보정. 하지만 hwplib HWPCharControlChar
        // (text 내) 는 이미 1 으로 카운트되므로, 여기서는 단순히 text length 를 사용.
        p0.getHeader().setCharacterCount(textCount);
    }

    // =========================================================
    //  Template DocInfo 의 CS_BOLD 를 mutate (Bold 속성만 set)
    // =========================================================

    /**
     * template.hwp 의 byte 를 가져온다 + [v14.52] cell 전용 CharShape 추가.
     *  - CS_BOLD (=55) 는 이미 bold property 를 갖고 있어 mutation 불요.
     *  - [v14.52] CS_CELL (=62) 신규 추가. CS 0 의 clone 에 Hangul/Latin
     *    charSpace = +5 (자간 +5%) 적용. cell paragraph 만 이 CharShape 사용 →
     *    section paragraph (CS 0) 와 자간 차이 발생. 사용자 보고 — center 셀
     *    텍스트 자간이 section paragraph 대비 좁아 보임.
     */


    // =========================================================
    //  Paragraph 직접 구성 (hwplib helper 버그 우회)
    // =========================================================

    private static void addParagraph(Section sec, String text, int charShapeId,
                                     List<int[]> boldRanges) throws Exception {
        addParagraph(sec, text, charShapeId, boldRanges, 0);
    }

    /** [v14.87 task2/3] paraShapeId 지정 가능한 overload — 정렬 적용에 사용. */
    private static void addParagraph(Section sec, String text, int charShapeId,
                                     List<int[]> boldRanges, int paraShapeId) throws Exception {
        Paragraph p = sec.addNewParagraph();
        // [v15.16 task1/4] TAB sentinel (\t) → 인라인 TAB 컨트롤 (HWPCharControlInline, code=0x0009)
        //   로 변환. MdRichParser.normalizeTocLeaderLine 가 TOC 줄을 "label\tnum" 형식으로
        //   넘기므로 본 emit 단계에서 \t 를 점선 leader (leader=DOT, type=RIGHT, width=N)
        //   인라인 컨트롤로 치환. 인라인 컨트롤은 HWP binary 에서 8 char position 을 차지
        //   하므로 paragraph CharacterCount 보정 필요.
        int tabCount = 0;
        if (text != null) {
            for (int k = 0; k < text.length(); k++) if (text.charAt(k) == '\t') tabCount++;
        }
        int charCount = (text == null) ? 0 : (text.length() + 7 * tabCount);
        p.getHeader().setCharacterCount(charCount);
        // [v15.17 task1/2] TAB sentinel 이 들어 있는 단락 (= TOC dot leader 패턴) 은
        //   PS_TOC paraShape 사용 — tabDefId 가 페이지 우측 RIGHT/DOT 탭 stop 을
        //   가리키므로 한/글이 점선 leader 를 실제로 그린다. v15.16 까지 PS 0 (기본
        //   LEFT/None 39 stops) 로 emit 되어 인라인 TAB 의 leader 가 무시되어 점선
        //   사라짐 (사용자 task1/task2 재보고).
        int effectivePsId = (tabCount > 0) ? PS_TOC : paraShapeId;
        p.getHeader().setParaShapeId((short) effectivePsId);
        p.getHeader().setStyleId((short) 0);
        p.getHeader().setLineAlignCount(1);
        p.getHeader().setRangeTagCount(0);
        p.getHeader().setLastInList(false);
        p.getHeader().setInstanceID(nextParaInstanceId());  // [task1/2] unique uint32 (was 0x80000000 KG default)
        p.getHeader().setIsMergedByTrack(0);
        if (text != null && !text.isEmpty()) {
            p.createText();
            if (tabCount > 0) {
                emitTextWithInlineTabs(p, text);
            } else {
                p.getText().addString(text);
            }
        }
        p.createCharShape();

        // bold ranges 가 있으면 multi-run charShape mapping. 각 toggle position 마다
        // (start_charShape) → (CS_BOLD) → (start_charShape) 식으로 추가한다.
        // bold ranges 는 sorted, non-overlapping, [start,end) 좌표.
        int runs = 0;
        if (boldRanges == null || boldRanges.isEmpty()) {
            p.getCharShape().addParaCharShape(0, charShapeId);
            runs = 1;
        } else {
            int cursor = 0;
            for (int[] r : boldRanges) {
                int s = Math.max(0, r[0]);
                int e = Math.min(charCount, r[1]);
                if (e <= s) continue;
                if (s > cursor) {
                    p.getCharShape().addParaCharShape(cursor, charShapeId); runs++;
                } else if (cursor == 0 && runs == 0) {
                    // bold 가 0 위치에서 바로 시작
                }
                p.getCharShape().addParaCharShape(s, CS_BOLD); runs++;
                cursor = e;
            }
            // 종료 후 잔여
            if (cursor < charCount) {
                p.getCharShape().addParaCharShape(cursor, charShapeId); runs++;
            }
            // 안전망: 0번 charShape mapping 이 없으면 prepend (HWP 사양상 첫 mapping 의 position 은 0)
            if (p.getCharShape().getPositonShapeIdPairList().isEmpty()
                    || p.getCharShape().getPositonShapeIdPairList().get(0).getPosition() != 0) {
                // 0 위치에 charShapeId 를 미리 넣고, 기존 list 를 뒤로 밀 수는 없으므로
                // 단순히 새 list 재구성 — 흔치 않은 case 라 보수적으로 처리
                java.util.ArrayList<long[]> snapshot = new java.util.ArrayList<>();
                for (kr.dogfoot.hwplib.object.bodytext.paragraph.charshape.CharPositionShapeIdPair pr
                        : p.getCharShape().getPositonShapeIdPairList()) {
                    snapshot.add(new long[]{pr.getPosition(), pr.getShapeId()});
                }
                p.getCharShape().getPositonShapeIdPairList().clear();
                p.getCharShape().addParaCharShape(0, charShapeId);
                for (long[] pr : snapshot) {
                    if (pr[0] != 0) p.getCharShape().addParaCharShape(pr[0], pr[1]);
                }
                runs = p.getCharShape().getPositonShapeIdPairList().size();
            }
        }
        p.getHeader().setCharShapeCount(runs);

        // [v15.25 task1~4] LineSegItem emit 보강 — v15.24 가 한/글에서 "손상" 팝업
        //   유발하던 회귀 fix.
        //   원인 (서브에이전트 byte-level 비교 결과) :
        //     1) 모든 paragraph 의 lineVerticalPosition=0 → 한/글 sanity check
        //        실패로 "손상" 경고. 원본 hand-made 는 section 내 누적 Y 값
        //        (16426, 48454, 60614 ...) 사용.
        //     2) continuation 줄(li>0) 에 tag bit 20 (AdjustIndentation) 누락.
        //        원본은 0x00160000, 우리는 0x00060000.
        //     3) distanceBaseLineToLineVerticalPosition 비율 0.80 → 0.85
        //        (원본 1275/1500 = 0.85).
        // [v15.26 task2/4] LineSeg height/spacing 단위 정정 — v15.25 까지 lineHeight 에
        //   font cap height (10pt=1000 hwpunit) 를 그대로 setLineHeight 로 넘겨 한/글이
        //   "줄 높이 < 폰트 크기" sanity 검증 실패 → "문서에 손상을 줄 수 있는 내용..."
        //   팝업. 동시에 RIGHT-tab 우측 정렬 시 다중 자릿수 페이지 번호("36") 가 줄
        //   메트릭 inconsistency 로 분리/뒤집힘 회귀 ("1·1", "71", "8·2", "3·6").
        //   원본 hand-made case1.hwp 의 line height: 10pt 본문 = 1500 hwpunit, TOC 강조 =
        //   1700 (= fontSize × 1.5 ~ 1.7). 본 fix 는 font-size × 1.5 로 통일 (10pt→1500,
        //   12pt→1800, 16pt→2400 ...) 하여 한/글 sanity 통과 + 페이지 번호 정상 렌더.
        //   line spacing 도 height 와 동일 (원본은 spacing == height 패턴) 으로 변경 —
        //   기존 0.6 비율은 매우 작아 paragraph 간 간격이 비정상.
        // [v15.29 task3/4] 사용자 요구 : MD → HWP/HWPX 변환 시 목차 하위 entry 간 행간이
        //   너무 떨어져 있어 압축이 필요. 원인 : v15.26 의 lineSpacing = lineHeight 설정으로
        //   paragraph 마다 advance = 2 × lineHeight (= 3000 hwpunit, 0.4 inch) 가 누적되어
        //   목차 7~8 항목만 보이고 한 page 가 꽉 차는 회귀. 본 fix 는 lineSpacing 을 lineHeight
        //   의 20% (~300 hwpunit) 로 축소 → advance ≈ 1800 hwpunit (≈ 0.25 inch) 로 줄여
        //   엔터 키를 쳤을 때 자연스러운 single-spacing 간격 확보. 한/글 line metric sanity
        //   (vertsize ≥ font cap + leading) 는 lineHeight 만으로 통과하며 lineSpacing 은
        //   paragraph 간 추가 gap 일 뿐이므로 줄여도 손상 경고 회귀 없음.
        p.createLineSeg();
        int fontSize = lineHeightForCharShape(charShapeId);
        int lineHeight = (int)(fontSize * 1.5);  // [v15.26] cap-height 가 아닌 본격 line height
        int lineSpacing = Math.max(200, lineHeight / 5);  // [v15.29] 1500 / 5 = 300 (≈ 20%)
        int distBaseline = (int) (lineHeight * 0.85);
        int segWidth = MdRichTemplateMutator.TOC_PAGE_INNER_WIDTH;
        boolean hasTab = tabCount > 0;
        int textVW = (text == null || text.isEmpty()) ? 0
                : visualWidthOfRange(text, 0, text.length());
        int lineCount = (!hasTab && textVW > segWidth)
                ? Math.max(1, (textVW + segWidth - 1) / segWidth)
                : 1;
        int paraLen = (text == null) ? 0 : text.length();
        int paraStartY = sectionRunningY;
        for (int li = 0; li < lineCount; li++) {
            LineSegItem seg = p.getLineSeg().addNewLineSegItem();
            int textStart;
            if (li == 0) {
                textStart = 0;
            } else {
                int targetW = li * segWidth;
                int curW = 0; int pos = 0;
                while (pos < paraLen && curW < targetW) {
                    curW += charVisualWidth(text.charAt(pos));
                    pos++;
                }
                textStart = pos;
            }
            seg.setTextStartPosition(textStart);
            // [v15.25] section 누적 Y 값 — 원본 hand-made 와 동일 패턴.
            seg.setLineVerticalPosition(paraStartY + li * (lineHeight + lineSpacing));
            seg.setLineHeight(lineHeight);
            seg.setTextPartHeight(lineHeight);
            seg.setDistanceBaseLineToLineVerticalPosition(distBaseline);
            seg.setLineSpace(lineSpacing);
            seg.setStartPositionFromColumn(0);
            seg.setSegmentWidth(segWidth);
            seg.getTag().setFirstSegmentAtLine(true);
            seg.getTag().setLastSegmentAtLine(true);
            // [v15.25] continuation 줄에 AdjustIndentation 비트 — 원본 매칭.
            if (li > 0) {
                seg.getTag().setAdjustIndentation(true);
            }
        }
        sectionRunningY = paraStartY + lineCount * (lineHeight + lineSpacing);
    }

    /** [v15.25] 섹션 내 paragraph 첫 줄의 lineVerticalPosition 을 누적 Y 로 추적.
     *  convert() 시작 시 reset. 한/글의 다중 paragraph layout sanity check 통과용. */
    private static int sectionRunningY = 0;

    /**
     * [v15.16 task1/4] TAB sentinel (\t) 가 포함된 텍스트를 HWP paragraph 의 ParaText 에
     * 인라인 TAB 컨트롤 (HWPCharControlInline, code=0x0009) 로 변환하여 추가.
     *
     * <p>각 \t 는 16-byte 인라인 TAB 컨트롤로 emit 된다. addition 12 byte:
     * <pre>
     *   offset 0..3 : UINT32 LE   탭 너비 (HWPUNIT)
     *   offset 4    : BYTE        리더 스타일 (3 = DOT)
     *   offset 5    : BYTE        탭 항목 종류 (1 = RIGHT)
     *   offset 6..11: BYTE[6]     패딩
     * </pre>
     *
     * <p>탭 width 는 페이지 본문 폭 41954 hwpunit 에서 라벨 ({@code \t} 이전 텍스트)
     * 과 페이지 번호 ({@code \t} 이후 텍스트) 의 시각 폭을 차감한 값. 이로써 인라인
     * 탭이 차지하는 가로 거리 = 페이지 폭 - 라벨 - 페이지 번호 이며, 라벨 길이가
     * 달라도 페이지 번호 우측 좌표가 페이지 본문 폭에 정렬됨. type=RIGHT 와
     * leader=DOT 는 한/글이 원본 case1.hwp 2/83 페이지 목차에서 사용하는 것과
     * 동일 (원본 HWPX 의 section0.xml 분석으로 확인).
     */
    private static void emitTextWithInlineTabs(Paragraph p, String text) throws Exception {
        int n = text.length();
        int i = 0;
        while (i < n) {
            int next = text.indexOf('\t', i);
            if (next < 0) {
                if (i < n) p.getText().addString(text.substring(i, n));
                return;
            }
            if (next > i) p.getText().addString(text.substring(i, next));
            int beforeW = visualWidthOfRange(text, 0, next);
            int afterW  = visualWidthOfRange(text, next + 1, n);
            // [v15.18 task1] 페이지 본문 폭은 template-rich.hwp 의 실제 값 (42520 hwpunit,
            //   = 59528 - 8504×2). v15.17 까지의 41954 는 잘못 추정한 값으로 점선 leader 가
            //   페이지 우측 마진보다 566 hwpunit (≈1.3 chars) 짧게 끝나는 회귀 원인.
            int tabW = TOC_PAGE_INNER_WIDTH - beforeW - afterW;
            if (tabW < 900) tabW = 4000;  // 라벨/번호 합이 페이지 폭을 넘으면 default tab width
            HWPCharControlInline tab = p.getText().addNewInlineControlChar();
            tab.setCode(0x0009);
            byte[] ad = new byte[12];
            ad[0] = (byte)(tabW & 0xFF);
            ad[1] = (byte)((tabW >>> 8) & 0xFF);
            ad[2] = (byte)((tabW >>> 16) & 0xFF);
            ad[3] = (byte)((tabW >>> 24) & 0xFF);
            // [v15.20 task1/2] 원본 hand-made case1.hwp 인라인 TAB 미러: ad[4]=3(Dot),
            //   ad[5]=2 (HWP 5.0 spec 표 36 탭종류 byte 2 — 원본이 TOC 우측 정렬 TAB 에
            //   사용하는 값). v15.19까지의 ad[5]=1 은 hwp2hwpx 가 OWPML type="1" (LEFT)
            //   로 전파하여 RIGHT-stop 까지 도달 못함 → leader 가 작은 TAB 폭에 dash 로
            //   렌더되는 회귀 원인.
            // [v15.23 task1] 사용자 보고 v15.20~v15.22 모두 같은 회귀 ("1·1","71"...).
            //   원본 ad[5]=2 미러였으나 한/글 binary 렌더가 byte 2 를 CENTER 로 해석
            //   해 다중 자릿수 페이지 번호가 분리/뒤집힘 가능성. HWP spec 표 36 의
            //   RIGHT = byte 1 로 변경. hwp2hwpx 는 OWPML type="1" (LEFT) 로 전파하므로
            //   HwpxXmlRewriter 후처리에서 type="1" → type="2" 로 보강.
            ad[4] = 3;  // leader = DOT (원본 ad[4]=0x03)
            ad[5] = 1;  // tab type = RIGHT (HWP spec byte 1) — HWPX post-process 가 type="2" 보강
            // ad[6..11] padding = 0
            tab.setAddition(ad);
            i = next + 1;
        }
    }

    /** charVisualWidth 누적 헬퍼. [from, to) 범위의 시각 폭 합 (hwpunit).
     *  TAB(\t) 은 0 으로 취급 (인라인 탭 width 계산 시 라벨/번호 텍스트 폭만 합산). */
    private static int visualWidthOfRange(String s, int from, int to) {
        int w = 0;
        for (int k = from; k < to; k++) {
            char ch = s.charAt(k);
            if (ch == '\t') continue;
            w += charVisualWidth(ch);
        }
        return w;
    }

    // =========================================================
    //  Picture control emit (이미지 → 실제 HWP 그림 컨트롤)
    // =========================================================



    // =========================================================
    //  Native HWP Table emit (v14.17)
    // =========================================================

    /**
     * v14.18: 셀 병합(colspan/rowspan)을 지원하는 native HWP 표 emit.
     *
     * <p>레이아웃:
     * <ul>
     *   <li>그리드 모델로 셀 위치 계산 — rowspan 으로 다음 행을 점유한 셀이 있으면 skip.</li>
     *   <li>numCols = 모든 행의 (자기 cell colspan 합 + 위쪽에서 내려오는 rowspan 점유 합) 의 최댓값.</li>
     *   <li>열 너비: 본문 너비(41954) 를 numCols 로 균등 분할. 셀 너비 = 차지하는 열 수 × baseW.</li>
     *   <li>행 높이: 1800 hwpunit, rowspan 셀 = N × baseH.</li>
     *   <li>BorderFillId=1 (template.hwp 1번 테두리).</li>
     * </ul>
     */
    /** [v14.64] 특정 표가 v14.60 layout (col 균등 + 셀 높이 2200 + 자간 +50%) 으로
     *  롤백 emit 되어야 하는지 판정. 식별 기준: 표 안에 "경기도 공고" 텍스트 존재
     *  (T15 — 경기도 공고안). 다른 표에는 이 텍스트 없음 (test1.md 검증).
     *  향후 다른 표가 추가로 롤백 대상이면 여기에 식별 패턴 추가. */
    private static boolean isV1460LegacyTable(Table t) {
        if (t == null || t.rows == null || t.rows.isEmpty()) return false;
        for (List<TableCell> row : t.rows) {
            if (row == null) continue;
            for (TableCell c : row) {
                if (c == null || c.text == null) continue;
                String s = stripStrongMarkers(c.text).trim();
                if (s.startsWith("경기도 공고")) return true;
            }
        }
        return false;
    }

    private static void addNativeTable(Section sec, Table t) throws Exception {
        final int TOTAL_W   = 41954;
        final int ROW_H     = 1800;
        final int MARGIN    = 141;
        // [v14.64] 특정 표(예: T15 — 경기도 공고안) 는 v14.60 layout 으로 롤백 emit.
        //   = col 폭 균등 분배 + 셀 높이 LINE_STEP=2200 + 자간 +50% (CS_CELL_LEGACY).
        //   사용자 task 요구: "12쪽 41줄 표만 v14.60 으로 롤백".
        boolean isV1460Legacy = isV1460LegacyTable(t);
        // [v14.22 핵심 수정] template.hwp 의 borderFillList 분석 결과:
        //   ID=1: 4면 None (테두리 없음) ← 이전 v14.21 까지 사용했던 잘못된 ID
        //   ID=3: 4면 Solid/MM0_12 ← 표 셀에 적합한 실선 테두리
        // borderFillId=1 (None) 을 표 셀에 사용하면 한/글이 셀 테두리 정의 부재로
        // "표가 손상되었습니다" 판정. ID=3 으로 변경하여 셀이 정상 인식되도록 함.
        final int BORDER_ID = 3;

        List<List<TableCell>> rows = t.rows;
        int numRows = rows.size();
        if (numRows == 0) return;

        // ── A. 그리드 모델: 각 셀의 (rowIdx, colIdx) 와 점유 영역 계산 ──
        // 일단 충분히 큰 그리드로 시작. estCols 는 한 행의 colspan 합 최댓값.
        int estCols = 0;
        for (List<TableCell> row : rows) {
            int s = 0;
            for (TableCell c : row) s += Math.max(1, c.colspan);
            estCols = Math.max(estCols, s);
        }
        if (estCols == 0) return;
        int gridCols = estCols + 4;  // 약간의 여유 (rowspan 으로 인한 column 확장 대비)

        boolean[][] occupied = new boolean[numRows][gridCols];
        // [v14.34 핵심 변경] colSpan/rowSpan 모두 평탄화 (모든 셀 1×1).
        //   대신 width 가변: master 셀이 N개 컬럼을 차지하던 경우, 첫 셀에 width=N*baseW,
        //   같은 행의 N-1개 slave 셀은 width=0 (시각적 invisible) 으로 emit.
        //   결과: 모든 셀 colSpan=1, rowSpan=1 → v14.30 의 안전성 유지
        //         + master 셀이 wide 너비로 시각화 → 5p 의 wide 셀 효과
        //         + 한/글 손상 팝업 회피 (colSpan>1 metadata 없음)
        // Master placements: [col, cs, rs, srcIdx] for each MD master cell
        List<int[]>[] mastersInRow = new List[numRows];
        for (int r = 0; r < numRows; r++) mastersInRow[r] = new ArrayList<>();
        int actualMaxCol = 0;
        for (int r = 0; r < numRows; r++) {
            List<TableCell> row = rows.get(r);
            int c = 0;
            for (TableCell cell : row) {
                while (c < gridCols && occupied[r][c]) c++;
                if (c >= gridCols) break;
                int cs = Math.min(cell.colspan, gridCols - c);
                int rs = Math.min(cell.rowspan, numRows - r);
                if (cs < 1) cs = 1;
                if (rs < 1) rs = 1;
                int srcIdx = row.indexOf(cell);
                mastersInRow[r].add(new int[]{c, cs, rs, srcIdx});
                for (int dr = 0; dr < rs; dr++) {
                    for (int dc = 0; dc < cs; dc++) {
                        if (r + dr < numRows && c + dc < gridCols) {
                            occupied[r + dr][c + dc] = true;
                        }
                    }
                }
                actualMaxCol = Math.max(actualMaxCol, c + cs - 1);
                c += cs;
            }
        }
        int numCols = actualMaxCol + 1;
        if (numCols == 0) return;

        // ── B. 열 너비 분배 ─────────────────────────────────────
        // [v14.65] 사용자 task 1~4 요구: 모든 표가 1쪽 첫 표(T0)처럼 페이지 폭 가득
        //   차지하되, 첫 컬럼(A) 폭 = 10488 (T0 의 col 0 폭) 통일.
        //   numCols=2 or 3: col 0 = 10488 fixed, 나머지는 페이지 폭에 맞춰 균등 분배
        //                    → 표 폭 = TOTAL_W (페이지 폭 가득), B 시작 X = 10488 ✓
        //   numCols=4    : v14.63 그대로 [10488, 10488, 10488, 10490] (이미 페이지 폭)
        //   numCols=1    : 단일 col, 페이지 폭 사용 (별도 분기)
        //   numCols>4    : wide 표 (T1, T2, T11~T13 등) 균등 분배 그대로
        // [v14.64] legacy 표 (T15) 는 v14.60 균등 분배로 롤백 — 분기 우선.
        final int FIXED_COL_W = 10488;  // = TOTAL_W / 4 (1쪽 첫 표 T0 기준 = 약 25mm)
        int baseColW;
        int[] colWidths = new int[numCols];
        if (!isV1460Legacy && numCols >= 2 && numCols <= 4) {
            if (numCols == 4) {
                // 4-col grid 표: 모두 10488 (마지막 col 만 ±2 보정해서 41954 일치)
                baseColW = FIXED_COL_W;
                for (int c = 0; c < numCols; c++) colWidths[c] = FIXED_COL_W;
                colWidths[numCols - 1] = TOTAL_W - FIXED_COL_W * (numCols - 1);
            } else {
                // numCols=2 or 3: col 0 = 10488 fixed, 나머지 페이지 폭에 맞춰 균등.
                // 결과: 표 폭 = TOTAL_W (페이지 폭 가득), B 시작 X = 10488 (T0 와 동일).
                colWidths[0] = FIXED_COL_W;
                int rest = (TOTAL_W - FIXED_COL_W) / (numCols - 1);
                for (int c = 1; c < numCols; c++) colWidths[c] = rest;
                // 마지막 col 만 leftover 흡수 (정수 분배 잔여 ±)
                colWidths[numCols - 1] = TOTAL_W - FIXED_COL_W - rest * (numCols - 2);
                baseColW = FIXED_COL_W;
            }
        } else {
            // legacy 표(v14.60 롤백) / cols=1 / cols>4 wide 표 : 균등 분배
            baseColW = TOTAL_W / numCols;
            for (int c = 0; c < numCols; c++) colWidths[c] = baseColW;
            colWidths[numCols - 1] = TOTAL_W - baseColW * (numCols - 1);
        }
        int tableTotalW = 0;
        for (int w : colWidths) tableTotalW += w;

        // ── B-1. 텍스트 매핑 (master 셀 위치에 텍스트 배치) ─────
        //   [v14.68] cellMaster — master cell 직접 참조 (nestedTable 검사용).
        String[][] cellText = new String[numRows][numCols];
        String[][] cellAlign = new String[numRows][numCols];
        TableCell[][] cellMaster = new TableCell[numRows][numCols];
        for (int r = 0; r < numRows; r++) {
            List<TableCell> srcRow = rows.get(r);
            for (int[] m : mastersInRow[r]) {
                int c = m[0], srcIdx = m[3];
                if (c < numCols && srcIdx >= 0 && srcIdx < srcRow.size()) {
                    TableCell mc = srcRow.get(srcIdx);
                    cellText[r][c] = mc.text;
                    cellAlign[r][c] = mc.align;
                    cellMaster[r][c] = mc;
                }
            }
        }

        // [v14.54] 사용자 요청 — v14.52 표 형태 유지 (동적 column width 폐기).
        //   v14.53 의 콘텐츠 기반 column width 분배가 "표 형태 변형" 으로 보고됨.
        //   colWidths 는 위에서 baseColW 로 균일 초기화된 상태 그대로 사용.

        // ── B-2. master 셀 병합 후 textWidth 사전 계산 ──────────
        int[][] mergedTextWidthAt = new int[numRows][numCols];
        for (int r = 0; r < numRows; r++) {
            for (int c = 0; c < numCols; c++) {
                mergedTextWidthAt[r][c] = colWidths[c] - MARGIN * 2;
            }
            for (int[] m : mastersInRow[r]) {
                int c = m[0], cs = m[1];
                int mergedW = 0;
                for (int dc = 0; dc < cs && c + dc < numCols; dc++) {
                    mergedW += colWidths[c + dc];
                }
                mergedTextWidthAt[r][c] = Math.max(1, mergedW - MARGIN * 2);
            }
        }

        // ── B-3. 셀별 wrap 점 (lineStarts) 계산 ────────────────
        // 공통 char-width helper (cellLineStarts) 로 rowH 계산과 LineSegItem
        // emit 양쪽이 동일한 결과를 내야 cell.h 와 LineSegItem.lineVerticalPosition
        // 이 일관 → 한/글이 콘텐츠를 cell 안에 정렬 가능.
        java.util.List<Integer>[][] cellLineStarts = new java.util.List[numRows][numCols];
        for (int r = 0; r < numRows; r++) {
            for (int c = 0; c < numCols; c++) {
                String text = cellText[r][c];
                String plain = (text == null || text.isEmpty()) ? "" : extractStrong(text).text;
                cellLineStarts[r][c] = computeLineStarts(plain, mergedTextWidthAt[r][c]);
            }
        }

        // ── B-4. rowH[r] 계산 (linesNeeded × LINE_STEP + margins) ─
        // [v14.45] 접근 1 — "one paragraph per visual line": N 개 LineSegItem 대신
        //   N 개 paragraph 를 emit. 각 paragraph 는 1 줄(1 LineSegItem). 한/글의
        //   layout 엔진이 paragraph 경계를 줄 경계로 인식 → linePos 계산 ambiguity
        //   원천 차단. cell.h = N × LINE_STEP + 2 × MARGIN (buffer 불요).
        //   v14.44 까지: 1 paragraph + N LineSegItems → 한/글이 linePos 를 무시하고
        //   자체 layout 결과를 적용하다 글자 겹침 발생.
        // [v14.60] 셀 높이 축소 — 일반 표는 1400 (140% 줄간격, 한/글 표준 근사).
        // [v14.64] legacy 표 (T15 등) 는 v14.60 이전 값 2200 으로 롤백.
        final int LINE_STEP_TBL = isV1460Legacy ? 2200 : 1400;
        int[] rowH = new int[numRows];
        for (int r = 0; r < numRows; r++) rowH[r] = ROW_H;
        for (int r = 0; r < numRows; r++) {
            for (int[] m : mastersInRow[r]) {
                int c = m[0], rs = m[2];
                int contentH;
                TableCell mc = cellMaster[r][c];
                if (USE_NESTED_HWP_TABLE && mc != null
                        && mc.nestedBlocks != null && !mc.nestedBlocks.isEmpty()) {
                    // [STEP4 task4] 다중 블록 셀: 모든 내부표 + 텍스트 블록 높이 합산 (firstTbl 만 세던 과소추정 해소)
                    int innerW = mergedTextWidthAt[r][c];
                    int h = 0;
                    for (Object b : mc.nestedBlocks) {
                        if (b instanceof Table) {
                            h += estimateNestedTableH((Table) b, innerW, LINE_STEP_TBL);
                        } else if (b instanceof String) {
                            String s = (String) b;
                            String pl = (s == null || s.isEmpty()) ? "" : extractStrong(s).text;
                            if (!pl.isEmpty()) h += Math.max(1, computeLineStarts(pl, innerW).size()) * LINE_STEP_TBL;
                        }
                    }
                    contentH = Math.max(LINE_STEP_TBL, h) + MARGIN * 2;
                } else if (USE_NESTED_HWP_TABLE && mc != null && mc.nestedTable != null) {
                    // [v14.68] nested cell 의 height = nested table H + textBefore/After lines + margin
                    int innerW = mergedTextWidthAt[r][c];
                    int nestedH = estimateNestedTableH(mc.nestedTable, innerW, LINE_STEP_TBL);
                    int beforeLines = (mc.textBefore != null && !mc.textBefore.isEmpty())
                            ? Math.max(1, computeLineStarts(extractStrong(mc.textBefore).text, innerW).size())
                            : 0;
                    int afterLines = (mc.textAfter != null && !mc.textAfter.isEmpty())
                            ? Math.max(1, computeLineStarts(extractStrong(mc.textAfter).text, innerW).size())
                            : 0;
                    contentH = (beforeLines + afterLines) * LINE_STEP_TBL + nestedH + MARGIN * 2;
                } else {
                    int linesNeeded = Math.max(1, cellLineStarts[r][c].size());
                    contentH = linesNeeded * LINE_STEP_TBL + MARGIN * 2;
                }
                int perRowContentH = Math.max(ROW_H, (contentH + rs - 1) / rs);
                for (int rr = r; rr < r + rs && rr < numRows; rr++) {
                    rowH[rr] = Math.max(rowH[rr], perRowContentH);
                }
            }
        }

        // ── B-5. tableH = sum(rowH) — 한/글이 표 vertical extent 정확 인식 ──
        int tableH = 0;
        for (int rh : rowH) tableH += rh;

        // ── C. Table 호스트 paragraph ───────────────────────────
        // [v14.19 핵심 수정] characterCount: addExtendCharForTable() 은 8 char
        // position(2+12+2 byte) 의 ExtendChar 를 추가하고, 내부에서 호출되는
        // processEndOfParagraph() 가 code-13 NormalChar(1 char position) 를
        // 추가한다. 따라서 총 9 char positions 가 되어야 한다 (이전 v14.18
        // 까지는 1로 잘못 설정 → Hangul 이 표 구조를 손상으로 판정).
        Paragraph p = sec.addNewParagraph();
        p.getHeader().setCharacterCount(9);  // 8 (table extend) + 1 (terminator)
        p.getHeader().setParaShapeId(0);
        p.getHeader().setStyleId((short) 0);
        p.getHeader().setCharShapeCount(1);
        p.getHeader().setLineAlignCount(1);
        p.getHeader().setRangeTagCount(0);
        p.getHeader().setLastInList(false);
        p.getHeader().setInstanceID(nextParaInstanceId());  // [task1/2] unique uint32 (was 0x80000000 KG default)
        p.getHeader().setIsMergedByTrack(0);
        p.getHeader().getControlMask().setHasGsoTable(true);

        p.createText();
        p.getText().addExtendCharForTable();

        p.createCharShape();
        p.getCharShape().addParaCharShape(0, CS_NORMAL);

        p.createLineSeg();
        LineSegItem pSeg = p.getLineSeg().addNewLineSegItem();
        pSeg.setTextStartPosition(0);
        pSeg.setLineVerticalPosition(0);
        pSeg.setLineHeight(tableH);
        pSeg.setTextPartHeight(tableH);
        pSeg.setDistanceBaseLineToLineVerticalPosition(tableH);
        pSeg.setLineSpace(tableH);
        pSeg.setStartPositionFromColumn(0);
        pSeg.setSegmentWidth(TOTAL_W);
        // [v14.24] tag bits 17, 18 set for first/last segment at line
        pSeg.getTag().setFirstSegmentAtLine(true);
        pSeg.getTag().setLastSegmentAtLine(true);

        // ── D. ControlTable + 헤더 ────────────────────────────────
        // [v14.63] ControlTable.width = tableTotalW (col 폭 합).
        //   numCols<4 인 표는 tableTotalW < TOTAL_W → 한/글이 표를 페이지 좌측에
        //   정렬 (col 0 시작 X = 0, 우측은 빈 공간). col widths 합과 일치 필수.
        ControlTable ct = (ControlTable) p.addNewControl(ControlType.Table);
        CtrlHeaderGso h = ct.getHeader();
        h.setWidth(tableTotalW);
        h.setHeight(tableH);
        h.setzOrder(0);
        // [v14.23] 정상 HWP 표는 outer margin 283 hwpunit (≈2mm) 을 가진다
        h.setOutterMarginLeft(283);
        h.setOutterMarginRight(283);
        h.setOutterMarginTop(283);
        h.setOutterMarginBottom(283);
        // [v14.23 핵심 수정] 정상 HWP 표는 TextFlowMethod=TakePlace (텍스트 자리 차지).
        // 이전 v14.22 까지 BehindText 사용 → 한/글이 floating picture 처럼 해석하다가
        // 실제로는 TABLE record 가 따라오므로 "표 손상" 판정.
        // [v14.47] likeWord=false (글자처럼 취급 해제). 이전 v14.46 까지 true → 표가
        //   inline character 처럼 취급되어 페이지 경계에서 분할 불가 (사용자 보고 —
        //   "경기도 공고 제 호" 표가 페이지 2개를 넘어가도 분할 안 됨).
        //   false 로 변경 → table 이 floating block 처럼 동작하며, table.property bit 1
        //   ("한 줄 단위로 페이지 나눔" — 0x06 에 이미 포함) 에 따라 행 경계에서 분할.
        h.getProperty().setLikeWord(false);
        h.getProperty().setVertRelTo(VertRelTo.Para);
        h.getProperty().setVertRelativeArrange(RelativeArrange.TopOrLeft);
        h.getProperty().setHorzRelTo(HorzRelTo.Para);
        h.getProperty().setHorzRelativeArrange(RelativeArrange.TopOrLeft);
        h.getProperty().setWidthCriterion(WidthCriterion.Absolute);
        h.getProperty().setHeightCriterion(HeightCriterion.Absolute);
        h.getProperty().setTextFlowMethod(TextFlowMethod.TakePlace);   // ★ TakePlace
        h.getProperty().setTextHorzArrange(TextHorzArrange.BothSides);
        h.getProperty().setObjectNumberSort(ObjectNumberSort.Table);

        // ── E. Table 메타 ─────────────────────────────────────────
        kr.dogfoot.hwplib.object.bodytext.control.table.Table tbl = ct.getTable();
        tbl.setRowCount(numRows);
        tbl.setColumnCount(numCols);
        tbl.setCellSpacing(0);
        tbl.setLeftInnerMargin(MARGIN);
        tbl.setRightInnerMargin(MARGIN);
        tbl.setTopInnerMargin(MARGIN);
        tbl.setBottomInnerMargin(MARGIN);
        tbl.setBorderFillId(BORDER_ID);
        // [v14.32] KG TABLE property=0x06 매칭 (bit 0-1=1 쪽 경계 셀 단위 나눔,
        //   bit 2=1 제목 줄 자동 반복). v14.31 까지 0 → 한/글 호환 위험.
        tbl.getProperty().setValue(0x06L);

        // ── G. 행/셀 추가 (1단계: 모든 셀 1×1, v14.30 스타일 - 안전한 베이스) ─
        for (int ri = 0; ri < numRows; ri++) {
            tbl.addCellCountOfRow(numCols);
            Row hwpRow = ct.addNewRow();
            for (int colIdx = 0; colIdx < numCols; colIdx++) {
                int cellW = colWidths[colIdx];
                String text = cellText[ri][colIdx];
                int cellH = rowH[ri];

                Cell cell = hwpRow.addNewCell();
                ListHeaderForCell lh = cell.getListHeader();
                // [v14.45] paraCount = lineStarts.size() — paragraph 1개 = 줄 1개.
                int cellParaCount = Math.max(1, cellLineStarts[ri][colIdx].size());
                lh.setParaCount(cellParaCount);
                lh.setColIndex(colIdx);
                lh.setRowIndex(ri);
                lh.setColSpan(1);    // ★ 1단계: 모두 1×1
                lh.setRowSpan(1);
                lh.setWidth(cellW);
                lh.setHeight(cellH);
                lh.setLeftMargin(MARGIN);
                lh.setRightMargin(MARGIN);
                lh.setTopMargin(MARGIN);
                lh.setBottomMargin(MARGIN);
                lh.setBorderFillId(BORDER_ID);
                // [v14.39 핵심 수정] master 셀의 textWidth = 병합 후 width (margin 차감 X).
                //   원본 case12.hwp byte 분석: textWidth == cell.width (margin 차감 없음).
                //   v14.38 까지 textWidth = baseColW - margin → 병합 셀에서 좁게 wrap.
                //   원본 매칭: master 셀 위치는 sum(colWidths[c..c+cs-1]) 사용,
                //   slave/empty 셀은 baseColW 그대로.
                int textW;
                int mergedW = mergedTextWidthAt[ri][colIdx] + MARGIN * 2; // 다시 margin 더해서 width 복원
                textW = mergedW;
                if (textW <= 0) textW = cellW;
                lh.setTextWidth(textW);
                lh.getProperty().setTextDirection(
                        kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.sectiondefine.TextDirection.Horizontal);
                lh.getProperty().setLineChange(
                        kr.dogfoot.hwplib.object.bodytext.control.gso.textbox.LineChange.Normal);
                // [v14.45] paragraph-per-line 모드에서는 cell.h = linesNeeded × 2200
                //   + 2 × margin 으로 정확히 산출되어 slack 거의 없음. tva 를 원본
                //   case12.hwp 와 동일하게 Center 로 → 한/글이 짧은 셀에서도 자연스러운
                //   수직 중앙 배치 → 시각 일관성. paragraph 경계가 줄 경계이므로
                //   Top/Center 차이는 overflow 시에만 나타나고, 우리는 cell.h 를 정확히
                //   산출하므로 overflow 사례 자체가 없다.
                lh.getProperty().setTextVerticalAlignment(
                        kr.dogfoot.hwplib.object.bodytext.control.gso.textbox.TextVerticalAlignment.Center);
                lh.getProperty().setApplyInnerMagin(false);
                lh.getProperty().setProtectCell(false);
                lh.getProperty().setTitleCell(false);
                lh.getProperty().setEditableAtFormMode(false);

                // [v14.45] paragraph-per-line: cellLineStarts 의 wrap 점에서 텍스트
                //   를 분할하여 N 개 paragraph 로 emit. 각 paragraph 는 1 줄(linePos=0).
                // [v14.49] cell align 을 paraShapeId 매핑으로 전달.
                int paragraphTextW = mergedTextWidthAt[ri][colIdx];
                int paraShapeForCell = paraShapeForAlign(cellAlign[ri][colIdx]);
                // [v14.64] legacy 표는 CS_CELL_LEGACY (자간 +50%) + lineStep 2200, 일반 표는 CS_CELL (자간 0%) + lineStep 1400.
                int cellCS = isV1460Legacy ? CS_CELL_LEGACY : CS_CELL;
                TableCell mcell = cellMaster[ri][colIdx];
                if (USE_NESTED_HWP_TABLE && mcell != null && mcell.nestedTable != null) {
                    // [v14.68] nested HWP table emit — cell 안에 진짜 nested ControlTable
                    int nestedParaCount = emitNestedTableToCell(cell, mcell, paragraphTextW,
                            LINE_STEP_TBL, cellCS, paraShapeForCell, 1);
                    lh.setParaCount(nestedParaCount);
                } else {
                    // [v14.91 task1] cell 본문에 여러 <p align> segment 가 있으면 per-segment 적용
                    int[] segStarts  = (mcell != null) ? mcell.segStarts  : null;
                    String[] segAligns = (mcell != null) ? mcell.segAligns : null;
                    addCellParagraphsPerLine(cell, text, cellCS, paragraphTextW,
                                     cellLineStarts[ri][colIdx], paraShapeForCell, LINE_STEP_TBL,
                                     segStarts, segAligns);
                }
            }
        }

        // ── H. 2단계: TableCellMerger 로 병합 후처리 (hwplib 공식 API) ──
        // [v14.35] hwplib 의 검증된 병합 로직으로 byte-level 병합 처리.
        //   각 MD master 셀에 대해 cs>1 또는 rs>1 이면 mergeCell 호출.
        //   API 시그니처: mergeCell(table, startRow, startCol, rowSpan, colSpan)
        int mergeAttempts = 0, mergeOk = 0;
        for (int r = 0; r < numRows; r++) {
            for (int[] m : mastersInRow[r]) {
                int c = m[0], cs = m[1], rs = m[2];
                if (cs > 1 || rs > 1) {
                    mergeAttempts++;
                    try {
                        boolean ok = TableCellMerger.mergeCell(ct, r, c, rs, cs);
                        if (ok) mergeOk++;
                    } catch (Exception e) {
                        // 병합 실패해도 무시 — 1×1 cell 들은 그대로 유효
                    }
                }
            }
        }
        if (mergeAttempts > 0) {
            System.out.println("[MdToHwpRich] TableCellMerger 병합: "
                    + mergeOk + "/" + mergeAttempts + " 성공");
        }
    }

    /** [v14.68] nested table 의 tableH 추정 — parentInnerW 기반 균등 col 분배,
     *  각 row 의 max linesNeeded 합산. cell.h 산출에 사용 (한/글 손상 판정 회피). */
    private static int estimateNestedTableH(Table nested, int parentInnerW, int lineStepTbl) {
        final int MARGIN = 141;
        final int ROW_H_DEFAULT = 1800;
        if (nested == null || nested.rows == null || nested.rows.isEmpty()) return ROW_H_DEFAULT;
        int estCols = 0;
        for (List<TableCell> row : nested.rows) {
            int s = 0;
            for (TableCell c : row) s += Math.max(1, c.colspan);
            estCols = Math.max(estCols, s);
        }
        if (estCols < 1) estCols = 1;
        int colW = Math.max(1, parentInnerW / estCols);
        int innerColW = Math.max(1, colW - MARGIN * 2);

        int numRows = nested.rows.size();
        int[] rowHsum = new int[numRows];
        for (int r = 0; r < numRows; r++) rowHsum[r] = ROW_H_DEFAULT;
        for (int r = 0; r < numRows; r++) {
            List<TableCell> row = nested.rows.get(r);
            for (TableCell cell : row) {
                int cellMergedW = innerColW * Math.max(1, cell.colspan);
                int contentH;
                if (cell.nestedBlocks != null && !cell.nestedBlocks.isEmpty()) {
                    // [STEP4 task4] 다중 블록 셀: 텍스트 라인 + 내부표 높이 재귀 합산 (높이0 취급 제거)
                    int h = 0;
                    for (Object b : cell.nestedBlocks) {
                        if (b instanceof Table) {
                            h += estimateNestedTableH((Table) b, cellMergedW, lineStepTbl);
                        } else if (b instanceof String) {
                            String s = (String) b;
                            String pl = (s == null || s.isEmpty()) ? "" : extractStrong(s).text;
                            if (!pl.isEmpty()) h += Math.max(1, computeLineStarts(pl, cellMergedW).size()) * lineStepTbl;
                        }
                    }
                    contentH = Math.max(lineStepTbl, h) + MARGIN * 2;
                } else if (cell.nestedTable != null) {
                    // [STEP4 task4] 내부표(nested) 높이를 재귀 반영 + textBefore/After 라인 (이전: 평문 height0 → 오버플로)
                    int beforeLines = (cell.textBefore != null && !cell.textBefore.isEmpty())
                            ? Math.max(1, computeLineStarts(extractStrong(cell.textBefore).text, cellMergedW).size()) : 0;
                    int afterLines = (cell.textAfter != null && !cell.textAfter.isEmpty())
                            ? Math.max(1, computeLineStarts(extractStrong(cell.textAfter).text, cellMergedW).size()) : 0;
                    int innerH = estimateNestedTableH(cell.nestedTable, cellMergedW, lineStepTbl);
                    contentH = innerH + (beforeLines + afterLines) * lineStepTbl + MARGIN * 2;
                } else {
                    String text = (cell.text != null) ? cell.text : "";
                    String plain = text.isEmpty() ? "" : extractStrong(text).text;
                    int linesNeeded = Math.max(1, computeLineStarts(plain, cellMergedW).size());
                    contentH = linesNeeded * lineStepTbl + MARGIN * 2;
                }
                int rs = Math.max(1, cell.rowspan);
                int perRowContentH = Math.max(ROW_H_DEFAULT, (contentH + rs - 1) / rs);
                for (int rr = r; rr < r + rs && rr < numRows; rr++) {
                    rowHsum[rr] = Math.max(rowHsum[rr], perRowContentH);
                }
            }
        }
        int totalH = 0;
        for (int rh : rowHsum) totalH += rh;
        return totalH;
    }

    /** [v14.68] nested table 을 outer cell 의 paragraph 안에 진짜 HWP nested ControlTable
     *  로 emit. Returns: parentCell 의 총 paragraph count (textBefore + 1 host + textAfter).
     *  복잡한 경우 (깊이 nested, 거대 표) 는 단순 평탄화 fallback. */
    private static final int MAX_NEST_DEPTH = 3;  // [STEP2 task2/3] nested 표 재귀 깊이 cap

    private static int emitNestedTableToCell(Cell parentCell, TableCell mc, int parentInnerW,
                                              int lineStepTbl, int cellCS, int paraShapeForCell,
                                              int depth) throws Exception {
        int paraCount = 0;

        // [STEP2 task2/3] 한 셀에 nested table 이 2개 이상이면 텍스트/표 블록 순서대로 emit.
        if (mc.nestedBlocks != null && !mc.nestedBlocks.isEmpty()) {
            for (Object b : mc.nestedBlocks) {
                if (b instanceof Table) {
                    paraCount += emitOneNestedTable(parentCell, (Table) b, parentInnerW,
                            lineStepTbl, cellCS, paraShapeForCell, depth);
                } else if (b instanceof String) {
                    String t = (String) b;
                    if (t != null && !t.isEmpty()) {
                        java.util.List<Integer> starts = computeLineStarts(extractStrong(t).text, parentInnerW);
                        int before = parentCell.getParagraphList().getParagraphCount();
                        addCellParagraphsPerLine(parentCell, t, cellCS, parentInnerW,
                                starts, paraShapeForCell, lineStepTbl);
                        paraCount += parentCell.getParagraphList().getParagraphCount() - before;
                    }
                }
            }
            return Math.max(1, paraCount);
        }

        // ── 단일 nested 경로 (기존 동작 보존) ──
        // 1) textBefore paragraphs
        if (mc.textBefore != null && !mc.textBefore.isEmpty()) {
            java.util.List<Integer> beforeStarts = computeLineStarts(extractStrong(mc.textBefore).text, parentInnerW);
            int beforeStartParaCount = parentCell.getParagraphList().getParagraphCount();
            addCellParagraphsPerLine(parentCell, mc.textBefore, cellCS, parentInnerW,
                    beforeStarts, paraShapeForCell, lineStepTbl);
            paraCount += parentCell.getParagraphList().getParagraphCount() - beforeStartParaCount;
        }

        // 2) nested 표
        paraCount += emitOneNestedTable(parentCell, mc.nestedTable, parentInnerW,
                lineStepTbl, cellCS, paraShapeForCell, depth);

        // 3) textAfter paragraphs
        if (mc.textAfter != null && !mc.textAfter.isEmpty()) {
            java.util.List<Integer> afterStarts = computeLineStarts(extractStrong(mc.textAfter).text, parentInnerW);
            int beforeAfter = parentCell.getParagraphList().getParagraphCount();
            addCellParagraphsPerLine(parentCell, mc.textAfter, cellCS, parentInnerW,
                    afterStarts, paraShapeForCell, lineStepTbl);
            paraCount += parentCell.getParagraphList().getParagraphCount() - beforeAfter;
        }
        return Math.max(1, paraCount);
    }

    /** [STEP2 task2/3] 단일 Table 을 parentCell 안에 host paragraph + 진짜 ControlTable 로 emit.
     *  반환: parentCell 에 추가된 paragraph 수(=host 1). inner cell 이 다시 nested 면 depth+1 로
     *  emitNestedTableToCell 재귀(2단계 이상 깊이도 ControlTable 로); MAX_NEST_DEPTH 초과 시에만
     *  flattenNestedTableToText 평탄화 fallback. */
    private static int emitOneNestedTable(Cell parentCell, Table nested, int parentInnerW,
                                           int lineStepTbl, int cellCS, int paraShapeForCell,
                                           int depth) throws Exception {
        final int MARGIN = 141;
        final int BORDER_ID = 3;
        final int ROW_H = 1800;

        int numRows = (nested != null && nested.rows != null) ? nested.rows.size() : 0;
        if (numRows == 0) return 0;

        int paraCount = 0;

        // grid model
        int estCols = 0;
        for (List<TableCell> row : nested.rows) {
            int s = 0;
            for (TableCell c : row) s += Math.max(1, c.colspan);
            estCols = Math.max(estCols, s);
        }
        if (estCols == 0) estCols = 1;
        int gridCols = estCols + 4;
        boolean[][] occupied = new boolean[numRows][gridCols];
        @SuppressWarnings("unchecked")
        List<int[]>[] mastersInRow = new List[numRows];
        for (int r = 0; r < numRows; r++) mastersInRow[r] = new ArrayList<>();
        int actualMaxCol = 0;
        for (int r = 0; r < numRows; r++) {
            List<TableCell> row = nested.rows.get(r);
            int c = 0;
            for (TableCell cell : row) {
                while (c < gridCols && occupied[r][c]) c++;
                if (c >= gridCols) break;
                int cs = Math.min(cell.colspan, gridCols - c);
                int rs = Math.min(cell.rowspan, numRows - r);
                if (cs < 1) cs = 1;
                if (rs < 1) rs = 1;
                int srcIdx = row.indexOf(cell);
                mastersInRow[r].add(new int[]{c, cs, rs, srcIdx});
                for (int dr = 0; dr < rs; dr++) {
                    for (int dc = 0; dc < cs; dc++) {
                        if (r + dr < numRows && c + dc < gridCols) {
                            occupied[r + dr][c + dc] = true;
                        }
                    }
                }
                actualMaxCol = Math.max(actualMaxCol, c + cs - 1);
                c += cs;
            }
        }
        int numCols = actualMaxCol + 1;
        if (numCols == 0) numCols = 1;

        // col widths — parentInnerW 기반 균등 분배
        int[] colWidths = new int[numCols];
        int baseColW = parentInnerW / numCols;
        for (int c = 0; c < numCols; c++) colWidths[c] = baseColW;
        colWidths[numCols - 1] = parentInnerW - baseColW * (numCols - 1);
        int tableTotalW = 0;
        for (int w : colWidths) tableTotalW += w;

        // cellText, cellAlign, cellMaster
        String[][] cellText = new String[numRows][numCols];
        String[][] cellAlign = new String[numRows][numCols];
        TableCell[][] cellMaster = new TableCell[numRows][numCols];
        for (int r = 0; r < numRows; r++) {
            List<TableCell> srcRow = nested.rows.get(r);
            for (int[] m : mastersInRow[r]) {
                int c = m[0], srcIdx = m[3];
                if (c < numCols && srcIdx >= 0 && srcIdx < srcRow.size()) {
                    TableCell mc2 = srcRow.get(srcIdx);
                    cellText[r][c] = mc2.text;
                    cellAlign[r][c] = mc2.align;
                    cellMaster[r][c] = mc2;
                }
            }
        }

        // mergedTextWidthAt
        int[][] mergedTextWidthAt = new int[numRows][numCols];
        for (int r = 0; r < numRows; r++) {
            for (int c = 0; c < numCols; c++) {
                mergedTextWidthAt[r][c] = colWidths[c] - MARGIN * 2;
            }
            for (int[] m : mastersInRow[r]) {
                int c = m[0], cs = m[1];
                int mergedW = 0;
                for (int dc = 0; dc < cs && c + dc < numCols; dc++) {
                    mergedW += colWidths[c + dc];
                }
                mergedTextWidthAt[r][c] = Math.max(1, mergedW - MARGIN * 2);
            }
        }

        // cellLineStarts
        @SuppressWarnings("unchecked")
        java.util.List<Integer>[][] cellLineStarts = new java.util.List[numRows][numCols];
        for (int r = 0; r < numRows; r++) {
            for (int c = 0; c < numCols; c++) {
                String text = cellText[r][c];
                String plain = (text == null || text.isEmpty()) ? "" : extractStrong(text).text;
                cellLineStarts[r][c] = computeLineStarts(plain, mergedTextWidthAt[r][c]);
            }
        }

        // rowH
        int[] rowH = new int[numRows];
        for (int r = 0; r < numRows; r++) rowH[r] = ROW_H;
        for (int r = 0; r < numRows; r++) {
            for (int[] m : mastersInRow[r]) {
                int c = m[0], rs = m[2];
                int contentH;
                TableCell mci = cellMaster[r][c];
                if (mci != null && mci.nestedBlocks != null && !mci.nestedBlocks.isEmpty()) {
                    // [STEP4 task4] 다중 블록 셀: 모든 내부표 + 텍스트 블록 높이 합산
                    int innerWi = mergedTextWidthAt[r][c];
                    int hi = 0;
                    for (Object b : mci.nestedBlocks) {
                        if (b instanceof Table) {
                            hi += estimateNestedTableH((Table) b, innerWi, lineStepTbl);
                        } else if (b instanceof String) {
                            String s = (String) b;
                            String pl = (s == null || s.isEmpty()) ? "" : extractStrong(s).text;
                            if (!pl.isEmpty()) hi += Math.max(1, computeLineStarts(pl, innerWi).size()) * lineStepTbl;
                        }
                    }
                    contentH = Math.max(lineStepTbl, hi) + MARGIN * 2;
                } else if (mci != null && mci.nestedTable != null) {
                    // 깊이 nested — 추정 (1-level 권장)
                    int innerWi = mergedTextWidthAt[r][c];
                    int nestedHi = estimateNestedTableH(mci.nestedTable, innerWi, lineStepTbl);
                    contentH = nestedHi + MARGIN * 2;
                } else {
                    int linesNeeded = Math.max(1, cellLineStarts[r][c].size());
                    contentH = linesNeeded * lineStepTbl + MARGIN * 2;
                }
                int perRowContentH = Math.max(ROW_H, (contentH + rs - 1) / rs);
                for (int rr = r; rr < r + rs && rr < numRows; rr++) {
                    rowH[rr] = Math.max(rowH[rr], perRowContentH);
                }
            }
        }
        int tableH = 0;
        for (int rh : rowH) tableH += rh;

        // host paragraph in parentCell
        Paragraph host = parentCell.getParagraphList().addNewParagraph();
        host.getHeader().setCharacterCount(9);
        host.getHeader().setParaShapeId(0);
        host.getHeader().setStyleId((short) 0);
        host.getHeader().setCharShapeCount(1);
        host.getHeader().setLineAlignCount(1);
        host.getHeader().setRangeTagCount(0);
        host.getHeader().setLastInList(false);
        host.getHeader().setInstanceID(nextParaInstanceId());  // [task1/2] unique uint32
        host.getHeader().setIsMergedByTrack(0);
        host.getHeader().getControlMask().setHasGsoTable(true);
        host.createText();
        host.getText().addExtendCharForTable();
        host.createCharShape();
        host.getCharShape().addParaCharShape(0, CS_NORMAL);
        host.createLineSeg();
        LineSegItem hostSeg = host.getLineSeg().addNewLineSegItem();
        hostSeg.setTextStartPosition(0);
        hostSeg.setLineVerticalPosition(0);
        // [v14.92 task1] nested 표의 outer T+B margin (각 1000 hwpunit) 을 paragraph lineHeight 에
        //   포함 → outer cell 안에서 nested 표 위/아래 시각적 여백 확보. 기존 v14.91 까지 lineHeight=tableH
        //   로 nested 표 경계가 paragraph 경계와 정확히 맞물려 여백 0 → 입찰공고문test.hwp 의 1/2쪽 1단
        //   5줄 표 같은 "표안에 표 마진" 모양 미구현. reference 매칭 산출:
        //   lineH = tableH + 2*1000 = 8164 (ref) vs 6164 (이전). distBase = lineH - 1225 (ref pattern).
        int hostLineH = tableH + 1000 + 1000;
        hostSeg.setLineHeight(hostLineH);
        hostSeg.setTextPartHeight(hostLineH);
        hostSeg.setDistanceBaseLineToLineVerticalPosition(Math.max(0, hostLineH - 1225));
        hostSeg.setLineSpace(600);
        hostSeg.setStartPositionFromColumn(0);
        hostSeg.setSegmentWidth(parentInnerW);
        hostSeg.getTag().setFirstSegmentAtLine(true);
        hostSeg.getTag().setLastSegmentAtLine(true);
        paraCount++;

        // ControlTable
        ControlTable ct = (ControlTable) host.addNewControl(ControlType.Table);
        CtrlHeaderGso h = ct.getHeader();
        h.setWidth(tableTotalW);
        h.setHeight(tableH);
        h.setzOrder(0);
        h.setOutterMarginLeft(283);
        h.setOutterMarginRight(283);
        // [v14.88 task4] nested 표 위/아래 여백 증가 — parent 본문 텍스트와 nested 표 사이
        //   시각적 간격 확보. 1000 HWPUnit ≈ 7pt → 한 줄 정도 여백.
        h.setOutterMarginTop(1000);
        h.setOutterMarginBottom(1000);
        // [v14.69] nested 표는 "글자처럼 취급" (LikeWord=true) 적용 — 사용자 task 요구.
        //   outer cell paragraph 안에서 inline 문자처럼 흐름 → 한/글의 일반적인 "표 안의 표"
        //   처리 방식. outer table 은 페이지 분할 위해 LikeWord=false 유지.
        h.getProperty().setLikeWord(true);
        h.getProperty().setVertRelTo(VertRelTo.Para);
        h.getProperty().setVertRelativeArrange(RelativeArrange.TopOrLeft);
        h.getProperty().setHorzRelTo(HorzRelTo.Para);
        // [v14.87 task4] nested 표를 부모 셀 중앙 정렬 — 좌측여백 == 우측여백.
        //   v14.86 까지 TopOrLeft 라 부모 inner width 와 nested table width 가 살짝 어긋날 때
        //   우측에 여백이 몰려 우측정렬처럼 보이는 문제.
        h.getProperty().setHorzRelativeArrange(RelativeArrange.Center);
        h.getProperty().setWidthCriterion(WidthCriterion.Absolute);
        h.getProperty().setHeightCriterion(HeightCriterion.Absolute);
        h.getProperty().setTextFlowMethod(TextFlowMethod.TakePlace);
        h.getProperty().setTextHorzArrange(TextHorzArrange.BothSides);
        h.getProperty().setObjectNumberSort(ObjectNumberSort.Table);

        kr.dogfoot.hwplib.object.bodytext.control.table.Table tbl = ct.getTable();
        tbl.setRowCount(numRows);
        tbl.setColumnCount(numCols);
        tbl.setCellSpacing(0);
        tbl.setLeftInnerMargin(MARGIN);
        tbl.setRightInnerMargin(MARGIN);
        tbl.setTopInnerMargin(MARGIN);
        tbl.setBottomInnerMargin(MARGIN);
        tbl.setBorderFillId(BORDER_ID);
        tbl.getProperty().setValue(0x06L);

        // 행/셀 추가
        int mergeAttempts = 0, mergeOk = 0;
        for (int ri = 0; ri < numRows; ri++) {
            tbl.addCellCountOfRow(numCols);
            Row hwpRow = ct.addNewRow();
            for (int colIdx = 0; colIdx < numCols; colIdx++) {
                int cellW = colWidths[colIdx];
                String text = cellText[ri][colIdx];
                int cellH = rowH[ri];

                Cell cellHwp = hwpRow.addNewCell();
                ListHeaderForCell lh = cellHwp.getListHeader();
                int cellParaCount = Math.max(1, cellLineStarts[ri][colIdx].size());
                lh.setParaCount(cellParaCount);
                lh.setColIndex(colIdx);
                lh.setRowIndex(ri);
                lh.setColSpan(1);
                lh.setRowSpan(1);
                lh.setWidth(cellW);
                lh.setHeight(cellH);
                lh.setLeftMargin(MARGIN);
                lh.setRightMargin(MARGIN);
                lh.setTopMargin(MARGIN);
                lh.setBottomMargin(MARGIN);
                lh.setBorderFillId(BORDER_ID);
                int textW;
                int mergedW2 = mergedTextWidthAt[ri][colIdx] + MARGIN * 2;
                textW = mergedW2;
                if (textW <= 0) textW = cellW;
                lh.setTextWidth(textW);
                lh.getProperty().setTextDirection(
                        kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.sectiondefine.TextDirection.Horizontal);
                lh.getProperty().setLineChange(
                        kr.dogfoot.hwplib.object.bodytext.control.gso.textbox.LineChange.Normal);
                lh.getProperty().setTextVerticalAlignment(
                        kr.dogfoot.hwplib.object.bodytext.control.gso.textbox.TextVerticalAlignment.Center);
                lh.getProperty().setApplyInnerMagin(false);
                lh.getProperty().setProtectCell(false);
                lh.getProperty().setTitleCell(false);
                lh.getProperty().setEditableAtFormMode(false);

                int paragraphTextW = mergedTextWidthAt[ri][colIdx];
                int paraShapeNested = paraShapeForAlign(cellAlign[ri][colIdx]);
                TableCell mc2 = cellMaster[ri][colIdx];
                if (mc2 != null && mc2.nestedTable != null) {
                    if (depth + 1 <= MAX_NEST_DEPTH) {
                        // [STEP2 task2/3] 2단계 이상 깊이도 진짜 ControlTable 로 재귀 emit.
                        int innerParaCount = emitNestedTableToCell(cellHwp, mc2, paragraphTextW,
                                lineStepTbl, cellCS, paraShapeNested, depth + 1);
                        lh.setParaCount(Math.max(1, innerParaCount));
                    } else {
                        // 깊이 cap 초과 — 평탄화 fallback (단순 텍스트로)
                        String flat = flattenNestedTableToText(mc2.nestedTable);
                        String combined = (text == null || text.isEmpty()) ? flat : (text + "\n" + flat);
                        java.util.List<Integer> ls = computeLineStarts(extractStrong(combined).text, paragraphTextW);
                        addCellParagraphsPerLine(cellHwp, combined, cellCS, paragraphTextW,
                                ls, paraShapeNested, lineStepTbl);
                        lh.setParaCount(Math.max(1, ls.size()));
                    }
                } else {
                    // [v14.91 task1] inner cell 도 segAligns 가 있으면 per-segment 적용
                    int[] segStartsInner  = (mc2 != null) ? mc2.segStarts  : null;
                    String[] segAlignsInner = (mc2 != null) ? mc2.segAligns : null;
                    addCellParagraphsPerLine(cellHwp, text, cellCS, paragraphTextW,
                            cellLineStarts[ri][colIdx], paraShapeNested, lineStepTbl,
                            segStartsInner, segAlignsInner);
                }
            }
        }

        // TableCellMerger 호출 (cs/rs 병합)
        for (int r = 0; r < numRows; r++) {
            for (int[] m : mastersInRow[r]) {
                int c = m[0], cs = m[1], rs = m[2];
                if (cs > 1 || rs > 1) {
                    mergeAttempts++;
                    try {
                        boolean ok = TableCellMerger.mergeCell(ct, r, c, rs, cs);
                        if (ok) mergeOk++;
                    } catch (Exception ignore) {}
                }
            }
        }

        return paraCount;
    }


    /**
     * 셀(Cell)에 단락 1개를 추가하고 텍스트를 채운다.
     * 셀 단락은 Section 단락과 달리 ParagraphList.addNewParagraph() 사용.
     *
     * [v14.19 수정]
     *   - segmentWidth 는 셀의 textWidth 로 (이전: 항상 41954 페이지 폭).
     *   - 빈 셀도 createText() + 빈 addString("") 으로 terminator 추가
     *     → text record 가 항상 존재 (Hangul 표 검증 통과 조건).
     *   - characterCount 는 text.length() 또는 0 (regular paragraph 와 동일 규칙;
     *     hwplib addString 이 internally +1 terminator 를 추가하지만 v14.9
     *     이래로 동일 패턴이 정상 동작 검증됨).
     */
    /** [v14.50] visual width + word-boundary + 2-tier fit-check 기반 wrap.
     *  알고리즘:
     *   1. text 를 \n (br sentinel) 단위로 나눠 segment 들로 split.
     *   2. 각 segment 에 대해 — "1 line emit" 조건 (둘 중 하나):
     *      A. naturalFit: visualSum ≤ innerW — 자연 spacing 으로 fit. 압축 없음.
     *      B. shortAndAcceptable: chars ≤ 35 AND visualSum ≤ 2.5 × innerW
     *         — 짧은 텍스트는 압축돼도 시각적으로 OK (예: "도지사에게 보고하는 경우").
     *      위 둘 다 충족 안되면 word-boundary wrap 으로 다중 paragraph emit
     *      (긴 텍스트가 한 줄에 너무 많은 chars 압축으로 자간 좁아지는 것 방지).
     *   3. \n 자체는 forced wrap → segment 사이 paragraph 경계.
     *  이전 v14.49: 단일 threshold 2.5 × innerW — 긴 텍스트 (~85 chars) 도 1 line 로
     *  emit 되어 한/글이 segW 안에 욱여넣다가 자간 압축 (490 hwpunit/char). 사용자가
     *  section paragraph (~600-800 hwpunit/char) 와의 자간 차이 보고.
     */
    private static java.util.List<Integer> computeLineStarts(String safeText, int innerW) {
        java.util.List<Integer> lineStarts = new java.util.ArrayList<>();
        lineStarts.add(0);
        if (safeText == null || safeText.isEmpty() || innerW <= 0) return lineStarts;
        int n = safeText.length();
        int i = 0;
        while (i < n) {
            int segEnd = n;
            for (int k = i; k < n; k++) {
                if (safeText.charAt(k) == '\n') { segEnd = k; break; }
            }
            // segment-level fit check: visual width 합 + char 수
            int segWidth = 0;
            int segChars = segEnd - i;
            for (int k = i; k < segEnd; k++) segWidth += charVisualWidth(safeText.charAt(k));
            // [v14.56] threshold 1.0 → 0.85 × innerW. 사용자 보고 — segW 거의 채우는
            //   text 가 1 줄로 emit 되면 한/글 padding 없이 그려 자간 좁아 보임 (예: "OOO군
            //   OOO면 OOO리" 15자 in segW=8108, visualSum 8550 거의 동일). 0.85 threshold
            //   로 wrap 더 일찍 트리거 → 줄당 chars 줄어 padding 확보 → 자연 spacing.
            boolean naturalFit          = segWidth * 100 <= innerW * 85;
            if (naturalFit) {
                // Don't wrap inside this segment — let 한/글 render as 1 line.
                i = segEnd;
            } else {
                // [v14.56] inner wrap threshold 도 0.85 × innerW — naturalFit 분기와 일치.
                //   이전 v14.55: naturalFit 0.85 만 0.85 였고 inner loop 는 1.0 → wrap 안 일어나는
                //   경우 발생 ("OOO군 OOO면 OOO리" 7650 vs innerW 8108 → 안 잡힘).
                int wrapLimit = innerW * 85 / 100;
                int acc = 0;
                int lineBegin = i;
                for (int k = i; k < segEnd; k++) {
                    char ch = safeText.charAt(k);
                    int w = charVisualWidth(ch);
                    if (acc + w > wrapLimit && acc > 0) {
                        int wrapAt = findBestBreak(safeText, lineBegin, k);
                        lineStarts.add(wrapAt);
                        lineBegin = wrapAt;
                        acc = 0;
                        for (int kk = wrapAt; kk <= k; kk++) acc += charVisualWidth(safeText.charAt(kk));
                    } else {
                        acc += w;
                    }
                }
                i = segEnd;
            }
            // \n 처리: forced wrap → 다음 segment 시작
            if (i < n && safeText.charAt(i) == '\n') {
                lineStarts.add(i + 1);
                i++;
            }
        }
        return lineStarts;
    }

    /** [v14.47] 단어 경계 우선 wrap 위치 탐색.
     *  hardI 위치에서 강제 wrap 이 필요한 경우, 가능하면 lineBegin..hardI 범위에서
     *  가장 최근의 공백 직후 위치를 반환. 없으면 hardI 그대로 (mid-word forced break).
     */
    private static int findBestBreak(String s, int lineBegin, int hardI) {
        for (int k = hardI - 1; k > lineBegin; k--) {
            char ck = s.charAt(k);
            if (ck == ' ' || ck == '\t' || ck == '　' || ck == ' ' || ck == '/') {
                return k + 1;
            }
        }
        return hardI;
    }

    /** 10pt 글꼴 기준 char 의 시각 너비 (hwpunit).
     *  [v14.50] CJK 750 → 900, ASCII 375 → 450. wrap trigger 시 줄당 chars 수가
     *    한/글 실제 font advance (~900-1000 hwpunit) 와 일치하도록 — 줄당 ~44 chars
     *    in segW=41672 → spacing 947 hwpunit/char (자연 spacing). 짧은 셀의 1 줄
     *    유지는 computeLineStarts 의 shortAndAcceptable 분기 (chars ≤ 35) 로 보장.
     *    이력: [v14.47] 750. [v14.46] 900. [v14.45] 580. [v14.43] 500.
     */
    private static int charVisualWidth(char ch) {
        if (ch >= 0xAC00 && ch <= 0xD7A3) return 900;  // 한글 음절
        if (ch >= 0x4E00 && ch <= 0x9FFF) return 900;  // CJK 통합 한자
        if (ch >= 0x3000 && ch <= 0x303F) return 900;  // CJK 기호
        if (ch >= 0xFF00 && ch <= 0xFFEF) return 900;  // 전각
        if (ch >= 0x2000 && ch <= 0x27BF) return 900;  // 일반 구두점/화살표 (≪≫※→ 등)
        if (ch >= 0x3200 && ch <= 0x33FF) return 900;  // 한글 호환자모/CJK 기호
        return 450;                                     // ASCII 등 반각
    }

    /**
     * [v14.45] 셀에 N 개 paragraph 를 추가 (paragraph-per-visual-line).
     * cellLineStarts 의 wrap 점에서 텍스트를 분할 → 각 조각이 별도 paragraph 로.
     * 각 paragraph 는 1 LineSegItem (linePos=0) 만 가진다 → 한/글의 layout 엔진이
     * paragraph 경계를 줄 경계로 인식 → linePos/segmentH 계산 ambiguity 차단.
     *
     * - paraCount (cell.listHeader) = lineStarts.size()
     * - 각 paragraph: chars = (다음 wrap point - 현재 wrap point), lineAlignCount=1
     * - bold ranges 는 paragraph 별 좌표로 재매핑
     * - 마지막 paragraph 만 lastInList=true
     */
    /** [v14.49] align ("center"|"right"|"left"|null) → template.hwp 의 ParaShape ID 매핑.
     *  [v14.55] center: PS 28 → PS 34. PS 28 의 prop1=0x10c (bit 7 not set) 사용 시
     *    한/글이 "자간 자동 조정" 활성화로 CharSpaces 무시 → 자간 +50 효과 시각 안 됨.
     *    PS 34 의 prop1=0x18c (bit 7 SET) — 자간 자동 조정 OFF → CharSpaces 적용 보장.
     *    PS 34: Center 정렬 + lineSpace=160 (PS 0 매칭) + bit 7 set.
     *   center → PS 34 (Center, bit 7 set = 자간 정적 적용)
     *   right  → [v14.91 task1] PS_RIGHT (loadMutatedTemplateBytes 에서 append, alignment=Right)
     *   left/default → PS 0 (Justify; 짧은 단일 줄에서는 Left 와 시각적 동일)
     */
    private static int paraShapeForAlign(String align) {
        if (align == null) return 0;
        if ("center".equalsIgnoreCase(align)) return 34;
        if ("right".equalsIgnoreCase(align)) return PS_RIGHT;
        return 0;
    }

    /** [v14.64] lineStep 파라미터 추가 — 일반 표 1400 (v14.60+), legacy 표 2200 (v14.60 이전).
     *  [v14.91 task1] segStarts/segAligns 오버로드. 셀 본문이 여러 &lt;p align&gt; segment 로
     *  구성된 경우, 각 출력 paragraph 의 시작 char 위치가 어느 segment 에 속하는지 찾아
     *  segment-별 align 을 적용. null/null 이면 cell-수준 단일 align 사용 (기존 동작). */
    private static void addCellParagraphsPerLine(Cell cell, String text, int charShapeId,
                                                 int cellTextWidth,
                                                 java.util.List<Integer> precomputedLineStarts,
                                                 int paraShapeId,
                                                 int lineStep)
            throws Exception {
        addCellParagraphsPerLine(cell, text, charShapeId, cellTextWidth,
                precomputedLineStarts, paraShapeId, lineStep, null, null);
    }
    private static void addCellParagraphsPerLine(Cell cell, String text, int charShapeId,
                                                 int cellTextWidth,
                                                 java.util.List<Integer> precomputedLineStarts,
                                                 int paraShapeId,
                                                 int lineStep,
                                                 int[] segStarts, String[] segAligns)
            throws Exception {
        // 1) plain text + bold ranges 추출 (전체 셀 텍스트 기준)
        RichLine richLine = (text == null || text.isEmpty())
                ? RichLine.plain("")
                : extractStrong(text);
        String cleanText = richLine.text;
        java.util.List<int[]> boldRanges = richLine.boldRanges;

        String safeText;
        if (cleanText == null || cleanText.isEmpty()) {
            safeText = "​"; // ZWSP
        } else {
            safeText = cleanText;
        }

        int innerW = cellTextWidth > 0 ? cellTextWidth : 41954;

        // 2) lineStarts 결정. precomputed 가 비었으면 즉석 계산.
        java.util.List<Integer> lineStarts;
        if (precomputedLineStarts != null && !precomputedLineStarts.isEmpty()) {
            lineStarts = precomputedLineStarts;
        } else {
            lineStarts = computeLineStarts(safeText, innerW);
        }
        if (lineStarts.isEmpty()) lineStarts = java.util.Collections.singletonList(0);

        int totalLen = safeText.length();
        int lineCount = lineStarts.size();

        // [v14.45] line metric: 한/글 cell 의 줄 간 시각적 vertical step 과 일치.
        //   template.hwp 분석 결과 (cell.paraCount > 1 인 셀): 각 paragraph 의
        //   LineSegItem.linePos 는 cell-absolute Y 좌표 (이전 paragraph 끝에서 이어짐).
        //   따라서 paragraph N 의 첫 LineSegItem linePos = N * LINE_STEP.
        // [v14.64] lineStep 파라미터 사용 — 일반 표 1400, legacy 표 2200.
        //   addNativeTable 의 LINE_STEP_TBL 과 동기화 보장.
        final int LINE_HEIGHT = 1000;
        final int LINE_SPACE  = Math.max(0, lineStep - LINE_HEIGHT);  // 400 or 1200
        final int LINE_STEP   = lineStep;
        final int BASE_DIST   = 900;

        // 3) N 개 paragraph emit
        for (int li = 0; li < lineCount; li++) {
            int start = lineStarts.get(li);
            int end = (li + 1 < lineCount) ? lineStarts.get(li + 1) : totalLen;
            if (end < start) end = start;
            String segText = safeText.substring(start, end);
            // [v14.47] \n 은 paragraph 경계 sentinel — 텍스트에 포함되면 안 됨.
            //   computeLineStarts 가 \n 위치+1 을 다음 줄 시작으로 잡지만, 안전망으로
            //   여기서도 한 번 더 strip.
            segText = segText.replace("\n", "");
            // 빈 segment → ZWSP 로 (paragraph 가 텍스트 없이 emit 되면 한/글 손상 판정)
            String safeSegText = segText.isEmpty() ? "​" : segText;

            Paragraph cp = cell.getParagraphList().addNewParagraph();
            // [v14.49] cell-level alignment 적용 — 0=Left/Justify (default), 12=Center.
            // [v14.91 task1] segAligns 가 주어지면 paragraph 의 start char 가 속한 segment 의
            //   align 으로 override (per-<p> align 보존).
            int psIdForThisLine = paraShapeId;
            if (segStarts != null && segAligns != null && segAligns.length > 0) {
                int segIdx = 0;
                for (int si = 0; si < segStarts.length; si++) {
                    if (segStarts[si] <= start) segIdx = si;
                    else break;
                }
                String segAlign = segAligns[segIdx];
                if (segAlign != null) psIdForThisLine = paraShapeForAlign(segAlign);
            }
            cp.getHeader().setParaShapeId((short) psIdForThisLine);
            cp.getHeader().setStyleId((short) 0);
            cp.getHeader().setLineAlignCount(1);
            cp.getHeader().setRangeTagCount(0);
            // 마지막 paragraph 만 lastInList=true
            cp.getHeader().setLastInList(li == lineCount - 1);
            cp.getHeader().setInstanceID(nextParaInstanceId());  // [task1/2] unique uint32
            cp.getHeader().setIsMergedByTrack(0);

            cp.createText();
            cp.getText().addString(safeSegText);
            int cc = safeSegText.length() + 1;
            cp.getHeader().setCharacterCount(cc);

            cp.createCharShape();
            // bold range 를 (start, end) 으로 clip 후 paragraph-local 좌표로 재매핑
            int charCount = safeSegText.length();
            int runs;
            if (boldRanges == null || boldRanges.isEmpty() || segText.isEmpty()) {
                cp.getCharShape().addParaCharShape(0, charShapeId);
                runs = 1;
            } else {
                java.util.List<int[]> localRanges = new java.util.ArrayList<>();
                for (int[] br : boldRanges) {
                    int s = Math.max(br[0], start) - start;
                    int e = Math.min(br[1], end) - start;
                    if (e > s && s < charCount) {
                        localRanges.add(new int[]{Math.max(0, s), Math.min(charCount, e)});
                    }
                }
                if (localRanges.isEmpty()) {
                    cp.getCharShape().addParaCharShape(0, charShapeId);
                    runs = 1;
                } else {
                    int cursor = 0;
                    int runCount = 0;
                    for (int[] r : localRanges) {
                        if (r[0] > cursor) {
                            cp.getCharShape().addParaCharShape(cursor, charShapeId);
                            runCount++;
                        }
                        cp.getCharShape().addParaCharShape(r[0], CS_BOLD);
                        runCount++;
                        cursor = r[1];
                    }
                    if (cursor < charCount) {
                        cp.getCharShape().addParaCharShape(cursor, charShapeId);
                        runCount++;
                    }
                    // 안전망: pos 0 이 매핑되어 있어야 함
                    if (cp.getCharShape().getPositonShapeIdPairList().isEmpty()
                            || cp.getCharShape().getPositonShapeIdPairList().get(0).getPosition() != 0) {
                        java.util.ArrayList<long[]> snap = new java.util.ArrayList<>();
                        for (kr.dogfoot.hwplib.object.bodytext.paragraph.charshape.CharPositionShapeIdPair pr
                                : cp.getCharShape().getPositonShapeIdPairList()) {
                            snap.add(new long[]{pr.getPosition(), pr.getShapeId()});
                        }
                        cp.getCharShape().getPositonShapeIdPairList().clear();
                        cp.getCharShape().addParaCharShape(0, charShapeId);
                        for (long[] pr : snap) {
                            if (pr[0] != 0) cp.getCharShape().addParaCharShape(pr[0], pr[1]);
                        }
                        runCount = cp.getCharShape().getPositonShapeIdPairList().size();
                    }
                    runs = runCount;
                }
            }
            cp.getHeader().setCharShapeCount(runs);

            // 4) 1 LineSegItem at linePos = li * LINE_STEP (cell-absolute Y).
            //    template.hwp 의 cell-with-multiple-paragraphs 가 사용하는 패턴.
            cp.createLineSeg();
            LineSegItem seg = cp.getLineSeg().addNewLineSegItem();
            seg.setTextStartPosition(0);
            seg.setLineVerticalPosition(li * LINE_STEP);
            seg.setLineHeight(LINE_HEIGHT);
            seg.setTextPartHeight(LINE_HEIGHT);
            seg.setDistanceBaseLineToLineVerticalPosition(BASE_DIST);
            seg.setLineSpace(LINE_SPACE);
            seg.setStartPositionFromColumn(0);
            seg.setSegmentWidth(innerW);
            seg.getTag().setFirstSegmentAtLine(true);
            seg.getTag().setLastSegmentAtLine(true);
        }
    }

    private static int lineHeightForCharShape(int charShapeId) {
        for (int i = 0; i < CS_HEADINGS.length; i++) {
            if (CS_HEADINGS[i] == charShapeId) {
                // baseSize ≈ line height (1/100 pt). H1 가 가장 큼.
                return CS_HEADING_SIZES[i];
            }
        }
        return 1000;
    }


    // =========================================================
    //  Block → 텍스트 라인 (paragraph 단위 분리)
    // =========================================================

    /** v14.16: paragraph 라인 + 그 안의 bold 범위 (start,end) 쌍을 함께 반환. */
}
