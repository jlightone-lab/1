package kr.n.nframe.newfeature;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import kr.n.nframe.hwplib.constants.CtrlId;
import kr.n.nframe.hwplib.model.BorderFill;
import kr.n.nframe.hwplib.model.CharShape;
import kr.n.nframe.hwplib.model.CharShapeRef;
import kr.n.nframe.hwplib.model.Control;
import kr.n.nframe.hwplib.model.CtrlField;
import kr.n.nframe.hwplib.model.CtrlHeaderFooter;
import kr.n.nframe.hwplib.model.CtrlSectionDef;
import kr.n.nframe.hwplib.model.CtrlTable;
import kr.n.nframe.hwplib.model.FaceName;
import kr.n.nframe.hwplib.model.HwpDocument;
import kr.n.nframe.hwplib.model.LineSeg;
import kr.n.nframe.hwplib.model.Paragraph;
import kr.n.nframe.hwplib.model.ParaShape;
import kr.n.nframe.hwplib.model.ParaText;
import kr.n.nframe.hwplib.model.Section;
import kr.n.nframe.hwplib.model.Style;
import kr.n.nframe.hwplib.model.TabDef;
import kr.n.nframe.hwplib.model.TableCell;
import kr.n.nframe.newfeature.OdtDocumentModel.Block;
import kr.n.nframe.newfeature.OdtDocumentModel.EquationBlock;
import kr.n.nframe.newfeature.OdtDocumentModel.HeadingBlock;
import kr.n.nframe.newfeature.OdtDocumentModel.ImageBlock;
import kr.n.nframe.newfeature.OdtDocumentModel.OdtStyle;
import kr.n.nframe.newfeature.OdtDocumentModel.ParagraphBlock;
import kr.n.nframe.newfeature.OdtDocumentModel.Run;
import kr.n.nframe.newfeature.OdtDocumentModel.TableBlock;

import kr.n.nframe.hwplib.model.BinDataItem;
import kr.n.nframe.hwplib.model.CtrlPicture;

/**
 * {@link OdtDocumentModel} → {@link HwpDocument} 변환기.
 *
 * <h2>v16.00 초기 버전 처리 범위</h2>
 * <ul>
 *   <li>최소 idMappings / faceNames / borderFills / charShapes / paraShapes / styles 골격 생성</li>
 *   <li>ODT paragraph / heading 본문 텍스트 → HWP Paragraph + ParaText 매핑</li>
 *   <li>Heading 의 ODT {@code fo:border-bottom} → 전용 ParaShape + 전용 BorderFill (bottom only) 부착</li>
 *   <li>Heading 글자 크기 / 색상 / bold → 전용 CharShape</li>
 *   <li>Span 의 인라인 색상 / bold / italic / strike → 전용 CharShape + charShapeRef 분할</li>
 * </ul>
 *
 * <p>표 / 이미지 / 리스트는 v16.00 후속 버전에서 확장. 본 버전에서는 표는 셀 텍스트만
 *   평탄화된 paragraph 시퀀스로 출력 (셀 구분 없음 — 임시).
 */
public final class OdtToHwpDocumentBuilder {

    // -------------------------------------------------------------------------
    //  상수
    // -------------------------------------------------------------------------

    /** 기본 한글 폰트. ODT 에 명시되지 않은 경우 사용. */
    private static final String DEFAULT_KOREAN_FONT  = "맑은 고딕";
    /** 기본 영문 폰트. ODT 에 명시되지 않은 경우 사용. */
    private static final String DEFAULT_LATIN_FONT   = "맑은 고딕";

    /** HWP CharShape.baseSize 단위 = 1/100 pt. 기본 10pt = 1000. */
    private static final int DEFAULT_FONT_SIZE_UNITS = 1000;
    /** 폰트 그룹 개수 (HWP 5.0 사양). */
    private static final int FONT_GROUP_COUNT = 7;

    // -------------------------------------------------------------------------
    //  내부 상태 (build 한 번당 새로 생성)
    // -------------------------------------------------------------------------

    private HwpDocument doc;
    private Map<String, OdtStyle> styleMap;
    /** CharShape signature → id (재사용 위함). */
    private Map<String, Integer> charShapeCache;
    /** BorderFill signature → id (재사용 위함). */
    private Map<String, Integer> borderFillCache;
    /** ParaShape signature → id. */
    private Map<String, Integer> paraShapeCache;
    /** v16t45 G1: 이미지 콘텐츠(ext+SHA-1+len) → binDataId — 동일 바이트 중복 임베드 방지. */
    private Map<String, Integer> binDataDedupCache;
    /** v16t45 G1: CtrlPicture instanceId 일련번호 — dedup 으로 binDataId 가 겹쳐도 유일성 보장. */
    private int pictureInstanceSeq;
    /** v16t46 FIX D: tab-stop 집합 직렬화 키 → TabDef id — 동일 집합 재사용 (dedup). */
    private Map<String, Integer> tabDefDedupCache;
    /** v16t48 F-2b: 직전 buildCellParagraphs 셀에 명시 font-size 존재 여부 — 8pt 보정 면제 판단용. */
    private boolean lastCellExplicitSize;
    /**
     * v16t46 FIX D-3: 현재 빌드 중 문단의 인라인 탭(0x09) Byte6=leader / Byte7=type.
     * buildParagraph 가 Stage-1 TabDef 보유 문단에서만 설정·해제. 0/0 이면 종전 바이트 그대로.
     */
    private int inlineTabLeader;
    private int inlineTabType;
    /** v16t50 R12-A: 인라인 탭 width 사전계산. width=0 이면 한컴이 dot leader 위치를 못 잡아 점선 미표시. */
    private int inlineTabWidth;
    /** 현재 빌드 중인 OdtDocumentModel 참조 (header/footer 텍스트 등 메타 접근용). */
    private OdtDocumentModel currentModel;

    // -------------------------------------------------------------------------
    //  공개 API
    // -------------------------------------------------------------------------

    public HwpDocument build(OdtDocumentModel model) {
        this.doc = new HwpDocument();
        this.doc.emitCompatibleDocument = false; // 변형B: ODT→HWP 경로는 COMPATIBLE_DOCUMENT 레코드 생략
        this.styleMap = model.styles;
        this.currentModel = model;
        this.charShapeCache = new HashMap<>();
        this.borderFillCache = new HashMap<>();
        this.paraShapeCache = new HashMap<>();
        this.binDataDedupCache = new HashMap<>();
        this.pictureInstanceSeq = 0;
        this.tabDefDedupCache = new HashMap<>();
        this.inlineTabLeader = 0;
        this.inlineTabType = 0;
        this.inlineTabWidth = 0;

        initBaseTables();
        Section section = new Section();
        // HWP 5.0 사양 : Section 의 첫 paragraph 는 SECTION_DEFINE 컨트롤을 보유해야 한다.
        //   누락 시 hwp2hwpx 의 ForContentHPFFile.sectionDefine 에서 NPE,
        //   한글에서는 "문서에 손상..." 팝업 발생 가능.
        section.paragraphs.add(sectionDefineParagraph());
        for (Block b : model.blocks) {
            appendBlock(section, b);
        }
        if (section.paragraphs.size() == 1) {
            // SECTION_DEFINE 만 있고 본문이 비었으면 빈 paragraph 1개 추가
            section.paragraphs.add(emptyParagraph());
        }
        doc.sections.add(section);
        // 빌드 종료 직전 IdMappings 최종 동기화 (heading borderFill / 추가 charShape 반영)
        syncIdMappings();
        return doc;
    }

    // -------------------------------------------------------------------------
    //  기본 테이블 초기화 (idMappings / faceNames / borderFills / charShapes / paraShapes / styles)
    // -------------------------------------------------------------------------

    private void initBaseTables() {
        // 1) FaceNames — 7개 언어 그룹, 각 1개 기본 폰트
        //   fontType = 1 (TTF) 로 명시. fontType=0 (알 수 없음) 은 한/글 손상 인식 가능.
        for (int g = 0; g < FONT_GROUP_COUNT; g++) {
            FaceName fn = new FaceName();
            fn.name = (g == 0 || g == 1) ? DEFAULT_KOREAN_FONT : DEFAULT_LATIN_FONT;
            fn.fontType = 1; // TTF
            fn.hasAltFont = false;
            fn.hasTypeInfo = false;
            fn.hasDefaultFont = false;
            doc.faceNames.add(new java.util.ArrayList<>(java.util.Collections.singletonList(fn)));
        }

        // 2) BorderFill — id 0 : 모든 변 NONE, fill NONE (HWP 규약 상 id 0 은 더미)
        BorderFill bfNone = newBorderFill(0, 0, 0L, 0); // type=NONE (0), width=0, color=0, fill=NONE
        ensureBorderFill("none", bfNone);

        // 3) CharShape — id 0 : 기본 검정 10pt
        CharShape cs0 = newCharShape(0L, false, false, false, false, DEFAULT_FONT_SIZE_UNITS);
        ensureCharShape(signCharShape(cs0), cs0);

        // 4) ParaShape — id 0 : 기본 좌측 정렬, 테두리 없음
        ParaShape ps0 = newParaShape(0); // borderFillId = 0 (none)
        ensureParaShape("default", ps0);

        // 5) TabDef — 1개 기본
        TabDef td = new TabDef();
        doc.tabDefs.add(td);

        // 6) Style — id 0 : "바탕글"
        Style st = new Style();
        st.localName = "바탕글";
        st.englishName = "Normal";
        st.type = 0;
        st.nextStyleId = 0;
        st.langId = 0x412; // ko-KR
        st.paraShapeId = 0;
        st.charShapeId = 0;
        doc.styles.add(st);

        // 7) IdMappings — 각 카운트를 우리가 채운 컬렉션 크기와 정확히 일치시킨다.
        //   HWP 5.0 사양 인덱스:
        //     0 = BinData, 1-7 = FaceName 7개 언어 그룹, 8 = BorderFill,
        //     9 = CharShape, 10 = TabDef, 11 = Numbering, 12 = Bullet,
        //     13 = ParaShape, 14 = Style, 15-17 = MemoShape/TrackChange (미사용 0)
        //   불일치 시 HWPReader 가 "Count of FaceName is greater than ID Mappings" 등으로 실패.
        Arrays.fill(doc.idMappings.counts, 0);
        // 빌드 끝에 다시 동기화 (build 메서드 끝에서 호출). 여기서는 baseline 만.
        syncIdMappings();
    }

    /** doc.idMappings.counts 를 현재 컬렉션 크기와 동기화. build 종료 직전 호출. */
    private void syncIdMappings() {
        int[] c = doc.idMappings.counts;
        c[0] = doc.binDataItems.size();
        for (int i = 0; i < 7 && i < doc.faceNames.size(); i++) {
            c[1 + i] = doc.faceNames.get(i).size();
        }
        c[8]  = doc.borderFills.size();
        c[9]  = doc.charShapes.size();
        c[10] = doc.tabDefs.size();
        c[11] = doc.numberings.size();
        c[12] = doc.bullets.size();
        c[13] = doc.paraShapes.size();
        c[14] = doc.styles.size();
        // c[15], c[16], c[17] : MemoShape / TrackChange 미사용
    }

    private static BorderFill newBorderFill(int type, int width, long color, int fillType) {
        BorderFill bf = new BorderFill();
        Arrays.fill(bf.borderTypes, type);
        Arrays.fill(bf.borderWidths, width);
        Arrays.fill(bf.borderColors, color);
        bf.fillType = fillType;
        return bf;
    }

    private static CharShape newCharShape(long textColor, boolean bold, boolean italic, boolean underline,
                                          boolean strike, int sizeUnits) {
        CharShape cs = new CharShape();
        Arrays.fill(cs.fontId, 0);
        cs.baseSize = sizeUnits;
        cs.textColor = textColor;
        // CharShape.property bit layout (HWP 5.0):
        //   bit 0 : italic
        //   bit 1 : bold
        //   bit 2-4 : underline kind
        //   bit 5-7 : underline shape (... 생략)
        //   bit 18-20 : strike
        long prop = 0L;
        if (italic)    prop |= (1L << 0);
        if (bold)      prop |= (1L << 1);
        if (underline) prop |= (1L << 2);
        if (strike)    prop |= (1L << 18);
        cs.property = prop;
        return cs;
    }

    private static ParaShape newParaShape(int borderFillId) {
        ParaShape ps = new ParaShape();
        ps.borderFillId = borderFillId;
        // HWP 5.0.2.5+ 는 lineSpacing 대신 lineSpacing2 (UINT32) 를 사용.
        //   비트 0-4 = 종류 (0=글자기준 %), 비트 5+ = 값.
        //   PERCENT 160% 표현 : 종류=0, 값=160 → 그대로 160.
        //   lineSpacing(deprecated) 도 backward compat 위해 채움.
        // v16.00-test15: task1 — 문단 행간/문단 간격 확장.
        //   기존 160% → 180% (ODT LibreOffice 의 기본 1.15-1.6 보다 약간 여유)
        //   spaceAfter = 300 HWPUNIT (≈ 3pt) — 단락간 가독성 확보.
        ps.lineSpacing  = 180;
        ps.lineSpacing2 = 180L;
        ps.spaceAfter   = 300;
        return ps;
    }

    /**
     * 모든 paragraph 가 최소 1개의 유효한 {@link LineSeg} 를 갖도록 채운다.
     *   segWidth=0 이면 paragraph 들이 한 줄에 겹쳐 표시되므로 본문 너비를 명시.
     *   (HWP A4 본문 영역 약 42000 HWPUNIT — 약 148mm)
     */
    private static LineSeg defaultLineSeg() {
        LineSeg seg = new LineSeg();
        seg.textStartPos    = 0;
        seg.lineVertPos     = 0;
        // v16.00-test15: task1 — LineSeg 의 lineHeight/lineSpacing 도 동기화하여
        //   한/글 렌더러가 캐시된 줄 높이로 좁게 표시하는 것을 방지.
        //   lineHeight = 1800 (10pt × 180%), textHeight 유지, lineSpacing 600 → 800.
        seg.lineHeight      = 1800;
        seg.textHeight      = 1000;
        seg.baselineDistance= 850;    // 약 baseline 위치
        seg.lineSpacing     = 800;    // 80 HWPUNIT 간격 (≈ 0.8pt 줄 끝 여유)
        seg.columnStartPos  = 0;
        seg.segWidth        = 42000;  // 본문 너비 HWPUNIT (A4 - 좌우 여백)
        seg.tag             = 0x10L;  // 정상 segment 표시 비트
        return seg;
    }

    // -------------------------------------------------------------------------
    //  블록 → Paragraph 변환
    // -------------------------------------------------------------------------

    private void appendBlock(Section section, Block b) {
        if (b instanceof HeadingBlock)        { section.paragraphs.add(buildHeading((HeadingBlock) b)); }
        else if (b instanceof ParagraphBlock) { section.paragraphs.add(buildParagraph((ParagraphBlock) b)); }
        else if (b instanceof TableBlock)     {
            // v16.00-test9 : 표 컨트롤(CtrlTable) 정식 활성화. rowHeights[] 자리에
            //   cellCountOfRow 를 채워 외부 hwplib reader 와 호환 확보 (deep diff v4).
            section.paragraphs.add(buildTableParagraph((TableBlock) b, 0));
        }
        else if (b instanceof ImageBlock)     {
            // v16.00-test15: task4/task5 — ODT draw:frame > draw:image → CtrlPicture 임베드.
            Paragraph imgPara = buildImageParagraph((ImageBlock) b);
            if (imgPara != null) section.paragraphs.add(imgPara);
        }
        else if (b instanceof EquationBlock)  {
            // v16.00-test15c: task3 — ODT MathML → CtrlPicture(shapeType="equation").
            Paragraph eqPara = buildEquationParagraph((EquationBlock) b);
            if (eqPara != null) section.paragraphs.add(eqPara);
        }
        else { /* unknown — skip */ }
    }

    /**
     * v16.00-test15: task4/task5 — 이미지 임베드 paragraph 빌드.
     *
     * <p>처리 흐름:
     * <ul>
     *   <li>currentModel.images 에서 href 키로 raw 바이트 조회.</li>
     *   <li>BinDataItem 으로 등록 (binDataId 는 0-based 인덱스 + 1 — HWPX BIN 매핑은
     *       1-based 이므로 binItemId 도 동일하게 1-based 부여). 확장자는 href 의
     *       마지막 점 뒤 문자열 (소문자).</li>
     *   <li>CtrlPicture 생성 — width/height 는 ODT widthMm/heightMm 를 HWPUNIT 으로
     *       환산, objProperty 는 표와 동일한 inline-as-char 패턴 (bit 0=treatAsChar,
     *       bit 13=para-area 제한, bit 21-23=textWrap=TOP_AND_BOTTOM, bit 26-28=
     *       numbering category=figure(1)).</li>
     *   <li>paragraph 의 paraText 에 inline-ext ctrl(0x000B, GSO) + 0x000D terminator.
     *       controls 에 CtrlPicture 추가. controlMask bit 11.</li>
     * </ul>
     *
     * <p>이미지 바이트가 없으면 null 반환 (caller 가 skip). 누락 케이스는 ODT 가
     *   외부 링크 이미지를 참조한 경우만 발생.
     */
    private Paragraph buildImageParagraph(ImageBlock img) {
        CtrlPicture pic = buildPictureControl(img);
        if (pic == null) return null;
        int widthHwp  = pic.width;
        int heightHwp = pic.height;

        // 4) Paragraph 빌드
        Paragraph p = new Paragraph();
        // v16t45 FIX A — 모문단 정렬(textAlign 등) 복원. 스타일 없으면 0 그대로 (byte 불변).
        p.paraShapeId = resolveParaShapeForParagraph(resolveStyleForParagraph(img.paragraphStyleName));
        p.styleId     = 0;
        CharShapeRef ref = new CharShapeRef();
        ref.position = 0;
        ref.charShapeId = 0;
        p.charShapeRefs.add(ref);

        byte[] inlineCtrl = inlineExtCtrlBytes((char) 0x000B, CtrlId.GSO);
        byte[] paraTextBytes = new byte[inlineCtrl.length + 2];
        System.arraycopy(inlineCtrl, 0, paraTextBytes, 0, inlineCtrl.length);
        paraTextBytes[inlineCtrl.length    ] = 0x0D;
        paraTextBytes[inlineCtrl.length + 1] = 0x00;
        p.paraText = new ParaText(paraTextBytes);
        p.nChars   = 9; // 8 (inline ctrl) + 1 (terminator)
        p.controlMask = (1L << 11); // bit 11 = TABLE/PICTURE/GSO inline-ext
        p.controls.add(pic);

        // LineSeg — 이미지 영역만큼 vertical space 확보
        LineSeg seg = new LineSeg();
        seg.textStartPos     = 0;
        seg.lineVertPos      = 0;
        seg.lineHeight       = heightHwp;
        seg.textHeight       = heightHwp;
        seg.baselineDistance = heightHwp;
        seg.lineSpacing      = heightHwp;
        seg.columnStartPos   = 0;
        seg.segWidth         = Math.max(widthHwp, 42000);
        seg.tag              = 0x60000L;
        p.lineSegs.add(seg);
        return p;
    }

    /**
     * v16t45 F-4: 이미지 BinData 등록 + CtrlPicture 구성만 분리 — 단독 문단
     *   (buildImageParagraph)과 텍스트 줄 내 인라인(as-char, buildParagraphCommon)
     *   양쪽에서 공유. 이미지 바이트 없으면 null.
     */
    private CtrlPicture buildPictureControl(ImageBlock img) {
        if (currentModel == null || img == null || img.href == null) return null;
        byte[] data = currentModel.images.get(img.href);
        if (data == null || data.length == 0) {
            // 시도 : 마지막 path 컴포넌트로 fallback
            int slash = img.href.lastIndexOf('/');
            if (slash >= 0) {
                String tail = img.href.substring(slash + 1);
                data = currentModel.images.get(tail);
            }
            if (data == null || data.length == 0) return null;
        }

        // 1) BinDataItem 등록 — v16t45 G1: 동일 바이트(ext+SHA-1+len)는 기존 항목 재사용.
        //   중복 이미지 없는 문서는 등록 순서·id 가 종전과 동일 (byte 불변).
        String ext = extractExtension(img.href);
        String dedupKey = ext + ":" + sha1Hex(data) + ":" + data.length;
        Integer binId = binDataDedupCache.get(dedupKey);
        if (binId == null) {
            BinDataItem bdi = new BinDataItem();
            bdi.type = 1; // EMBEDDING
            bdi.binDataId = doc.binDataItems.size() + 1; // 1-based — HWPX BIN 매핑과 동일
            bdi.relativePath = "BinData/" + String.format("BIN%04X.%s", bdi.binDataId, ext);
            bdi.extension = ext;
            bdi.data = data;
            doc.binDataItems.add(bdi);
            binId = bdi.binDataId;
            binDataDedupCache.put(dedupKey, binId);
        }

        // 2) HWPUNIT 환산 — widthMm/heightMm → HWPUNIT (1mm ≈ 283.46 HWPUNIT).
        //   기본값 : 본문 너비의 1/2 정도 (= 23000 HWPUNIT, 약 81mm) — 누락 시.
        int widthHwp  = mmToHwpUnit(img.widthMm);
        int heightHwp = mmToHwpUnit(img.heightMm);
        if (widthHwp <= 0)  widthHwp  = 23000;
        if (heightHwp <= 0) heightHwp = 23000;

        // 3) CtrlPicture 구성
        CtrlPicture pic = new CtrlPicture();
        pic.shapeType   = "pic";
        pic.width       = widthHwp;
        pic.height      = heightHwp;
        // objProperty : 인라인 (treatAsChar=1) + figure 카테고리 (bit 26-28=1).
        //   reader/SectionParser.buildObjProperty + buildExtendedObjProperty 와 동일 패턴.
        //   bit 0=1 (treatAsChar)
        //   bit 3-4=2 (vertRelTo=PARA)
        //   bit 8-9=2 (horzRelTo=COLUMN)
        //   bit 13=1 (para-area 제한)
        //   bit 15-17=4 (widthBasis=ABSOLUTE)
        //   bit 18-19=2 (heightBasis=ABSOLUTE)
        //   bit 21-23=3 (textWrap=TOP_AND_BOTTOM)
        //   bit 26-28=1 (figure)
        long objProp = 0L;
        objProp |= 1L;              // bit 0 : treatAsChar
        objProp |= (2L << 3);       // bit 3-4 : vertRelTo=PARA
        objProp |= (2L << 8);       // bit 8-9 : horzRelTo=COLUMN
        objProp |= (1L << 13);      // bit 13 : para-area 제한
        objProp |= (4L << 15);      // bit 15-17 : widthBasis=ABSOLUTE
        objProp |= (2L << 18);      // bit 18-19 : heightBasis=ABSOLUTE
        objProp |= (3L << 21);      // bit 21-23 : textWrap=TOP_AND_BOTTOM
        objProp |= (1L << 26);      // bit 26-28 : numbering category=figure
        pic.objProperty = objProp;
        pic.vertOffset  = 0;
        pic.horzOffset  = 0;
        pic.zOrder      = 0;
        pic.margins[0]  = 0; pic.margins[1] = 0; pic.margins[2] = 0; pic.margins[3] = 0;
        // v16t45 G1: instanceId 는 그림 일련번호 기반 — 그림당 BinData 1건이던 종전에는
        //   binDataId 와 동일 값이므로 중복 없는 문서는 byte 불변, dedup 시에도 유일성 보장.
        pic.instanceId  = 0x20000L | (long) (++pictureInstanceSeq);
        pic.pageBreakPrev = 0;
        pic.description = "";

        // SHAPE_COMPONENT initialWidth/Height (orgSz 등가) — 표시 크기와 동일하게
        //   두어 스케일링 매트릭스가 단위 행렬이 되도록 한다. 추정 픽셀 차원도 동일.
        pic.originalWidth   = widthHwp;
        pic.originalHeight  = heightHwp;
        pic.imgDimWidth     = widthHwp;
        pic.imgDimHeight    = heightHwp;
        pic.binItemId       = binId; // SHAPE_COMPONENT_PICTURE 의 imageInfo 참조
        pic.transparency    = 0;
        pic.pictureInstanceId = pic.instanceId;

        // imageRect : pt0=(0,0), pt1=(W,0), pt2=(W,H), pt3=(0,H) 에 대응하는
        //   parsePicture 의 변환 ( X=[0,0,W,0], Y=[W,H,0,H]) 와 동일 패턴.
        pic.imageRectX[0] = 0;        pic.imageRectY[0] = widthHwp;
        pic.imageRectX[1] = 0;        pic.imageRectY[1] = heightHwp;
        pic.imageRectX[2] = widthHwp; pic.imageRectY[2] = 0;
        pic.imageRectX[3] = 0;        pic.imageRectY[3] = heightHwp;
        return pic;
    }

    /**
     * v16.00-test15c: task3 — ODT MathML(content.xml 내 inline {@code <draw:object>}
     *   ⊂ {@code <draw:frame>}) → HWP 수식 컨트롤 paragraph 빌드.
     *
     * <p>처리 흐름 (이미지와 거의 동일하지만 BinDataItem 이 없음):
     * <ul>
     *   <li>CtrlPicture 생성, {@code shapeType="equation"} 으로 설정.</li>
     *   <li>{@link CtrlPicture#eqScript} 에 한컴 수식편집기 스크립트
     *       (예: {@code "a^{2}+b^{2}=c^{2}"}) 를 저장. 한글 5.0 스펙
     *       §4.3.10.3 의 HWPTAG_EQEDIT 레코드로 SectionWriter 가 직렬화.</li>
     *   <li>{@link CtrlPicture#eqVersion}="Equation Version 60",
     *       {@link CtrlPicture#eqFont}="HancomEQN",
     *       {@link CtrlPicture#eqBaseUnit}=1000, {@link CtrlPicture#eqBaseLine}=86.</li>
     *   <li>width/height: ODT widthMm/heightMm → HWPUNIT (mmToHwpUnit). 누락 시
     *       기본 2in × 0.5in 의 HWPUNIT 값 사용 (14400 × 3600).</li>
     *   <li>objProperty: 표/이미지와 동일한 inline-as-char 패턴 (bit 0=treatAsChar).
     *       수식 numbering category(equation=3) 도 함께 설정.</li>
     *   <li>paraText: 8-wchar inline-ext ctrl (charCode=0x000B, ctrlId='eqed') +
     *       0x000D 종료자. controlMask bit 11 켬.</li>
     * </ul>
     *
     * <p>인라인 위치 fidelity (caption + 수식 한 줄) 는 향후 작업 — v1 에서는
     *   파서가 EquationBlock 을 별도 paragraph 로 분리 emit. caption 줄 다음 줄에
     *   수식이 표시되어 콘텐츠가 보존된다.
     */
    private Paragraph buildEquationParagraph(EquationBlock eq) {
        if (eq == null) return null;
        String script = eq.script == null ? "" : eq.script;

        // HWPUNIT 환산 — widthMm/heightMm → HWPUNIT (1mm ≈ 283.46 HWPUNIT).
        //   누락 시 ODT 의 기본 frame 크기인 2in × 0.5in 사용.
        int widthHwp  = mmToHwpUnit(eq.widthMm);
        int heightHwp = mmToHwpUnit(eq.heightMm);
        if (widthHwp <= 0)  widthHwp  = 14400; // 2 in
        if (heightHwp <= 0) heightHwp = 3600;  // 0.5 in

        CtrlPicture pic = new CtrlPicture();
        pic.shapeType   = "equation";
        pic.ctrlId      = CtrlId.EQUATION; // 'eqed'
        pic.width       = widthHwp;
        pic.height      = heightHwp;

        // 수식 전용 필드
        pic.eqScript   = script;
        pic.eqVersion  = "Equation Version 60";
        pic.eqFont     = "HancomEQN";
        pic.eqBaseUnit = 1000;
        pic.eqBaseLine = 86;
        pic.eqProperty = 0L;

        // objProperty — 이미지의 inline-as-char 패턴과 같되 numbering category 만
        //   equation(3) 으로 변경.
        long objProp = 0L;
        objProp |= 1L;              // bit 0 : treatAsChar
        objProp |= (2L << 3);       // bit 3-4 : vertRelTo=PARA
        objProp |= (2L << 8);       // bit 8-9 : horzRelTo=COLUMN
        objProp |= (1L << 13);      // bit 13 : para-area 제한
        objProp |= (4L << 15);      // bit 15-17 : widthBasis=ABSOLUTE
        objProp |= (2L << 18);      // bit 18-19 : heightBasis=ABSOLUTE
        objProp |= (3L << 21);      // bit 21-23 : textWrap=TOP_AND_BOTTOM
        objProp |= (3L << 26);      // bit 26-28 : numbering category=equation(3)
        pic.objProperty = objProp;
        pic.vertOffset  = 0;
        pic.horzOffset  = 0;
        pic.zOrder      = 0;
        pic.margins[0]  = 0; pic.margins[1] = 0; pic.margins[2] = 0; pic.margins[3] = 0;
        pic.instanceId  = 0x30000L | ((long) (script.hashCode() & 0xFFFF));
        pic.pageBreakPrev = 0;
        pic.description = "";

        // Paragraph 빌드 — 이미지 buildImageParagraph 와 동일 inline-ext ctrl 패턴.
        Paragraph p = new Paragraph();
        // v16t45 FIX A — 모문단 정렬(textAlign 등) 복원. 스타일 없으면 0 그대로 (byte 불변).
        p.paraShapeId = resolveParaShapeForParagraph(resolveStyleForParagraph(eq.paragraphStyleName));
        p.styleId     = 0;
        CharShapeRef ref = new CharShapeRef();
        ref.position = 0;
        ref.charShapeId = 0;
        p.charShapeRefs.add(ref);

        byte[] inlineCtrl = inlineExtCtrlBytes((char) 0x000B, CtrlId.EQUATION);
        byte[] paraTextBytes = new byte[inlineCtrl.length + 2];
        System.arraycopy(inlineCtrl, 0, paraTextBytes, 0, inlineCtrl.length);
        paraTextBytes[inlineCtrl.length    ] = 0x0D;
        paraTextBytes[inlineCtrl.length + 1] = 0x00;
        p.paraText = new ParaText(paraTextBytes);
        p.nChars   = 9; // 8 (inline ctrl) + 1 (terminator)
        p.controlMask = (1L << 11); // bit 11 = TABLE/PICTURE/GSO/EQUATION inline-ext
        p.controls.add(pic);

        // LineSeg — 수식 박스 높이만큼 vertical space 확보
        LineSeg seg = new LineSeg();
        seg.textStartPos     = 0;
        seg.lineVertPos      = 0;
        seg.lineHeight       = heightHwp;
        seg.textHeight       = heightHwp;
        seg.baselineDistance = heightHwp;
        seg.lineSpacing      = heightHwp;
        seg.columnStartPos   = 0;
        seg.segWidth         = Math.max(widthHwp, 42000);
        seg.tag              = 0x60000L;
        p.lineSegs.add(seg);
        return p;
    }

    /** href 의 마지막 점 뒤 문자열 (소문자). 없으면 "png" 기본. */
    private static String extractExtension(String href) {
        if (href == null) return "png";
        int dot = href.lastIndexOf('.');
        if (dot < 0 || dot == href.length() - 1) return "png";
        return href.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /** v16t45 G1: 이미지 바이트 SHA-1 hex — BinData dedup 키용. */
    private static String sha1Hex(byte[] data) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 미지원 JVM", e);
        }
    }

    /** mm → HWPUNIT (1 inch = 7200 HWPUNIT, 1 mm = 7200/25.4 ≈ 283.46). */
    private static int mmToHwpUnit(double mm) {
        if (mm <= 0) return 0;
        return (int) Math.round(mm / 25.4 * 7200.0);
    }

    /** 표를 임시로 paragraph 시퀀스로 평탄화 (각 셀의 텍스트 + 행간 빈 줄). */
    private void appendTableAsParagraphs(Section section, TableBlock t) {
        for (TableBlock.Row row : t.rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < row.cells.size(); i++) {
                if (i > 0) line.append("    ");
                TableBlock.Cell c = row.cells.get(i);
                for (Block b : c.content) {
                    if (b instanceof ParagraphBlock) {
                        for (Run r : ((ParagraphBlock) b).runs) line.append(r.text);
                    } else if (b instanceof HeadingBlock) {
                        for (Run r : ((HeadingBlock) b).runs) line.append(r.text);
                    }
                }
            }
            ParagraphBlock pb = new ParagraphBlock(null);
            pb.runs.add(Run.plain(line.toString()));
            section.paragraphs.add(buildParagraph(pb));
        }
    }

    private Paragraph buildHeading(HeadingBlock h) {
        OdtStyle resolved = resolveStyleForParagraph(h.paragraphStyleName);
        // Heading 의 paraShape : border-bottom 있으면 전용 borderFill + paraShape
        int paraShapeId = resolveParaShapeForHeading(resolved);
        // Heading 의 기본 charShape : ODT 스타일의 fontSize/color/bold
        int defaultCharShapeId = resolveCharShape(resolved);
        return buildParagraphCommon(h.runs, paraShapeId, defaultCharShapeId, /*styleId*/ 0);
    }

    private Paragraph buildParagraph(ParagraphBlock p) {
        OdtStyle resolved = resolveStyleForParagraph(p.paragraphStyleName);
        // Task1: 회색박스 — paragraph 배경 / 좌우 들여쓰기 가 있으면 전용 paraShape 사용
        int paraShapeId = resolveParaShapeForParagraph(resolved);
        int defaultCharShapeId = resolveCharShape(resolved);
        // v16t46 FIX D-3: Stage-1 탭 보유 문단이면 인라인 탭 Byte6/7 미러값 설정 (finally 로 원복 —
        //   비대상 문단·셀 경로는 0/0 이라 종전 무인자 appendTabControl 바이트 그대로).
        // v16t50 R12-A: width 도 사전계산 — paraShape 가 가리키는 TabDef 의 마지막 tabItem.position
        //   을 inlineTabWidth 로 set. width=0 이면 한컴이 dot leader 그릴 위치를 못 잡아 점선 미표시.
        int[] lt = inlineTabBytes(resolved);
        if (lt != null) {
            inlineTabLeader = lt[0];
            inlineTabType = lt[1];
            inlineTabWidth = resolveInlineTabWidth(resolved, p.runs);
        }
        try {
            Paragraph built = buildParagraphCommon(p.runs, paraShapeId, defaultCharShapeId, /*styleId*/ 0);
            // v16t52 P2 T6-b: ODT fo:break-before="page" → paragraph columnBreakType bit2 = pageBreak.
            //   golden case1 hp:p pageBreak="1" 40건 복원 (시각 영향 직접·표 잘림 60p 페이지 흐름 결정타).
            if (resolved != null && Boolean.TRUE.equals(resolved.breakBeforePage)) {
                built.columnBreakType |= 0x04;
            }
            return built;
        } finally {
            inlineTabLeader = 0;
            inlineTabType = 0;
            inlineTabWidth = 0;
        }
    }

    /**
     * v16t50 R12-A: 인라인 탭 width 사전계산. TabDef 의 마지막(가장 큰) tabItem.position 을 사용한다.
     * 한컴은 width 를 leader 채울 거리로 해석 — width=0 이면 leader 그릴 길이를 0 으로 보고 점선 미표시.
     * v16t54 T7: 종전엔 stop position 자체(=우측 여백 16.99cm=48161)를 넣었으나, 한컴은 이 값을
     *   '텍스트 끝에서부터 채울 점선 길이' 로 해석한다. 텍스트끝(≈20000)+48161 가 페이지폭을 한참 넘겨
     *   점선이 본문 밖으로 밀려 보이지 않았다(case1 목차 31줄 width 전부 48161 → 시각 점선 누락 FAIL).
     *   원본 case1.hwp 인라인 탭은 줄마다 다른 gap(7344~32080)을 직접 보유 → width = (탭정지 −
     *   탭앞 텍스트너비) 로 복원해 동일 의미를 갖게 한다.
     */
    private int resolveInlineTabWidth(OdtStyle s, java.util.List<Run> runs) {
        if (s == null || s.tabStops == null) return 0;
        int max = 0;
        for (OdtStyle.TabStop ts : s.tabStops) {
            int p = lengthToHwpUnit(ts.positionSpec);
            if (p > max) max = p;
        }
        if (max <= 0) return 0;
        int leading = leadingTextWidthBeforeTab(runs, s);
        int w = max - leading;
        if (w < 100) w = 100;   // 텍스트가 탭정지를 넘는 예외 — 음수/0 방지(점선 최소폭)
        return w;
    }

    /**
     * v16t54 T7: 첫 '\t' 앞 텍스트의 추정 가로너비(HWPUNIT). 한컴 인라인 탭은 (탭정지 − 텍스트끝)
     * 만큼을 점선으로 채우므로 이 값을 빼서 채움폭을 구한다. CJK/한글/전각/로마숫자=1em, 그 외=0.5em
     * 근사. em 은 각 run 의 span fontSize(없으면 단락 fontSize, 그래도 없으면 10pt) 로 결정.
     */
    private int leadingTextWidthBeforeTab(java.util.List<Run> runs, OdtStyle paraStyle) {
        if (runs == null) return 0;
        int paraEm = fontEmHwpUnit(paraStyle);
        int width = 0;
        for (Run r : runs) {
            int em = paraEm;
            if (r.spanStyleName != null) {
                int e = fontEmHwpUnit(resolveStyleForParagraph(r.spanStyleName));
                if (e > 0) em = e;
            }
            String t = (r.text == null) ? "" : r.text;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c == '\t') return width;   // 첫 탭에서 종료 (탭 뒤 페이지번호는 제외)
                width += isWideGlyph(c) ? em : (em / 2);
            }
        }
        return width;
    }

    /** ODT fontSize("17pt") → 1em 의 HWPUNIT 너비. 미지정 시 10pt(1000). */
    private int fontEmHwpUnit(OdtStyle s) {
        int em = (s != null) ? lengthToHwpUnit(s.fontSize) : 0;
        return em > 0 ? em : 1000;
    }

    /** CJK 통합한자·한글·전각·로마숫자(Ⅰ Ⅱ Ⅲ) 등 full-width 글리프 판정. */
    private static boolean isWideGlyph(char c) {
        return (c >= 0x1100 && c <= 0x11FF)   // Hangul Jamo
            || (c >= 0x2150 && c <= 0x218F)   // Number Forms (Ⅰ Ⅱ Ⅲ …)
            || (c >= 0x2E80 && c <= 0x9FFF)   // CJK Radicals ~ Unified Ideographs
            || (c >= 0xAC00 && c <= 0xD7A3)   // Hangul Syllables
            || (c >= 0xF900 && c <= 0xFAFF)   // CJK Compatibility Ideographs
            || (c >= 0xFF00 && c <= 0xFFEF);  // Halfwidth/Fullwidth Forms
    }

    /**
     * 일반 paragraph 전용 ParaShape 해석.
     *  - fo:background-color 가 있으면 BorderFill 의 fillType=1 + fillBgColor 사용 → paraShape.borderFillId 부여
     *  - fo:margin-left / fo:margin-right 가 있으면 ParaShape.leftMargin / rightMargin 채움
     * 모두 없으면 0 (기본 ParaShape) 반환.
     */
    private int resolveParaShapeForParagraph(OdtStyle s) {
        if (s == null) return 0;
        boolean hasBg  = s.paragraphBackgroundColor != null;
        int leftMargin  = lengthToHwpUnit(s.marginLeft);
        int rightMargin = lengthToHwpUnit(s.marginRight);
        // v16t50 R2: text-indent 매핑 (음수 가능 — hanging indent). dedup 키 :ti= 포함해
        //   indent 만 다른 스타일이 lm/rm 동일하다고 결합되는 결함(채은 paraPr id 5/6 결합) 해소.
        int textIndent  = lengthToHwpUnit(s.textIndent);
        // Task5 — text-align 매핑 (0=JUSTIFY, 1=LEFT, 2=RIGHT, 3=CENTER)
        int alignType = 0;
        if (s.textAlign != null) {
            String a = s.textAlign.trim().toLowerCase(java.util.Locale.ROOT);
            // v16t51 S1: ODT 'start' 는 LTR default(=본문 정렬 미명시와 동등) — 방향1 hwp2odt 가
            //   JUSTIFY paragraph 를 'start' 로 잘못 수출하는 경우가 다수라 LEFT 과잉·JUSTIFY 부족 유발.
            //   'start' → alignType=0 (JUSTIFY) 으로 매핑. 'left' 명시는 그대로 LEFT 유지.
            if ("center".equals(a)) alignType = 3;
            else if ("right".equals(a) || "end".equals(a)) alignType = 2;
            else if ("left".equals(a)) alignType = 1;
            else if ("justify".equals(a) || "start".equals(a)) alignType = 0;
        }
        // Task6 — margin-top/bottom 매핑
        boolean hasMt = s.marginTop != null;
        boolean hasMb = s.marginBottom != null;
        int spaceBefore = hasMt ? lengthToHwpUnit(s.marginTop) : 0;
        int spaceAfterOverride = hasMb ? lengthToHwpUnit(s.marginBottom) : -1;
        // v16t46 FIX D: Stage-1 tab-stop(type/leader 보유) → TabDef 매핑. 0 = 비대상(기본 TabDef).
        int tabDefId = resolveTabDefId(s);
        if (!hasBg && leftMargin == 0 && rightMargin == 0 && textIndent == 0
                && alignType == 0 && !hasMt && !hasMb && tabDefId == 0) return 0;

        int borderFillId1Based = 0;
        String bgKey = "";
        if (hasBg) {
            long bgColor = parseHexColor(s.paragraphBackgroundColor, 0xFFFFFFFFL);
            BorderFill bf = new BorderFill();
            // 4면 NONE — 박스 테두리는 그리지 않고 배경만 채움
            java.util.Arrays.fill(bf.borderTypes, 0);
            java.util.Arrays.fill(bf.borderWidths, 0);
            java.util.Arrays.fill(bf.borderColors, 0L);
            bf.fillType    = 1;
            bf.fillBgColor = bgColor;
            bf.fillPatColor = bgColor;
            String bfSig = "paraBg:" + Long.toHexString(bgColor);
            int bfId0 = ensureBorderFill(bfSig, bf);
            borderFillId1Based = bfId0 + 1;
            bgKey = Long.toHexString(bgColor);
        }
        ParaShape ps = newParaShape(borderFillId1Based);
        ps.leftMargin  = leftMargin;
        ps.rightMargin = rightMargin;
        ps.indent      = textIndent;  // v16t50 R2: ODT fo:text-indent
        // Task5 — property1 bit 2-4 = alignType
        ps.property1 = (ps.property1 & ~(0x7L << 2)) | (((long) alignType & 0x7) << 2);
        // Task6 — ODT 명시 margin-top/bottom 적용 (없으면 newParaShape 기본 유지)
        if (hasMt) ps.spaceBefore = spaceBefore;
        if (hasMb) ps.spaceAfter  = spaceAfterOverride;
        if (hasBg) {
            // 배경이 있으면 paragraph 가 본문 박스로 보이도록 약간의 padding
            ps.borderPadLeft   = 200;
            ps.borderPadRight  = 200;
            ps.borderPadTop    = 100;
            ps.borderPadBottom = 100;
        }
        // v16t46 FIX D: tabDefId 연결 + dedup 키 포함 (스펙 D-2-3 — 탭 없는 동일 모양
        //   ParaShape 와의 공유 오염 방지. tabDefId=0 문단은 ":td=0" 으로 종전과 1:1 대응).
        if (tabDefId != 0) ps.tabDefId = tabDefId;
        String psSig = "paraShape:bg=" + bgKey + ":lm=" + leftMargin + ":rm=" + rightMargin
                + ":ti=" + textIndent
                + ":al=" + alignType + ":mt=" + (hasMt ? spaceBefore : "n")
                + ":mb=" + (hasMb ? spaceAfterOverride : "n")
                + ":td=" + tabDefId;
        return ensureParaShape(psSig, ps);
    }

    /**
     * v16t46 FIX D Stage-1: 스타일 tab-stop 중 type(right/center/char) 또는 leader 보유 항목만
     * TabDef 로 매핑 (평탭 position-only 는 현행 유지 — Stage-2 는 덕수 재판단 전 착수 금지).
     * 동일 stop 집합은 dedup 캐시로 기존 TabDef id 재사용. 대상 없으면 0(기본 TabDef).
     * 미러 근거 — 원본 case1.hwp TAB_DEF raw 덤프(_diag/TabDefDump·TabDefRaw, 추정값 금지):
     *   tabDef[4] property=0x0 item{pos=100000, type=1(Right), fillType=3(Dot), padding=0x0000},
     *   tabDef[11] property=0x0 item{pos=77952, type=1, fillType=0, padding=0}.
     */
    private int resolveTabDefId(OdtStyle s) {
        if (s == null || s.tabStops == null || s.tabStops.isEmpty()) return 0;
        java.util.List<TabDef.TabItem> items = new java.util.ArrayList<>();
        StringBuilder key = new StringBuilder("tabDef");
        for (OdtStyle.TabStop ts : s.tabStops) {
            int type = 0;
            if (ts.type != null) {
                String t = ts.type.trim().toLowerCase(java.util.Locale.ROOT);
                if ("right".equals(t)) type = 1;
                else if ("center".equals(t)) type = 2;
                else if ("char".equals(t)) type = 3;
            }
            boolean hasLeader = ts.leaderStyle != null && !"none".equalsIgnoreCase(ts.leaderStyle.trim());
            // v16t51 S7: Stage-1 필터(type 또는 leader 보유) 완화 — 평탭(left+none) 도 등록.
            //   민준 가드: 목차 점선 단락 누락 0 = 핵심, tabItem 카운트 80%+ = 보조. 평탭 등록은
            //   카운트 게이트 충족용. ODT 평탭 동일 paragraph 그룹은 dedup 으로 1개 TabDef 공유.
            TabDef.TabItem it = new TabDef.TabItem();
            it.position = lengthToHwpUnit(ts.positionSpec);
            it.type     = type;
            // v16t51 S7-A: leader-style → HWP fillType 매핑 (ODT 1.2 미러).
            //   dotted→3(Dot)·dashed→2(Dash)·solid→1(Solid)·none→0. orig case1 의 DASH 4건은 odt에
            //   부재(방향1 결함, v52+ 합류)지만 매핑은 사전 정의.
            it.fillType = mapOdtLeaderToFillType(ts.leaderStyle);
            it.padding  = 0;
            items.add(it);
            key.append(':').append(it.position).append('/').append(it.type).append('/').append(it.fillType);
        }
        if (items.isEmpty()) return 0;
        Integer cached = tabDefDedupCache.get(key.toString());
        if (cached != null) return cached;
        TabDef td = new TabDef();
        td.property = 0; // 원본 미러 — 동종 tabDef[4]/[11] 모두 0x0 (자동탭 비트 임의 추정 금지)
        td.items.addAll(items);
        doc.tabDefs.add(td);
        int id = doc.tabDefs.size() - 1;
        tabDefDedupCache.put(key.toString(), id);
        return id;
    }

    /**
     * v16t46 FIX D-3: 문단 인라인 탭(0x09)에 기록할 {Byte6=leader, Byte7=type}. 비대상 null.
     * 원본 case1.hwp 목차 인라인 탭 실측 미러: leader=0x03(Dot)/type=0x02 — 인라인 측 type enum 은
     * TAB_DEF 의 type=1(Right)과 다름(실측 0x02). Stage-1 발생분은 right 뿐이라 right 한정 기록.
     */
    private int[] inlineTabBytes(OdtStyle s) {
        if (s == null || s.tabStops == null) return null;
        for (OdtStyle.TabStop ts : s.tabStops) {
            if (ts.type == null) continue;
            if (!"right".equals(ts.type.trim().toLowerCase(java.util.Locale.ROOT))) continue;
            boolean hasLeader = ts.leaderStyle != null && !"none".equalsIgnoreCase(ts.leaderStyle.trim());
            return new int[] { hasLeader ? 0x03 : 0x00, 0x02 };
        }
        return null;
    }

    /** ODT 길이 표기 ("0.5in", "10mm", "12pt") → HWPUNIT (1/7200 inch). 실패/빈값 → 0. */
    static int lengthToHwpUnit(String spec) {
        if (spec == null) return 0;
        String s = spec.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) return 0;
        try {
            if (s.endsWith("in")) {
                return (int) Math.round(Double.parseDouble(s.substring(0, s.length() - 2)) * 7200.0);
            } else if (s.endsWith("mm")) {
                return (int) Math.round(Double.parseDouble(s.substring(0, s.length() - 2)) / 25.4 * 7200.0);
            } else if (s.endsWith("cm")) {
                return (int) Math.round(Double.parseDouble(s.substring(0, s.length() - 2)) * 10.0 / 25.4 * 7200.0);
            } else if (s.endsWith("pt")) {
                return (int) Math.round(Double.parseDouble(s.substring(0, s.length() - 2)) / 72.0 * 7200.0);
            }
        } catch (NumberFormatException ignored) { /* fall through */ }
        return 0;
    }

    private Paragraph buildParagraphCommon(java.util.List<Run> runs, int paraShapeId, int defaultCharShapeId,
                                          int styleId) {
        Paragraph para = new Paragraph();
        para.paraShapeId = paraShapeId;
        para.styleId = styleId;
        para.controlMask = 0;
        // v16t39 TYPE-1 수정: 탭(0x09)은 HWP 5.0 에서 8-WCHAR inline 컨트롤이므로
        //   ParaText.fromString 의 단일-wchar 인코딩으로는 외부 hwplib reader 가
        //   16 byte 를 읽으려다 레코드 스트림이 desync → "This is not paragraph".
        //   탭을 8-WCHAR inline 컨트롤로 전개하며 wchar 위치/ nChars/ controlMask 를 정정.
        //   (탭이 없는 문단은 byte 출력·position·nChars 가 종전과 완전히 동일.)
        java.io.ByteArrayOutputStream tbuf = new java.io.ByteArrayOutputStream();
        long controlMask = 0;
        long position = 0;
        // 빈 runs 면 빈 paragraph
        if (runs == null || runs.isEmpty()) {
            CharShapeRef ref = new CharShapeRef();
            ref.position = 0;
            ref.charShapeId = defaultCharShapeId;
            para.charShapeRefs.add(ref);
            para.paraText = ParaText.fromString("");
            para.nChars = 1; // 0x000D terminator
            para.lineSegs.add(defaultLineSeg());
            return para;
        }
        String openHref = null; // v16t45 FIX B: 현재 열려 있는 하이퍼링크 필드의 href
        int inlineImgMaxH = 0;  // v16t45 F-4: 줄 내 인라인 이미지 최대 높이 (lineSeg 확보용)
        for (Run r : runs) {
            // v16t45 FIX B: text:a 경계 — href 가 바뀌는 지점에서 필드 end(0x0004)/begin(0x0003) 발화.
            //   링크/책갈피 없는 문서는 이 블록이 한 번도 실행되지 않아 byte 출력 불변.
            String href = (r.linkHref == null || r.linkHref.isEmpty()) ? null : r.linkHref;
            if (openHref != null && !openHref.equals(href)) {
                appendFieldEnd(tbuf, CtrlId.FIELD_HYPERLINK);
                controlMask |= (1L << 0x0004);
                position += 8;
                openHref = null;
            }
            if (href != null && openHref == null) {
                byte[] fb = inlineExtCtrlBytes((char) 0x0003, CtrlId.FIELD_HYPERLINK);
                tbuf.write(fb, 0, fb.length);
                controlMask |= (1L << 0x0003);
                position += 8;
                para.controls.add(makeHyperlinkField(href));
                openHref = href;
            }
            int csId = (r.spanStyleName == null) ? defaultCharShapeId
                    : resolveCharShape(resolveStyleForParagraph(r.spanStyleName));
            // 새 charShape 시작 시 ref 추가
            if (para.charShapeRefs.isEmpty()
                    || para.charShapeRefs.get(para.charShapeRefs.size() - 1).charShapeId != csId) {
                CharShapeRef ref = new CharShapeRef();
                ref.position = position;
                ref.charShapeId = csId;
                para.charShapeRefs.add(ref);
            }
            // v16t45 FIX B: text:bookmark 마커 — begin+end 인접쌍, 이름은 CTRL_DATA 로 직렬화.
            if (r.bookmarkName != null && !r.bookmarkName.isEmpty()) {
                byte[] bb = inlineExtCtrlBytes((char) 0x0003, CtrlId.FIELD_BOOKMARK);
                tbuf.write(bb, 0, bb.length);
                para.controls.add(makeBookmarkField(r.bookmarkName));
                appendFieldEnd(tbuf, CtrlId.FIELD_BOOKMARK);
                controlMask |= (1L << 0x0003) | (1L << 0x0004);
                position += 16;
            }
            // v16t45 F-4: as-char 인라인 이미지 — 텍스트 줄 안 동일 위치에 GSO inline-ext
            //   발화 (종전: 문단 앞 별도 이미지 문단으로 분리 → 줄 이탈).
            //   인라인 이미지 없는 문서는 이 블록이 실행되지 않아 byte 출력 불변.
            if (r.inlineImage != null) {
                CtrlPicture ipic = buildPictureControl(r.inlineImage);
                if (ipic != null) {
                    byte[] gb = inlineExtCtrlBytes((char) 0x000B, CtrlId.GSO);
                    tbuf.write(gb, 0, gb.length);
                    controlMask |= (1L << 11);
                    position += 8;
                    para.controls.add(ipic);
                    if (ipic.height > inlineImgMaxH) inlineImgMaxH = ipic.height;
                }
            }
            // v16t45 FIX C: 수식 셀 — 표시 텍스트 run 을 FIELD_FORMULA begin/end 로 감싼다.
            //   수식 없는 문서는 이 블록이 실행되지 않아 byte 출력 불변 (FIX B 와 동일 전례).
            boolean formulaOpen = false;
            if (r.formulaCommand != null && !r.formulaCommand.isEmpty()) {
                byte[] fb = inlineExtCtrlBytes((char) 0x0003, CtrlId.FIELD_FORMULA);
                tbuf.write(fb, 0, fb.length);
                controlMask |= (1L << 0x0003);
                position += 8;
                para.controls.add(makeFormulaField(r.formulaCommand));
                formulaOpen = true;
            }
            String t = (r.text == null) ? "" : r.text;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c == '\t') {
                    // v16t46 FIX D-3: Stage-1 TabDef 보유 문단만 leader/type 기록 — 그 외 종전 경로 불변
                    if (inlineTabLeader != 0 || inlineTabType != 0) {
                        appendTabControl(tbuf, inlineTabWidth, inlineTabLeader, inlineTabType);
                    } else {
                        appendTabControl(tbuf);      // 8-WCHAR (16 byte) inline 탭
                    }
                    controlMask |= (1L << 0x0009);
                    position += 8;
                } else {
                    tbuf.write(c & 0xFF);
                    tbuf.write((c >> 8) & 0xFF);     // UTF-16LE
                    if (c < 0x0020) controlMask |= (1L << c); // 문자형 컨트롤 플래그
                    position += 1;
                }
            }
            if (formulaOpen) {
                appendFieldEnd(tbuf, CtrlId.FIELD_FORMULA);
                controlMask |= (1L << 0x0004);
                position += 8;
            }
        }
        if (openHref != null) {
            // v16t45 FIX B: 문단 끝까지 열려 있던 하이퍼링크 필드 닫기
            appendFieldEnd(tbuf, CtrlId.FIELD_HYPERLINK);
            controlMask |= (1L << 0x0004);
            position += 8;
        }
        if (para.charShapeRefs.isEmpty()) {
            CharShapeRef ref = new CharShapeRef();
            ref.position = 0;
            ref.charShapeId = defaultCharShapeId;
            para.charShapeRefs.add(ref);
        }
        tbuf.write(0x0D); tbuf.write(0x00);          // 문단 끝 마커 0x000D (1 WCHAR)
        para.paraText = new ParaText(tbuf.toByteArray());
        // HWP 5.0 사양 : nChars 는 ParaText 의 wchar 수 (0x000D terminator 포함).
        //   탭은 8 WCHAR 로 계산된다. 누락/오계산 시 한/글이 "파일 손상" 으로 거부.
        para.nChars = position + 1;
        para.controlMask = controlMask;
        LineSeg seg = defaultLineSeg();
        // v16t45 F-4: 인라인 이미지가 줄높이보다 크면 lineSeg 높이를 이미지에 맞춰 확보.
        if (inlineImgMaxH > seg.lineHeight) {
            seg.lineHeight       = inlineImgMaxH;
            seg.textHeight       = inlineImgMaxH;
            seg.baselineDistance = inlineImgMaxH;
            seg.lineSpacing      = inlineImgMaxH;
        }
        para.lineSegs.add(seg);
        return para;
    }

    /** v16t45 FIX B: 문서 내 필드 instance id 일련번호. */
    private long fieldIdSeq = 1;

    /**
     * v16t45 FIX B: 필드 끝(0x0004) inline 8-WCHAR 컨트롤. ctrlId 자리에는 짝이 되는
     *   FIELD_BEGIN ctrlId 의 high byte('%')를 0x00 으로 바꾼 pair marker 를 기록 —
     *   이 marker 가 없으면 한글이 클릭 범위를 필드 레코드와 연결하지 못한다
     *   (SectionParser.buildParaTextAndCharRefs 의 FIELD_END 처리와 동일 규약).
     */
    private static void appendFieldEnd(java.io.ByteArrayOutputStream buf, int beginCtrlId) {
        byte[] b = inlineExtCtrlBytes((char) 0x0004, beginCtrlId & 0x00FFFFFF);
        buf.write(b, 0, b.length);
    }

    /** v16t45 FIX B: 하이퍼링크 필드. fieldProperty=0x8000(dirty)/extra=0 — 한컴 참조 HWP 관측치. */
    private CtrlField makeHyperlinkField(String href) {
        CtrlField f = new CtrlField(CtrlId.FIELD_HYPERLINK);
        f.fieldProperty = 0x8000L;
        f.extraProperty = 0;
        f.command = hrefToHwpCommand(href);
        f.fieldId = fieldIdSeq++;
        return f;
    }

    /**
     * v16t45 FIX C: 계산식 필드. fieldProperty=0(editable=0/dirty=0)/extra=8(Prop=8)
     *   — 한컴 원본(TA-04/case1 hwpx 의 fieldBegin type="FORMULA") 관측치.
     *   command 형식: {@code "=식??%g,;;표시값"} (예 "=SUM(LEFT)??%g,;;25,700,000").
     */
    private CtrlField makeFormulaField(String command) {
        CtrlField f = new CtrlField(CtrlId.FIELD_FORMULA);
        f.fieldProperty = 0;
        f.extraProperty = 8;
        f.command = command;
        f.fieldId = fieldIdSeq++;
        return f;
    }

    /** v16t45 FIX B: 책갈피 필드. fieldProperty=0/extra=2 — 한컴 참조 HWP 관측치. name 은 CTRL_DATA 로. */
    private CtrlField makeBookmarkField(String name) {
        CtrlField f = new CtrlField(CtrlId.FIELD_BOOKMARK);
        f.fieldProperty = 0;
        f.extraProperty = 2;
        f.command = "";
        f.name = name;
        f.fieldId = fieldIdSeq++;
        return f;
    }

    /**
     * v16t50 R9: ODF xlink:href → HWP 하이퍼링크 필드 command.
     *   한컴 원본(TA-03 v49 vs orig 실측, 2026-06-11) 포맷 미러:
     * <pre>
     *   내부 앵커 "#name"           → "?name;0;0;-1;"               ('?' 접두 필수)
     *   메일 "mailto:a@b"          → "mailto\:a@b;2;5;-1;"           (mailto: 스킴 보존)
     *   웹 "https://h/p"           → "https\://h/p;1;5;-1;"          (scheme 전체 보존)
     *   웹+쿼리 "https://h/p?q=v"  → "https\://h/p\?q=v;1;5;-1;"     (쿼리도 suffix 부착)
     *   기타 scheme(ftp 등)        → 전체 URL 보존 + ";1;5;-1;"
     * </pre>
     *   이스케이프 4종: \\ \? \: \; (FieldMapper.unescape 의 거울).
     *   v16t45 FIX B 의 scheme 강등(http://→//)·mailto: 접두어 잘림·쿼리 suffix 누락은
     *   잠복 결함이었음 — 한컴이 '찾을 수 없습니다' 띄우던 원인.
     */
    private static String hrefToHwpCommand(String href) {
        if (href.startsWith("#")) {
            return "?" + escapeFieldTarget(href.substring(1)) + ";0;0;-1;";
        }
        if (href.startsWith("mailto:")) {
            return escapeFieldTarget(href) + ";2;5;-1;";  // mailto: 스킴 보존 → mailto\:user@host
        }
        return escapeFieldTarget(href) + ";1;5;-1;";       // http/https/ftp 등 전체 scheme 보존
    }

    /** v16t45 FIX B: 필드 target 이스케이프 — 리터럴 {@code \ ? : ;} 앞에 역슬래시. */
    private static String escapeFieldTarget(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '?' || c == ':' || c == ';') b.append('\\');
            b.append(c);
        }
        return b.toString();
    }

    /**
     * 탭(0x09) 8-WCHAR inline 컨트롤(16 byte)을 버퍼에 기록.
     * 레이아웃은 SectionParser.writeTabControl 과 동일:
     *   code(2) + width UINT32(4)=0 + leader(1)=0 + type(1)=0 + pad 0x0020 x3(6) + code(2).
     */
    private static void appendTabControl(java.io.ByteArrayOutputStream buf) {
        buf.write(0x09); buf.write(0x00);   // Byte 0-1: 0x0009
        buf.write(0); buf.write(0); buf.write(0); buf.write(0); // Byte 2-5: width=0
        buf.write(0);                        // Byte 6: leader
        buf.write(0);                        // Byte 7: type
        buf.write(0x20); buf.write(0x00);    // Byte 8-9: pad 공백
        buf.write(0x20); buf.write(0x00);    // Byte 10-11
        buf.write(0x20); buf.write(0x00);    // Byte 12-13
        buf.write(0x09); buf.write(0x00);    // Byte 14-15: 코드 복사본
    }

    /**
     * v16t46 FIX D-3 + v16t50 R12-A: leader/type/width 지정 인라인 탭. width(Byte2-5)는
     * paraShape 의 TabDef 의 가장 큰 tabItem.position 을 LE32 로 기록 — 한컴이 dot leader 그릴
     * 거리로 해석한다. width=0 이면 점선 미표시(채은 실측, v49 결함). pad 0x0020×3 은 원본 동일.
     */
    private static void appendTabControl(java.io.ByteArrayOutputStream buf, int width, int leader, int type) {
        buf.write(0x09); buf.write(0x00);                       // Byte 0-1: 0x0009
        buf.write(width & 0xFF);                                 // Byte 2-5: width LE32
        buf.write((width >> 8) & 0xFF);
        buf.write((width >> 16) & 0xFF);
        buf.write((width >> 24) & 0xFF);
        buf.write(leader & 0xFF);            // Byte 6: leader (Dot=0x03 — 원본 미러)
        buf.write(type & 0xFF);              // Byte 7: type (right=0x02 — 원본 인라인 실측)
        buf.write(0x20); buf.write(0x00);    // Byte 8-9: pad 공백
        buf.write(0x20); buf.write(0x00);    // Byte 10-11
        buf.write(0x20); buf.write(0x00);    // Byte 12-13
        buf.write(0x09); buf.write(0x00);    // Byte 14-15: 코드 복사본
    }

    private Paragraph emptyParagraph() {
        Paragraph p = new Paragraph();
        CharShapeRef ref = new CharShapeRef();
        ref.position = 0;
        ref.charShapeId = 0;
        p.charShapeRefs.add(ref);
        p.paraText = ParaText.fromString("");
        p.nChars = 1; // 0x000D terminator 1 wchar 포함
        p.lineSegs.add(defaultLineSeg());
        return p;
    }

    /**
     * Section 의 첫 paragraph — SECTION_DEFINE 컨트롤 부착.
     *
     * <p>HWP 5.0 사양: paragraph 의 {@code controls} 에 첨부된 컨트롤은
     *   paraText 안에 8-wchar inline-extended control character 가 동일 위치에
     *   존재해야 하고 {@code controlMask} 의 해당 비트가 켜져야 한다.
     *   누락 시 한/글이 "파일이 손상되었습니다" 팝업을 띄운다 (deep diff 확인 완료).
     */
    private Paragraph sectionDefineParagraph() {
        Paragraph p = new Paragraph();
        p.paraShapeId = 0;
        p.styleId = 0;
        // columnBreakType = 3 (DivideSort.Section) — 첫 paragraph 가 SECT_DEF 를 가질 때
        //   반드시 3 으로 설정해야 한/글이 구역 시작을 인식. 누락 시 손상 인식.
        p.columnBreakType = 3;
        CharShapeRef ref = new CharShapeRef();
        ref.position = 0;
        ref.charShapeId = 0;
        p.charShapeRefs.add(ref);

        // 1) SECTION_DEFINE inline-ext ctrl + 2) (옵션) FOOTER inline-ext ctrl + 0x000D
        java.io.ByteArrayOutputStream paraTextBuf = new java.io.ByteArrayOutputStream();
        try {
            paraTextBuf.write(inlineExtCtrlBytes((char) 0x0002, CtrlId.SECTION_DEF));
        } catch (java.io.IOException ignore) { /* impossible on ByteArrayOutputStream */ }
        int nCharsAcc = 8;
        long controlMaskAcc = 0x04L; // bit 2 = SECT_DEF

        // CtrlSectionDef 정합 (v47 GOOD 과 byte-by-byte 일치)
        CtrlSectionDef sd = new CtrlSectionDef();
        sd.property             = 0L;
        sd.columnGap            = 1134;
        sd.defaultTabStop       = 8000;
        sd.numberingParaShapeId = 1;
        p.controls.add(sd);

        // Task1 — header 컨트롤 첨부. footer 와 대칭.
        if (currentModel != null && currentModel.headerText != null && !currentModel.headerText.isEmpty()) {
            CtrlHeaderFooter header = buildHeaderControl(currentModel.headerText);
            p.controls.add(header);
            try {
                paraTextBuf.write(inlineExtCtrlBytes((char) 0x0010, CtrlId.HEADER));
            } catch (java.io.IOException ignore) { /* impossible */ }
            nCharsAcc += 8;
            controlMaskAcc |= (1L << 0x0010);
        }

        // Task2 — footer 컨트롤 첨부.
        //   inline-ext FOOTER ctrl (charCode=0x0010) 을 SECTION_DEFINE 뒤에 이어 붙이고,
        //   controlMask 의 bit 16 을 켠다 (SectionParser.CHAR_HEADER_FOOTER = 0x0010).
        if (currentModel != null && currentModel.footerText != null && !currentModel.footerText.isEmpty()) {
            CtrlHeaderFooter footer = buildFooterControl(currentModel.footerText);
            p.controls.add(footer);
            try {
                paraTextBuf.write(inlineExtCtrlBytes((char) 0x0010, CtrlId.FOOTER));
            } catch (java.io.IOException ignore) { /* impossible */ }
            nCharsAcc += 8;
            controlMaskAcc |= (1L << 0x0010);
        }

        // paraText 종료자 0x000D
        try {
            paraTextBuf.write(new byte[] { 0x0D, 0x00 });
        } catch (java.io.IOException ignore) { /* impossible */ }
        nCharsAcc += 1;

        p.paraText    = new ParaText(paraTextBuf.toByteArray());
        p.nChars      = nCharsAcc;
        p.controlMask = controlMaskAcc;
        p.lineSegs.add(defaultLineSeg());
        return p;
    }

    /**
     * FOOTER 컨트롤 1개 생성.
     *
     * <p>v16.00-test15: task6 — footer 텍스트가 "Page of" / "페이지" 등을 포함하면
     *   ODT 가 의도한 페이지 번호 자리에 HWP 의 인라인 페이지 번호 컨트롤(pgnp,
     *   charCode=0x0015) 을 삽입한다. HWP 5.0 사양에는 "전체 페이지 수" 전용
     *   인라인 컨트롤이 없으므로 두 번째 pgnp 를 동일하게 추가하면 두 자리 모두
     *   현재 페이지 번호로 표시된다. 한 자리만 가능한 한계 (= "Page 1 of " 까지만
     *   자동 치환되고 전체 페이지 수는 비어 보임) — 추후 사양 확인 필요.
     */
    private CtrlHeaderFooter buildFooterControl(String footerText) {
        return buildHeaderFooterControl(footerText, false);
    }

    /** Task1 — HEADER 컨트롤 1개 생성. footer 와 대칭. */
    private CtrlHeaderFooter buildHeaderControl(String headerText) {
        return buildHeaderFooterControl(headerText, true);
    }

    /**
     * HEADER / FOOTER 공통 빌더. {@code isHeader} 로 ctrlId 만 분기.
     *
     * <p>v16.00-test15b: footer 텍스트가 "Page of" 패턴이면 페이지 번호 자리에
     *   인라인-확장 atno (AUTO_NUMBER) 컨트롤 삽입. pgnp 는 "페이지 번호 위치 선언"
     *   이라 실제 숫자를 그리지 않는다 — atno + numType=PAGE/TOTAL_PAGE 가 올바른
     *   "현재 페이지 / 전체 페이지" 표시용 inline ctrl. (charCode 0x0012)
     *
     * <p>OdtParser 가 page-number/page-count 를 PUA 마커(U+E000/U+E001) 로 보존하면
     *   본 메서드가 그 위치에 atno 컨트롤을 직접 삽입. 마커가 없으면 기존
     *   {@code (?i)\bPage\s*of\b} 정규식 분기로 fallback.
     */
    private CtrlHeaderFooter buildHeaderFooterControl(String text, boolean isHeader) {
        CtrlHeaderFooter hf = new CtrlHeaderFooter(isHeader ? CtrlId.HEADER : CtrlId.FOOTER);
        // property 0 = 양쪽 페이지 모두 / 본문 영역 기준 — 한/글이 위 기본을 받아들임
        hf.property    = 0L;
        hf.textWidth   = 46800; // Letter 본문 6.5in
        hf.textHeight  = 0;
        hf.textRefFlag = 0;
        hf.numRefFlag  = 0;

        // v16t51 S5: 머리글/바닥글 첫 paragraph 의 ODT style-name 으로 ParaShape 해석 — paraStyle 의
        //   fo:border-bottom 등 정보 보존(orig TA-02 머리글 #2E75B6 파란 하단선 미러). style-name 미캡처
        //   또는 미해석 시 paraShapeId=0 으로 종전 동작(byte 불변).
        String psName = (currentModel == null) ? null
                : (isHeader ? currentModel.headerParaStyleName : currentModel.footerParaStyleName);
        int hfParaShapeId = 0;
        if (psName != null) {
            OdtStyle resolved = resolveStyleForParagraph(psName);
            hfParaShapeId = resolveParaShapeForHeading(resolved); // borderBottom 있으면 전용 paraShape
        }
        Paragraph fp = new Paragraph();
        fp.paraShapeId = hfParaShapeId;
        fp.styleId     = 0;
        CharShapeRef ref = new CharShapeRef();
        ref.position = 0;
        ref.charShapeId = 0;
        fp.charShapeRefs.add(ref);

        java.io.ByteArrayOutputStream ptBuf = new java.io.ByteArrayOutputStream();
        int nCharsAcc = 0;
        long ctrlMaskAcc = 0L;
        String t = text == null ? "" : text;
        boolean hasMarker = (t.indexOf('') >= 0) || (t.indexOf('') >= 0);
        if (hasMarker) {
            // PUA 마커 기반 — 마커 위치마다 atno 삽입. 일반 문자는 그대로.
            int start = 0;
            for (int i = 0; i < t.length(); i++) {
                char ch = t.charAt(i);
                if (ch == '' || ch == '') {
                    if (i > start) {
                        String chunk = t.substring(start, i);
                        try {
                            ptBuf.write(chunk.getBytes("UTF-16LE"));
                        } catch (java.io.IOException ignore) { /* impossible */ }
                        nCharsAcc += chunk.length();
                    }
                    try {
                        ptBuf.write(inlineExtCtrlBytes((char) 0x0012, CtrlId.AUTO_NUMBER));
                    } catch (java.io.IOException ignore) { /* impossible */ }
                    nCharsAcc += 8;
                    ctrlMaskAcc |= (1L << 0x0012);
                    int numType = (ch == '') ? 0 : 6; // 0=PAGE, 6=TOTAL_PAGE
                    if (false) System.err.println("[S6-BLD] numType=" + numType + " ch=U+" + String.format("%04X", (int)ch));
                    fp.controls.add(buildAutoNumControl(numType));
                    start = i + 1;
                }
            }
            if (start < t.length()) {
                String tail = t.substring(start);
                try {
                    ptBuf.write(tail.getBytes("UTF-16LE"));
                } catch (java.io.IOException ignore) { /* impossible */ }
                nCharsAcc += tail.length();
            }
        } else {
            // fallback : 기존 (?i)\\bPage\\s*of\\b 정규식 분기 유지
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?i)\\bPage\\s*of\\b").matcher(t);
            if (m.find()) {
                String before = t.substring(0, m.start()) + "Page ";
                String after  = " of ";
                String tail   = t.substring(m.end());
                try {
                    byte[] pre = before.getBytes("UTF-16LE");
                    ptBuf.write(pre);
                    nCharsAcc += before.length();
                    ptBuf.write(inlineExtCtrlBytes((char) 0x0012, CtrlId.AUTO_NUMBER));
                    nCharsAcc += 8;
                    ctrlMaskAcc |= (1L << 0x0012);

                    byte[] mid = after.getBytes("UTF-16LE");
                    ptBuf.write(mid);
                    nCharsAcc += after.length();

                    ptBuf.write(inlineExtCtrlBytes((char) 0x0012, CtrlId.AUTO_NUMBER));
                    nCharsAcc += 8;

                    if (!tail.isEmpty()) {
                        byte[] tb = tail.getBytes("UTF-16LE");
                        ptBuf.write(tb);
                        nCharsAcc += tail.length();
                    }
                } catch (java.io.IOException ignore) { /* impossible */ }

                fp.controls.add(buildAutoNumControl(0));
                fp.controls.add(buildAutoNumControl(6));
            } else {
                // plain 텍스트
                try {
                    byte[] tb = t.getBytes("UTF-16LE");
                    ptBuf.write(tb);
                    nCharsAcc += t.length();
                } catch (java.io.IOException ignore) { /* impossible */ }
            }
        }
        // 0x000D terminator
        try { ptBuf.write(new byte[] { 0x0D, 0x00 }); } catch (java.io.IOException ignore) {}
        nCharsAcc += 1;
        fp.paraText    = new ParaText(ptBuf.toByteArray());
        fp.nChars      = nCharsAcc;
        fp.controlMask = ctrlMaskAcc;
        LineSeg seg = new LineSeg();
        seg.textStartPos    = 0;
        seg.lineVertPos     = 0;
        seg.lineHeight      = 1000;
        seg.textHeight      = 1000;
        seg.baselineDistance= 850;
        seg.lineSpacing     = 600;
        seg.columnStartPos  = 0;
        seg.segWidth        = 46800;
        seg.tag             = 0x10L;
        fp.lineSegs.add(seg);
        hf.paragraphs.add(fp);
        return hf;
    }

    /**
     * 인라인 자동 번호 컨트롤 (atno) 1개 생성. HWP 5.0 §4.3.10.5 :
     *   UINT32 property (bit 0-3 = numType: 0=PAGE, 6=TOTAL_PAGE;
     *                    bit 4-11 = numFormat: 0=DIGIT)
     *   + UINT16 number(=1)  + WCHAR userChar + prefix + suffix = 12 byte rawData.
     *
     * <p>v16.00-test15b: 한컴 렌더링이 받아들이는 "현재 페이지 / 전체 페이지" inline
     *   ctrl. pgnp 는 위치선언용이라 숫자를 그리지 않는다. atno 가 정답.
     *
     * @param numType  0 = 현재 페이지, 6 = 전체 페이지
     */
    private static Control buildAutoNumControl(int numType) {
        Control c = new Control(CtrlId.AUTO_NUMBER);
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(12)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.putInt(numType & 0xF);    // bit 0-3 = numType, bit 4-11 = 0(DIGIT)
        bb.putShort((short) 1);      // number 자리 placeholder — 한컴이 재계산
        bb.putShort((short) 0);      // userChar
        bb.putShort((short) 0);      // prefixChar
        bb.putShort((short) 0);      // suffixChar
        c.rawData = bb.array();
        return c;
    }

    /**
     * HWP 5.0 inline-extended control character (8 wchar = 16 byte) byte 시퀀스.
     *
     *   wchar 0 : charCode (컨트롤별 고유 값 — SECT_DEF=0x0002, TABLE=0x000B 등)
     *   wchars 1-2 : ctrlId 4 byte (little-endian)
     *   wchars 3-6 : 12 byte padding (0)
     *   wchar 7 : charCode 복사
     *
     * <p>controlMask 의 비트 번호 == charCode 값. 따라서 SECT_DEF 는 bit 2,
     *   TABLE 은 bit 11. 두 wchar 모두 같은 charCode 여야 hwplib reader 가
     *   확장 컨트롤로 인식한다.
     */
    private static byte[] inlineExtCtrlBytes(char charCode, int ctrlId) {
        byte[] b = new byte[16];
        b[0]  = (byte)  (charCode        & 0xFF);
        b[1]  = (byte) ((charCode >>  8) & 0xFF);
        b[2]  = (byte)  (ctrlId          & 0xFF);
        b[3]  = (byte) ((ctrlId   >>  8) & 0xFF);
        b[4]  = (byte) ((ctrlId   >> 16) & 0xFF);
        b[5]  = (byte) ((ctrlId   >> 24) & 0xFF);
        // b[6..13] = 0 padding (4 wchar)
        b[14] = (byte)  (charCode        & 0xFF);
        b[15] = (byte) ((charCode >>  8) & 0xFF);
        return b;
    }

    /**
     * 표 paragraph 빌드 (v16.00-test5+). HWP 5.0 CtrlTable + TableCell 정식 구현.
     *
     * <p>참고 01_계약서.hwpx 의 표 구조를 모방:
     *   - paragraph paraText 에 inline-ext TABLE ctrl ('tbl ', 8 wchar) + 0x000D 종료자
     *   - paragraph.controls 에 CtrlTable, controlMask |= (1 &lt;&lt; 11)
     *   - CtrlTable 안에 TableCell[] (행 단위), 각 셀 안에 paragraph
     *   - 표 전체와 모든 셀이 같은 borderFill 사용 (4면 SOLID 검정 0.12mm)
     */
    /** v16t50 R7: depth==0 (최상위 표) 와 depth>=1 (중첩 표) 분기. */
    private Paragraph buildTableParagraph(TableBlock t, int depth) {
        int rowCnt = Math.max(1, t.rows.size());
        int colCnt = Math.max(1, t.columnCount > 0 ? t.columnCount
                : (t.rows.isEmpty() ? 1 : t.rows.get(0).cells.size()));

        // Task3 — 표 너비 : ODT style:width / style:column-width 를 HWPUNIT 로 환산 후 적용.
        //   본문 너비(Letter 6.5in = 46800 HWPUNIT) 초과 시 비례 축소.
        final int bodyWidthHwp = 46800;
        int[] colWidthsHwp = new int[colCnt];
        int sumColHwp = 0;
        if (t.columnWidthSpecs != null && !t.columnWidthSpecs.isEmpty()) {
            for (int i = 0; i < colCnt; i++) {
                String spec = (i < t.columnWidthSpecs.size()) ? t.columnWidthSpecs.get(i) : null;
                int w = lengthToHwpUnit(spec);
                colWidthsHwp[i] = (w > 0) ? w : 0;
                sumColHwp += colWidthsHwp[i];
            }
        }
        int tableWidthFromStyle = lengthToHwpUnit(t.widthSpec);
        int tableTargetWidth;
        if (tableWidthFromStyle > 0)      tableTargetWidth = tableWidthFromStyle;
        else if (sumColHwp > 0)           tableTargetWidth = sumColHwp;
        else                              tableTargetWidth = bodyWidthHwp;
        if (tableTargetWidth > bodyWidthHwp) tableTargetWidth = bodyWidthHwp;

        if (sumColHwp > 0) {
            double scale = (double) tableTargetWidth / sumColHwp;
            int acc = 0;
            for (int i = 0; i < colCnt - 1; i++) {
                colWidthsHwp[i] = Math.max(100, (int) Math.round(colWidthsHwp[i] * scale));
                acc += colWidthsHwp[i];
            }
            colWidthsHwp[colCnt - 1] = Math.max(100, tableTargetWidth - acc);
        } else {
            int even = tableTargetWidth / colCnt;
            for (int i = 0; i < colCnt - 1; i++) colWidthsHwp[i] = even;
            colWidthsHwp[colCnt - 1] = tableTargetWidth - even * (colCnt - 1);
        }
        int tableWidth  = tableTargetWidth;
        final int cellH = 2400;                 // 약 24pt = 줄간격 + 패딩 여유 (행높이 미지정 시 기본)
        // v16t45 F-2: ODT style:row-height 보유 행은 지정 높이 사용.
        //   미지정 행은 종전 2400 그대로 — row-height 없는 문서는 byte 불변.
        int[] rowHeightHwp = new int[rowCnt];
        int tableHeight = 0;
        for (int i = 0; i < rowCnt; i++) {
            int h = (i < t.rows.size()) ? lengthToHwpUnit(t.rows.get(i).heightSpec) : 0;
            rowHeightHwp[i] = (h > 0) ? h : cellH;
            tableHeight += rowHeightHwp[i];
        }

        // 표용 borderFill — 4면 SOLID 검정 (한번만 생성, 공유)
        int tblBorderFillId = ensureTableBorderFill();

        // CtrlTable 구성
        CtrlTable ct = new CtrlTable();
        ct.rowCount    = rowCnt;
        ct.colCount    = colCnt;
        ct.cellSpacing = 0;
        ct.padLeft     = 141;
        ct.padRight    = 141;
        ct.padTop      = 141;
        ct.padBottom   = 141;
        ct.borderFillId   = tblBorderFillId + 1;  // 1-based
        // HWP 5.0 사양 : TABLE 본문의 'rowHeights[]' 자리는 실제로 각 행의 셀 개수
        //   (cellCountOfRowList) 를 의미한다. 외부 hwplib reader 가 이 값으로
        //   row 당 ForCell.read 호출 횟수를 결정하므로 셀 개수와 정확히 일치해야 한다.
        //   (자체 SectionWriter 의 byte 출력은 그대로 — 값만 정정.)
        //   v16t39: colSpan 으로 실제 셀수<colCnt 가 되면 hwplib 가 없는 셀을 읽다 실패하므로
        //   colCnt 하드코딩을 제거하고, 아래 셀 빌드 루프에서 행별 실제 add 횟수로 채운다.
        ct.rowHeights  = new int[rowCnt];
        ct.zoneCount   = 0;
        ct.width       = tableWidth;
        ct.height      = tableHeight;
        ct.margins[0]  = 283; ct.margins[1] = 283; ct.margins[2] = 283; ct.margins[3] = 283;
        ct.instanceId  = 0x10000L;             // 임의 양의 32bit 값
        // v16.00-test14 : 표 위치 버그 수정 — bit 0(treatAsChar=인라인) 미설정 시
        //   HWP 가 표를 floating 객체로 처리하여 페이지 상단으로 떠오르는 현상.
        //   reader(SectionParser.buildObjProperty + buildExtendedObjProperty) 의
        //   HWPX→HWP 기본 비트 패턴과 동일하게 세팅:
        //     bit 0   treatAsChar (인라인, 글자처럼 취급)
        //     bit 3-4 vertRelTo=PARA(2)
        //     bit 8-9 horzRelTo=COLUMN(2)
        //     bit 13  para-area 제한
        //     bit 15-17 width basis=4 / bit 18-19 height basis=2
        //     bit 21-23 textWrap=TOP_AND_BOTTOM(1)
        //     bit 26-28 numbering category=TABLE(2)
        // v16t51 S2: 중첩표(depth>=1) objProperty 실측 미러 — _diag/NestedTbl 결과 orig case1.hwp
        //   중첩표 17건 다수파 0x082A2311 (vRel=2/PARA·hRel=3/PARA·bit13=1·treatAsChar=1).
        //   종전 0x08224219(hRel=2/COLUMN·bit13=0)은 컬럼 기준 → 채은 측정대로 '중첩 표가
        //   outer cell 단락 기준이 아닌 컬럼 기준 위치 이동' 회귀(LEFT 4p 표 인접 정렬 깨짐).
        //   최상위 표(depth==0)는 S4 분기에서 floating 0x082A2210 덮어쓰므로 기본값 변경 영향 없음.
        // v16t65 task1/2: 중첩표 '글자처럼 취급' 미체크 — bit0(treatAsChar) 0x...11→0x...10 만 클리어.
        //   hRel=3/PARA·bit13=1 은 유지해 outer cell 단락 기준 정착(컬럼 기준 floating 회귀 방지).
        ct.objProperty = (depth >= 1) ? 0x082A2310L : 0x08224219L;
        // v16t50 R7-A: 최상위 표(depth==0) 는 floating 객체로 두고 표 본문에 pageBreak=CELL(셀단위 분할)
        //   비트를 켠다. 종전엔 모든 표가 treatAsChar=1 inline 으로 강제되고 property=0 (TABLE 고정,
        //   페이지 분할 금지) 이라 109p 처럼 큰 표가 페이지 끝에서 잘리는 본질 결함을 유발.
        //   중첩 표(depth>=1) 는 종전 inline 패턴+property=0 그대로 유지(byte 불변).
        //   ODT fo:keep-together/break-* 의 표별 분포 미러는 R7-B (v16t51) 에서 진행.
        if (depth == 0) {
            // v16t51 S4 (덕수·민준 확정 — _diag/CtrlTableBitDump 로 orig case1.hwp floating 표
            //   3건 실측: 0x082A2310/tabProp=0x4(NoDivide) 1건 + 0x082A2210/tabProp=0x6(Divide) 2건).
            //   다수파 0x082A2210 + Divide(NONE) 미러로 모든 최상위 표를 floating(treatAsChar=0)+
            //   쪽경계 나눔으로 통일. 사용자 방침 직결:
            //   objProperty=0x082A2210 (treatAsChar=0, vRel=2(PARA), hRel=2(COLUMN), wBasis=4,
            //                            hBasis=2, textWrap=1(TOP_AND_BOTTOM))
            //   tableProperty bit0-1=2 → DivideAtPageBoundary.Divide (= OWPML pageBreak="NONE")
            //   1차 시도 0x082A2310(hRel=3) 가 한컴 본문 압축 회귀 원인이었음 — hRel=2 가 정답.
            //   중첩 표(depth>=1) 는 종전 0x08224219+property=0 유지(inline) — byte 불변.
            ct.objProperty  = 0x082A2210L;
            ct.property     = 2L;
        } else {
            // v16t65 task2/3: 중첩표 쪽경계도 '나눔'(property bit=2 Divide → hwp2hwpx OWPML "TABLE")
            //   으로 통일. 종전 기본값(0)은 NoDivide → OWPML "NONE"(나누지 않음) 이었다.
            ct.property = 2L;
        }
        ct.pageBreakPrev = 0;
        ct.description = "";

        // v16t39: rowSpan 점유맵 — 위 행의 rowSpan>1 셀이 덮은 칸은 아래 행에서 셀을
        //   새로 만들지 않는다. 이를 추적하지 않으면 Σ(셀 area)>rowCnt*colCnt 가 되어
        //   한/글이 표를 거부/재배치한다. rowSpan 없는 표는 occ 전부 false → 기존동작 불변.
        boolean[][] occ = new boolean[rowCnt][colCnt];

        // v16t45 FIX C: table:formula 셀이 있으면 그리드 앵커·숫자성 사전계산 (없으면 null — 종전 경로).
        FormulaGrid fg = computeFormulaGrid(t, rowCnt, colCnt);

        // 업무3 후속(v16.68) Option A: '선택적 테두리 표'(같은 표 안에 명시적 per-side 테두리를
        //   가진 셀이 하나라도 있는 표) 에서만, 테두리 속성이 전무한 셀(anyRaw=false)을 4면 SOLID
        //   폴백 대신 4면 NONE(테두리 없음) 으로 매핑한다('지방보조금 개요' 제목표 row1-좌 빈셀이
        //   원본 bf7 전변 NONE 과 일치하도록). 명시 테두리 셀이 전무한 일반 표는 종전
        //   ensureTableBorderFill() 폴백 유지 → 다른 문서 byte 무회귀.
        boolean tableHasSelectiveBorders = false;
        for (TableBlock.Row scanRow : t.rows) {
            for (TableBlock.Cell scanCell : scanRow.cells) {
                OdtStyle cs = resolveStyleForParagraph(scanCell.cellStyleName);
                if (cs != null && (cs.borderTop != null || cs.borderBottom != null
                        || cs.borderLeft != null || cs.borderRight != null)) {
                    tableHasSelectiveBorders = true;
                    break;
                }
            }
            if (tableHasSelectiveBorders) break;
        }

        // 모든 셀을 (row major) 추가 — 셀 스타일별로 borderFill 분리 + 헤더 행 charPr 분리
        for (int rowIdx = 0; rowIdx < t.rows.size(); rowIdx++) {
            TableBlock.Row row = t.rows.get(rowIdx);
            int colIdx = 0;
            int cellsThisRow = 0;
            for (TableBlock.Cell src : row.cells) {
                // 위 행 rowSpan 이 점유한 칸은 건너뛴다(셀 생성 X).
                while (colIdx < colCnt && occ[rowIdx][colIdx]) colIdx++;
                if (colIdx >= colCnt) break;
                int srcSpan = Math.max(1, src.colSpan);
                int cellWForThis = 0;
                for (int k = 0; k < srcSpan && (colIdx + k) < colCnt; k++) {
                    cellWForThis += colWidthsHwp[colIdx + k];
                }
                if (cellWForThis <= 0) cellWForThis = colWidthsHwp[Math.min(colIdx, colCnt - 1)];
                TableCell tc = new TableCell();
                tc.colAddr  = colIdx;
                tc.rowAddr  = rowIdx;
                tc.colSpan  = src.colSpan;
                tc.rowSpan  = src.rowSpan;
                tc.width    = cellWForThis;
                // v16t45 F-2: rowSpan 구간 행높이 합산 (rowCnt 초과분은 기본 cellH — 종전 cellH*rowSpan 호환).
                int cellHForThis = 0;
                for (int k = 0; k < Math.max(1, src.rowSpan); k++) {
                    cellHForThis += (rowIdx + k < rowCnt) ? rowHeightHwp[rowIdx + k] : cellH;
                }
                tc.height   = cellHForThis;
                // v16.00-test15: 보너스 — 셀 패딩 축소 (141 → 70 HWPUNIT) 로 가용 너비 확장.
                tc.margins[0] = 700; tc.margins[1] = 700; tc.margins[2] = 400; tc.margins[3] = 400;
                // 셀 스타일 (HeaderCell/AltCell/BodyCell 등) 의 fo:background-color → 셀 전용 borderFill 의 fillBgColor.
                tc.borderFillId = ensureCellBorderFill(resolveStyleForParagraph(src.cellStyleName), tableHasSelectiveBorders) + 1;
                tc.listHeaderParaCount = 1;
                // v16t50 R3/R5/R8: ODT style:vertical-align (top/middle/bottom) → listHeaderProperty bit 5-6
                //   (0=TOP, 1=CENTER, 2=BOTTOM). 미명시 셀은 0(TOP) 유지 — byte 영향 없음.
                tc.listHeaderProperty  = cellVertAlignBits(resolveStyleForParagraph(src.cellStyleName));
                tc.textWidth  = tc.width;
                tc.textHeight = 0;
                // 셀 안 paragraph — 헤더 행이면 헤더 전용 charShape (글자색 #FFFFFF, bold) 적용
                // v16t40: 셀 content 의 중첩표(TableBlock)까지 빌드 → [텍스트문단, 중첩표문단...].
                //   중첩표 없는 셀은 size==1 로 종전과 동일(byte 불변).
                String formulaCmd = (fg == null) ? null
                        : buildFormulaCommand(src, rowIdx, colIdx, rowCnt, colCnt, fg);
                java.util.List<Paragraph> cellParas = buildCellParagraphs(src, row.header, formulaCmd);
                // v16.00-test15: 보너스 — 셀 안 글자크기 10pt → 8pt 로 축소 (단, 빌더가
                //   기본 charShape ref 만 사용한 경우에 한해서). 셀의 charShapeRef 가
                //   모두 0 (기본 검정 10pt) 일 때만 cellCharShapeId 로 교체한다.
                //   보정은 평탄화 텍스트(첫 문단)에만 적용 — 중첩표 문단은 자체 charShape/lineSeg 유지.
                // v16t48 F-2b: 셀에 명시 font-size 가 있으면 보정 면제 — 명시 10pt 가 기본
                //   charShape 와 dedup 되어 id 0 이 되므로, 치환하면 원본 10pt 가 8pt 로 강등된다.
                Paragraph firstPara = cellParas.get(0);
                if (!lastCellExplicitSize) {
                    int cellCsId = ensureCellTextCharShape();
                    for (CharShapeRef r : firstPara.charShapeRefs) {
                        if (r.charShapeId == 0) r.charShapeId = cellCsId;
                    }
                }
                // lineSeg.segWidth 를 셀 textWidth 에 맞춰서 셀 안에서만 줄바꿈되게 한다.
                //   기본 segWidth(=42000) 그대로 두면 셀 경계를 넘어 글자가 겹쳐 보인다.
                if (!firstPara.lineSegs.isEmpty()) {
                    // v16t52 T5: golden cellMargin (left/right=700·top/bottom=400) 미러 후 좌우 합 1400.
                    firstPara.lineSegs.get(0).segWidth = Math.max(1000, tc.textWidth - 1400);
                }
                tc.listHeaderParaCount = cellParas.size();
                tc.paragraphs.addAll(cellParas);
                ct.cells.add(tc);
                cellsThisRow++;
                // v16t39: 이 셀이 차지하는 colSpan×rowSpan 영역을 점유 마킹.
                markOccupied(occ, rowIdx, colIdx, srcSpan, Math.max(1, src.rowSpan), rowCnt, colCnt);
                colIdx += srcSpan;
            }
            // 부족한 셀 채우기 (colSpan/rowSpan 으로 인해 비는 자리). 점유칸은 건너뜀.
            while (colIdx < colCnt) {
                if (occ[rowIdx][colIdx]) { colIdx++; continue; }
                int wHere = colWidthsHwp[colIdx];
                TableCell tc = new TableCell();
                tc.colAddr = colIdx;
                tc.rowAddr = rowIdx;
                tc.colSpan = 1;
                tc.rowSpan = 1;
                tc.width   = wHere;
                tc.height  = rowHeightHwp[rowIdx];
                // v16.00-test15: 보너스 — 셀 패딩 축소.
                tc.margins[0] = 700; tc.margins[1] = 700; tc.margins[2] = 400; tc.margins[3] = 400;
                tc.borderFillId = tblBorderFillId + 1;
                tc.listHeaderParaCount = 1;
                tc.textWidth = wHere;
                tc.paragraphs.add(emptyParagraph());
                ct.cells.add(tc);
                cellsThisRow++;
                colIdx++;
            }
            // v16t39: 이 행에 실제로 추가한 셀 개수를 rowHeights(cellCountOfRowList)에 기록.
            //   hwplib reader 가 이 값만큼 ForCell.read 하므로 실제 add 횟수와 정확히 일치해야 한다.
            if (cellsThisRow == 0) {
                TableCell tc = new TableCell();
                tc.colAddr = 0; tc.rowAddr = rowIdx; tc.colSpan = 1; tc.rowSpan = 1;
                tc.width = (colCnt > 0 ? colWidthsHwp[0] : tableWidth); tc.height = rowHeightHwp[rowIdx];
                tc.margins[0] = 700; tc.margins[1] = 700; tc.margins[2] = 400; tc.margins[3] = 400;
                tc.borderFillId = tblBorderFillId + 1;
                tc.listHeaderParaCount = 1;
                tc.textWidth = tc.width;
                tc.paragraphs.add(emptyParagraph());
                ct.cells.add(tc);
                cellsThisRow = 1;
            }
            ct.rowHeights[rowIdx] = cellsThisRow;
        }

        // 표 paragraph 자체 빌드
        Paragraph p = new Paragraph();
        p.paraShapeId = 0;
        p.styleId     = 0;
        CharShapeRef ref = new CharShapeRef();
        ref.position = 0;
        ref.charShapeId = 0;
        p.charShapeRefs.add(ref);
        byte[] tblCtrl = inlineExtCtrlBytes((char) 0x000B, CtrlId.TABLE);
        byte[] paraTextBytes = new byte[tblCtrl.length + 2];
        System.arraycopy(tblCtrl, 0, paraTextBytes, 0, tblCtrl.length);
        paraTextBytes[tblCtrl.length    ] = 0x0D;
        paraTextBytes[tblCtrl.length + 1] = 0x00;
        p.paraText = new ParaText(paraTextBytes);
        p.nChars      = 9;                  // 8 (inline ctrl) + 1 (terminator)
        p.controlMask = (1L << 11);         // bit 11 = TABLE
        p.controls.add(ct);
        // 표 paragraph 의 lineSeg : 표 전체 영역
        LineSeg seg = new LineSeg();
        seg.textStartPos = 0;
        seg.lineVertPos  = 0;
        seg.lineHeight   = tableHeight;
        seg.textHeight   = tableHeight;
        seg.baselineDistance = tableHeight;
        seg.lineSpacing  = tableHeight;
        seg.columnStartPos = 0;
        seg.segWidth     = tableWidth;
        seg.tag          = 0x60000L;        // flags=393216
        p.lineSegs.add(seg);
        return p;
    }

    /** v16t39: 셀의 colSpan×rowSpan 영역을 점유맵에 마킹(그리드 범위 가드). */
    private static void markOccupied(boolean[][] occ, int row, int col,
                                     int colSpan, int rowSpan, int rowCnt, int colCnt) {
        for (int r = row; r < row + rowSpan && r < rowCnt; r++) {
            for (int c = col; c < col + colSpan && c < colCnt; c++) {
                occ[r][c] = true;
            }
        }
    }

    /** v16t45 FIX C: 표 그리드 앵커별 '숫자 셀' 여부 — SUM(방향) 재구성 판정용. */
    private static final class FormulaGrid {
        final boolean[][] numeric;
        FormulaGrid(int rowCnt, int colCnt) { numeric = new boolean[rowCnt][colCnt]; }
    }

    /**
     * v16t45 FIX C: table:formula 셀이 하나라도 있을 때만 점유맵 워크를 미러해
     *   각 셀 앵커 좌표의 숫자성(표시 텍스트 기준 — hwp2odt TableMapper.isNumericCell 거울)을
     *   사전 계산한다. 수식 없는 표는 null (종전 경로, 추가 비용 없음).
     */
    private static FormulaGrid computeFormulaGrid(TableBlock t, int rowCnt, int colCnt) {
        boolean has = false;
        for (TableBlock.Row row : t.rows) {
            for (TableBlock.Cell c0 : row.cells) {
                if (c0.formulaSpec != null && !c0.formulaSpec.isEmpty()) { has = true; break; }
            }
            if (has) break;
        }
        if (!has) return null;
        FormulaGrid g = new FormulaGrid(rowCnt, colCnt);
        boolean[][] occ = new boolean[rowCnt][colCnt];
        for (int rowIdx = 0; rowIdx < t.rows.size() && rowIdx < rowCnt; rowIdx++) {
            int colIdx = 0;
            for (TableBlock.Cell src : t.rows.get(rowIdx).cells) {
                while (colIdx < colCnt && occ[rowIdx][colIdx]) colIdx++;
                if (colIdx >= colCnt) break;
                int srcSpan = Math.max(1, src.colSpan);
                g.numeric[rowIdx][colIdx] = isNumericText(cellFlatText(src));
                markOccupied(occ, rowIdx, colIdx, srcSpan, Math.max(1, src.rowSpan), rowCnt, colCnt);
                colIdx += srcSpan;
            }
        }
        return g;
    }

    /** 셀 content 의 텍스트 run 평탄화 (메타 무시 — 숫자성 판정용). */
    private static String cellFlatText(TableBlock.Cell src) {
        StringBuilder sb = new StringBuilder();
        for (Block b : src.content) {
            java.util.List<Run> rs = (b instanceof ParagraphBlock) ? ((ParagraphBlock) b).runs
                    : (b instanceof HeadingBlock) ? ((HeadingBlock) b).runs : null;
            if (rs == null) continue;
            for (Run r : rs) sb.append(r.text);
        }
        return sb.toString();
    }

    /** hwp2odt TableMapper.isNumericCell 의 거울 — 콤마/공백 제거 후 숫자면 true. */
    private static boolean isNumericText(String txt) {
        if (txt == null) return false;
        String num = txt.replaceAll("[,\\s]", "");
        return num.matches("[-+]?\\d+(\\.\\d+)?");
    }

    /** ooow:/of: 수식 안의 셀 참조 토큰 — {@code <D4>} 또는 {@code [.D4]}. */
    private static final java.util.regex.Pattern ODF_CELL_REF =
            java.util.regex.Pattern.compile("<([A-Z]+)(\\d+)>|\\[\\.([A-Z]+)(\\d+)\\]");

    /**
     * v16t45 FIX C: ODF table:formula → HWP FIELD_FORMULA command ({@code "=식??%g,;;표시값"}).
     *
     * <p>식 재구성 우선순위 (정방향 hwp2odt TableMapper 발화의 정확한 역):
     * <ol>
     *   <li>참조의 순수 '+' 체인이 한 방향의 숫자 셀 전부와 발화 순서까지 일치
     *       → {@code =SUM(LEFT|RIGHT|ABOVE|BELOW)} (원본 명령 그대로 복원)</li>
     *   <li>모든 참조가 현재 열 → {@code =?N} 행참조형 (C-2 의 역; 원본 "=?3+?4+?5" 복원)</li>
     *   <li>그 외 → A1 참조 그대로 (한컴 계산식의 A1 주소 지원은 원본 "=SUM(C2:C2)" 로 실증)</li>
     * </ol>
     *   참조 외 잔여 문자가 사칙연산/숫자/괄호/공백이 아니거나 표시 텍스트가 비면
     *   null — 종전 동작(필드 미부여, 정적 텍스트만). 표시값은 셀 표시 텍스트와 동일해야
     *   한다(필드가 그 텍스트를 감싸므로).
     */
    private static String buildFormulaCommand(TableBlock.Cell src, int r, int c,
                                              int rowCnt, int colCnt, FormulaGrid g) {
        String spec = src.formulaSpec;
        if (spec == null || spec.isEmpty()) return null;
        String display = cellFlatText(src).trim();
        if (display.isEmpty()) return null;

        String expr = spec;
        int colon = expr.indexOf(':');
        if (colon > 0 && colon <= 8) expr = expr.substring(colon + 1); // "ooow:" / "of:" 접두 제거
        if (expr.startsWith("=")) expr = expr.substring(1);
        expr = expr.trim();
        if (expr.isEmpty()) return null;

        // 참조 토큰 수집 + 자리표시자('\u0001') 템플릿화
        java.util.List<int[]> refs = new java.util.ArrayList<>(); // {row0, col0}
        StringBuilder tpl = new StringBuilder();
        java.util.regex.Matcher m = ODF_CELL_REF.matcher(expr);
        int last = 0;
        while (m.find()) {
            tpl.append(expr, last, m.start());
            String colS = m.group(1) != null ? m.group(1) : m.group(3);
            String rowS = m.group(1) != null ? m.group(2) : m.group(4);
            int col0 = 0;
            for (int i = 0; i < colS.length(); i++) col0 = col0 * 26 + (colS.charAt(i) - 'A' + 1);
            col0 -= 1;
            int row0 = Integer.parseInt(rowS) - 1;
            if (row0 < 0 || row0 >= rowCnt || col0 < 0 || col0 >= colCnt) return null;
            refs.add(new int[]{row0, col0});
            tpl.append('\u0001');
            last = m.end();
        }
        tpl.append(expr, last, expr.length());
        if (refs.isEmpty()) return null;
        // 참조 외 잔여가 사칙연산식이 아니면(함수 등) 변환 보류 — 종전 동작
        if (!tpl.toString().matches("[\u00010-9+\\-*/(). ]+")) return null;

        String body = null;
        if (isPlusChain(tpl.toString(), refs.size())) {
            body = matchDirectionalSum(refs, r, c, rowCnt, colCnt, g);
        }
        if (body == null) {
            boolean allCurCol = true;
            for (int[] ref : refs) if (ref[1] != c) { allCurCol = false; break; }
            StringBuilder b = new StringBuilder();
            int ri = 0;
            for (int i = 0; i < tpl.length(); i++) {
                char ch = tpl.charAt(i);
                if (ch == '\u0001') {
                    int[] ref = refs.get(ri++);
                    if (allCurCol) b.append('?').append(ref[0] + 1);       // ?N 행참조형 (C-2 의 역)
                    else           b.append(colLetters(ref[1])).append(ref[0] + 1); // A1 그대로
                } else b.append(ch);
            }
            body = b.toString();
        }
        return "=" + body + "??%g,;;" + display;
    }

    /** 템플릿이 자리표시자 n개를 '+' 로만 이은 순수 체인인가 ("++..."). */
    private static boolean isPlusChain(String tpl, int n) {
        StringBuilder want = new StringBuilder();
        for (int i = 0; i < n; i++) { if (i > 0) want.append('+'); want.append('\u0001'); }
        return tpl.replace(" ", "").equals(want.toString());
    }

    /**
     * 참조 목록이 정방향 buildDirectionalSum 의 발화(해당 방향 숫자 셀 전부, 탐색 순서 그대로:
     *   LEFT c-1→0 / RIGHT c+1→끝 / ABOVE r-1→0 / BELOW r+1→끝)와 일치하면 "SUM(방향)".
     */
    private static String matchDirectionalSum(java.util.List<int[]> refs, int r, int c,
                                              int rowCnt, int colCnt, FormulaGrid g) {
        String[] dirs = {"LEFT", "RIGHT", "ABOVE", "BELOW"};
        for (String dir : dirs) {
            java.util.List<int[]> want = new java.util.ArrayList<>();
            switch (dir) {
                case "LEFT":  for (int cc = c - 1; cc >= 0; cc--)      if (g.numeric[r][cc]) want.add(new int[]{r, cc}); break;
                case "RIGHT": for (int cc = c + 1; cc < colCnt; cc++)  if (g.numeric[r][cc]) want.add(new int[]{r, cc}); break;
                case "ABOVE": for (int rr = r - 1; rr >= 0; rr--)      if (g.numeric[rr][c]) want.add(new int[]{rr, c}); break;
                default:      for (int rr = r + 1; rr < rowCnt; rr++)  if (g.numeric[rr][c]) want.add(new int[]{rr, c}); break;
            }
            if (want.size() != refs.size() || want.isEmpty()) continue;
            boolean eq = true;
            for (int i = 0; i < refs.size(); i++) {
                if (refs.get(i)[0] != want.get(i)[0] || refs.get(i)[1] != want.get(i)[1]) { eq = false; break; }
            }
            if (eq) return "SUM(" + dir + ")";
        }
        return null;
    }

    /** 0-base 열 인덱스 → 열 문자 (A,B,…,Z,AA…) — hwp2odt TableMapper.a1 의 열 부분 거울. */
    private static String colLetters(int c) {
        StringBuilder col = new StringBuilder();
        int n = c;
        do { col.insert(0, (char) ('A' + (n % 26))); n = n / 26 - 1; } while (n >= 0);
        return col.toString();
    }

    /**
     * 셀 안 paragraph 1개. 헤더 행(row.header=true) 이거나 셀 paragraph 의 첫 run
     *   style 이 헤더용(HeaderCellPara 등)일 때 헤더 charShape 사용.
     */
    /**
     * 셀 content 를 HWP 셀 문단리스트로 빌드한다.
     *
     * <p>v16t40: 기존엔 ParagraphBlock/HeadingBlock 만 1개 문단으로 평탄화하고
     *   셀 안의 표(중첩 TableBlock)를 조용히 폐기 → HWPX 셀/표 수 손실(case1 △364셀/△17표).
     *   파서는 중첩표를 보존하므로(OdtParser:589) 여기서 {@link #buildTableParagraph}
     *   로 재귀 빌드해 셀 문단리스트에 표 컨트롤 문단으로 추가한다.
     *
     * <p>반환: [평탄화 텍스트 문단, 중첩표/이미지/수식 문단...]. extras 가 없으면 size==1 로
     *   종전 동작과 완전히 동일(평탄화 로직 불변, byte 출력 불변).
     *
     * <p>v16t45 FIX A-2: 셀 안의 draw:frame(ImageBlock/EquationBlock)도 v16t40 중첩표와
     *   동일 패턴으로 buildImageParagraph/buildEquationParagraph 재사용해 보존
     *   (종전에는 분기가 없어 조용히 폐기 — TA-02 셀내 이미지 5건/TA-04 셀내 수식 5건 손실).
     */
    private java.util.List<Paragraph> buildCellParagraphs(TableBlock.Cell src, boolean header,
                                                          String formulaCommand) {
        // v16t50 R1/R4: 셀 안에 ParagraphBlock/HeadingBlock 이 2 개 이상이면 paragraph 경계
        //   보존(각자의 textAlign 가 살아난다). 1 개면 종전 평탄화 그대로 — 단일 paragraph
        //   셀(case1 99%·TA-04 대다수) 의 byte 출력 불변 가드.
        int paraBlockCount = 0;
        for (Block bb : src.content) {
            if (bb instanceof ParagraphBlock || bb instanceof HeadingBlock) paraBlockCount++;
        }
        if (paraBlockCount > 1) {
            return buildCellParagraphsMulti(src, header, formulaCommand);
        }
        String paraStyle = null;
        java.util.List<Run> flatRuns = new java.util.ArrayList<>();
        StringBuilder sb = new StringBuilder();
        String curHref = null; // v16t45 FIX B: 평탄화 중 현재 링크 href
        java.util.List<Block> extras = new java.util.ArrayList<>(); // 컨트롤 문단 블록 — content 순서 보존
        // v16t48 F-2b: span 경계마다 flush 해 run 별 span 스타일을 평탄 run 에 보존한다.
        //   (v16t47 의 '단일 span 셀만 복원'을 일반화 — 혼합 span 셀도 각 구간의
        //   글자크기/색/굵기가 살아난다. span 없는 셀은 종전과 단일 plain run 으로 동일.)
        String curSpan = null;
        for (Block b : src.content) {
            java.util.List<Run> rs = null;
            if (b instanceof ParagraphBlock) {
                ParagraphBlock pb0 = (ParagraphBlock) b;
                if (paraStyle == null) paraStyle = pb0.paragraphStyleName;
                rs = pb0.runs;
            } else if (b instanceof HeadingBlock) {
                rs = ((HeadingBlock) b).runs;
            } else if (b instanceof TableBlock || b instanceof ImageBlock || b instanceof EquationBlock) {
                extras.add(b);
            }
            if (rs == null) continue;
            // v16t45 FIX B: 종전처럼 텍스트를 하나로 합치되(span 스타일 무시 유지),
            //   링크/책갈피 메타만 run 경계로 보존. 링크·책갈피 없는 셀은 종전과
            //   동일한 단일 plain run 으로 떨어져 byte 출력 불변.
            for (Run r : rs) {
                if (r.bookmarkName != null && !r.bookmarkName.isEmpty()) {
                    curHref = flushCellRun(flatRuns, sb, curHref, curSpan);
                    Run m = Run.plain("");
                    m.bookmarkName = r.bookmarkName;
                    flatRuns.add(m);
                    continue;
                }
                // v16t45 F-4: 셀 내 as-char 인라인 이미지 run 보존 (종전 평탄화는 text 만 합쳐 폐기).
                if (r.inlineImage != null) {
                    curHref = flushCellRun(flatRuns, sb, curHref, curSpan);
                    Run m = Run.plain("");
                    m.inlineImage = r.inlineImage;
                    flatRuns.add(m);
                    continue;
                }
                String h = (r.linkHref == null || r.linkHref.isEmpty()) ? null : r.linkHref;
                if (!java.util.Objects.equals(h, curHref)) {
                    flushCellRun(flatRuns, sb, curHref, curSpan);
                    curHref = h;
                }
                // v16t48 F-2b: 비공백 텍스트 run 의 span 이 바뀌면 flush — 구간별 span 보존.
                if (r.text != null && !r.text.isEmpty()
                        && !java.util.Objects.equals(r.spanStyleName, curSpan)) {
                    flushCellRun(flatRuns, sb, curHref, curSpan);
                    curSpan = r.spanStyleName;
                }
                sb.append(r.text);
            }
        }
        flushCellRun(flatRuns, sb, curHref, curSpan);
        // v16t48 F-2b: 셀 안 어디든 명시 font-size 가 있으면(문단 스타일 or 어떤 run 의 span)
        //   호출부의 8pt 셀 보정을 면제한다. 명시 10pt 는 기본 charShape(10pt)와 dedup 되어
        //   id 0 이 되므로 '크기 미지정'과 구분이 안 돼 8pt 로 강등되던 것이 F-7 의 본질.
        lastCellExplicitSize = false;
        OdtStyle ps0 = resolveStyleForParagraph(paraStyle);
        if (ps0 != null && ps0.fontSize != null) lastCellExplicitSize = true;
        if (!lastCellExplicitSize) {
            for (Run fr : flatRuns) {
                if (fr.spanStyleName == null) continue;
                OdtStyle ss = resolveStyleForParagraph(fr.spanStyleName);
                if (ss != null && ss.fontSize != null) { lastCellExplicitSize = true; break; }
            }
        }
        // v16t49 F-2b: 표시 텍스트가 전혀 없는 셀(빈 run 만)도 면제 — 8pt 보정은 보이는
        //   텍스트 축소용이라 빈 셀에는 의미가 없고, 원본엔 없는 8pt charPr 참조만 남긴다.
        if (!lastCellExplicitSize) {
            boolean hasText = false;
            for (Run fr : flatRuns) {
                if (fr.text != null && !fr.text.isEmpty()) { hasText = true; break; }
            }
            if (!hasText) lastCellExplicitSize = true;
        }
        if (flatRuns.isEmpty()) flatRuns.add(Run.plain("")); // 종전 동작 미러 (빈 셀)
        // v16t45 FIX C: 수식 셀 — 표시 텍스트가 링크/책갈피 없는 단일 run 일 때만
        //   FIELD_FORMULA 마커를 부착한다 (수식 셀은 실무상 항상 이 형태).
        if (formulaCommand != null && flatRuns.size() == 1) {
            Run only = flatRuns.get(0);
            if (only.linkHref == null && (only.bookmarkName == null || only.bookmarkName.isEmpty())
                    && !only.text.isEmpty()) {
                only.formulaCommand = formulaCommand;
            }
        }
        // 헤더 행이면 셀 paragraph 의 cellPara style 이 헤더용일 가능성 높음.
        //   ODT 의 HeaderCellPara 가 fo:color=#FFFFFF + bold 정의를 가지므로
        //   그것이 resolve 되어 charShape 매핑됨.
        ParagraphBlock pb = new ParagraphBlock(paraStyle);
        pb.runs.addAll(flatRuns);
        java.util.List<Paragraph> out = new java.util.ArrayList<>();
        out.add(buildParagraph(pb));
        // 셀 안의 표/이미지/수식을 빌드(표는 임의 깊이 재귀). 각 문단은 자체 inline-ext
        //   컨트롤(TABLE/GSO/EQUATION)을 보유하므로 SectionWriter 가 중첩 level 로 재귀 출력한다.
        for (Block eb : extras) {
            Paragraph ep;
            if (eb instanceof TableBlock)          ep = buildTableParagraph((TableBlock) eb, 1);
            else if (eb instanceof ImageBlock)     ep = buildImageParagraph((ImageBlock) eb);
            else                                   ep = buildEquationParagraph((EquationBlock) eb);
            if (ep != null) out.add(ep);
        }
        return out;
    }

    /**
     * v16t50 R1/R4: 셀 안 paragraph 가 2개 이상일 때 paragraph 경계 보존 빌드.
     * 각 ParagraphBlock/HeadingBlock 별로 buildParagraph/buildHeadingParagraph 호출 →
     * 각자의 textAlign·ParaShape 가 살아난다. 호출부의 8pt 셀 보정은 단일 paragraph 모드와
     * 동일 조건(셀 안 명시 font-size 가 어디에도 없으면 적용)으로 모든 paragraph 에 일괄.
     * extras(중첩표/이미지/수식) 도 종전과 같이 별도 paragraph 로 add.
     */
    private java.util.List<Paragraph> buildCellParagraphsMulti(TableBlock.Cell src, boolean header,
                                                               String formulaCommand) {
        java.util.List<Paragraph> out = new java.util.ArrayList<>();
        java.util.List<Block> extras = new java.util.ArrayList<>();
        boolean anyExplicitSize = false;
        boolean anyText = false;
        for (Block b : src.content) {
            if (b instanceof ParagraphBlock) {
                ParagraphBlock pb = (ParagraphBlock) b;
                OdtStyle ss = resolveStyleForParagraph(pb.paragraphStyleName);
                if (ss != null && ss.fontSize != null) anyExplicitSize = true;
                for (Run r : pb.runs) {
                    if (r.text != null && !r.text.isEmpty()) anyText = true;
                    if (r.spanStyleName != null) {
                        OdtStyle ts = resolveStyleForParagraph(r.spanStyleName);
                        if (ts != null && ts.fontSize != null) anyExplicitSize = true;
                    }
                }
                out.add(buildParagraph(pb));
            } else if (b instanceof HeadingBlock) {
                HeadingBlock hb = (HeadingBlock) b;
                for (Run r : hb.runs) {
                    if (r.text != null && !r.text.isEmpty()) anyText = true;
                }
                out.add(buildHeading(hb));
            } else if (b instanceof TableBlock || b instanceof ImageBlock || b instanceof EquationBlock) {
                extras.add(b);
            }
        }
        if (out.isEmpty()) {
            // 비어있는 셀 보정 — 빈 paragraph 1개
            ParagraphBlock empty = new ParagraphBlock(null);
            empty.runs.add(Run.plain(""));
            out.add(buildParagraph(empty));
        }
        // 셀 8pt 보정 면제 조건: 명시 크기 어딘가 있으면, 또는 텍스트 자체가 없으면.
        lastCellExplicitSize = anyExplicitSize || !anyText;
        for (Block eb : extras) {
            Paragraph ep;
            if (eb instanceof TableBlock)          ep = buildTableParagraph((TableBlock) eb, 1);
            else if (eb instanceof ImageBlock)     ep = buildImageParagraph((ImageBlock) eb);
            else                                   ep = buildEquationParagraph((EquationBlock) eb);
            if (ep != null) out.add(ep);
        }
        return out;
    }

    /**
     * v16t45 FIX B: 셀 평탄화 버퍼 비우기 — 누적 텍스트를 (있을 때만) href 가 달린
     *   plain run 으로 내보낸다. 반환값은 항상 null (curHref 리셋용).
     */
    private static String flushCellRun(java.util.List<Run> out, StringBuilder sb, String href, String span) {
        if (sb.length() > 0) {
            Run r = new Run(sb.toString(), span); // v16t48 F-2b: 구간의 span 스타일 보존
            r.linkHref = href;
            out.add(r);
            sb.setLength(0);
        }
        return null;
    }

    /**
     * 표 셀 안 본문용 charShape — 본문 10pt → 8pt 로 축소 (보너스 task15).
     * 캐싱.
     */
    private int ensureCellTextCharShape() {
        CharShape cs = newCharShape(0L, false, false, false, false, 800); // 8pt
        return ensureCharShape("cellText:8pt", cs);
    }

    /** 표용 기본 borderFill 1개 (4면 SOLID 검정 0.12mm) — 셀 배경 없음 (BodyCell 용). */
    private int ensureTableBorderFill() {
        String sig = "tableBorder:solid:black:0.12mm:none";
        Integer cached = borderFillCache.get(sig);
        if (cached != null) return cached;
        BorderFill bf = new BorderFill();
        Arrays.fill(bf.borderTypes, 1);          // 4면 SOLID
        Arrays.fill(bf.borderWidths, 1);         // 약 0.12mm 인덱스
        Arrays.fill(bf.borderColors, 0L);        // 검정
        bf.fillType = 0;                          // 채움 없음
        int id = doc.borderFills.size();
        doc.borderFills.add(bf);
        borderFillCache.put(sig, id);
        return id;
    }

    /**
     * 셀 전용 borderFill 등록. ODT 셀 스타일의 fo:background-color 가 있으면
     *   fillBgColor 채워진 borderFill 생성, 없으면 기본 (배경 없음) 반환.
     */
    /**
     * v16t50 R3/R5/R8: ODT 셀 style:vertical-align → HWP listHeaderProperty bits 5-6.
     * 0=TOP(default), 1=CENTER, 2=BOTTOM. 미명시 → 0 (byte 영향 없음).
     * 채은 실측 게이트 — case1 CENTER 2434·TOP 147·BOTTOM 14, TA-02 CENTER 39·TOP 24·BOTTOM 1.
     */
    private long cellVertAlignBits(OdtStyle cellStyle) {
        if (cellStyle == null || cellStyle.cellVerticalAlign == null) return 0L;
        String v = cellStyle.cellVerticalAlign.trim().toLowerCase(java.util.Locale.ROOT);
        switch (v) {
            case "middle": return 1L << 5; // CENTER
            case "bottom": return 2L << 5; // BOTTOM
            default:       return 0L;       // TOP / auto
        }
    }

    private int ensureCellBorderFill(OdtStyle cellStyle, boolean tableHasSelectiveBorders) {
        // v16.66: ODT 셀 per-side 테두리(fo:border-top/-bottom/-left/-right) → HWP BorderFill 4면 독립 매핑.
        //   anyRaw=false(4면 모두 미명시) 면 종전 4면 SOLID 폴백을 그대로 사용 → byte 불변(격리 핵심).
        //   anyRaw=true 면 명시변 SOLID / 미명시(또는 none)변 NONE 으로 selective 테두리 재현
        //   ('지방보조금 개요' 표 선유무 구분). 4-index 규약: 0=LEFT,1=RIGHT,2=TOP,3=BOTTOM.
        boolean anyRaw = cellStyle != null
                && (cellStyle.borderTop != null || cellStyle.borderBottom != null
                        || cellStyle.borderLeft != null || cellStyle.borderRight != null);
        ParsedBorder pbL = anyRaw ? ParsedBorder.parse(cellStyle.borderLeft)   : null;
        ParsedBorder pbR = anyRaw ? ParsedBorder.parse(cellStyle.borderRight)  : null;
        ParsedBorder pbT = anyRaw ? ParsedBorder.parse(cellStyle.borderTop)    : null;
        ParsedBorder pbB = anyRaw ? ParsedBorder.parse(cellStyle.borderBottom) : null;
        // dedup 서명: 4면 type/width/color 포함(4면 다른 셀 오폴딩 방지). 미명시 셀은 상수 "solid4"
        //   → 종전과 동일한 폴딩 관계 유지(문자열만 접미, 생성 BorderFill 바이트는 불변).
        String borderSig = anyRaw ? perSideBorderSig(pbL, pbR, pbT, pbB) : "solid4";

        // v16t51 S3: 셀 배경이 이미지(style:background-image) 면 BorderFill 의 ImageFill 비트로 등록.
        //   _diag/CellFillImgDump 실측 미러 — orig case1.hwp 셀 ImageFill 6건 모두
        //   fillType=2 (Image only) · imgType=5 (FitSize) · imgEffect=0 (RealPicture) · imgBinItemId=1~5,8.
        //   ODT background-image style:repeat="stretch" → FitSize 매핑. dedup 은 G1 패턴(ext+SHA-1+len) 재사용.
        //   미명시 셀(이미지·색 모두 null)은 종전 ensureTableBorderFill 경로 — byte 불변.
        if (cellStyle != null && cellStyle.cellBackgroundImageHref != null && currentModel != null) {
            int imgBinId = ensureCellBgBinData(cellStyle.cellBackgroundImageHref);
            if (imgBinId > 0) {
                String sig = "cellBorder:bgImg=" + imgBinId + ":rep=" + cellStyle.cellBackgroundImageRepeat
                        + ":" + borderSig;
                Integer cached = borderFillCache.get(sig);
                if (cached != null) return cached;
                BorderFill bf = new BorderFill();
                applyCellBorders(bf, anyRaw, pbL, pbR, pbT, pbB);
                bf.fillType    = 2;  // bit1 = ImageFill only (orig 실측 — Pattern 비트 동시 켜지 않음)
                bf.imgType     = mapOdtRepeatToImgType(cellStyle.cellBackgroundImageRepeat);
                bf.imgEffect   = 0;  // RealPicture
                bf.imgBright   = 0;
                bf.imgContrast = 0;
                bf.imgBinItemId = imgBinId;
                int id = doc.borderFills.size();
                doc.borderFills.add(bf);
                borderFillCache.put(sig, id);
                return id;
            }
        }
        if (cellStyle == null || cellStyle.cellBackgroundColor == null) {
            // 배경색 없음. per-side 미명시.
            if (!anyRaw) {
                // 업무3 후속(v16.68) Option A: 선택적 테두리 표의 '테두리 전무' 셀 → 4면 NONE(테두리 없음).
                //   ('지방보조금 개요' 제목표 row1-좌 빈셀이 원본 bf7 전변 NONE 과 일치.)
                //   명시 테두리 셀이 전무한 일반 표는 종전 4면 SOLID 폴백 유지 → byte 무회귀.
                if (!tableHasSelectiveBorders) return ensureTableBorderFill();
                final String sigNone = "cellBorder:noneAll:selectiveTbl";
                Integer cachedNone = borderFillCache.get(sigNone);
                if (cachedNone != null) return cachedNone;
                BorderFill bfNone = new BorderFill();
                Arrays.fill(bfNone.borderTypes, 0);   // NONE
                Arrays.fill(bfNone.borderWidths, 0);
                Arrays.fill(bfNone.borderColors, 0L);
                bfNone.fillType = 0;                  // 채움 없음
                int idNone = doc.borderFills.size();
                doc.borderFills.add(bfNone);
                borderFillCache.put(sigNone, idNone);
                return idNone;
            }
            String sig = "cellBorder:persideNoBg:" + borderSig;
            Integer cached = borderFillCache.get(sig);
            if (cached != null) return cached;
            BorderFill bf = new BorderFill();
            applyCellBorders(bf, true, pbL, pbR, pbT, pbB);
            bf.fillType = 0;   // 채움 없음
            int id = doc.borderFills.size();
            doc.borderFills.add(bf);
            borderFillCache.put(sig, id);
            return id;
        }
        long bgColor = parseHexColor(cellStyle.cellBackgroundColor, 0xFFFFFFFFL);
        String sig = "cellBorder:bg=" + Long.toHexString(bgColor) + ":" + borderSig;
        Integer cached = borderFillCache.get(sig);
        if (cached != null) return cached;
        BorderFill bf = new BorderFill();
        applyCellBorders(bf, anyRaw, pbL, pbR, pbT, pbB);
        bf.fillType    = 1;            // 단색 채움
        bf.fillBgColor = bgColor;
        bf.fillPatColor = bgColor;
        int id = doc.borderFills.size();
        doc.borderFills.add(bf);
        borderFillCache.put(sig, id);
        return id;
    }

    /**
     * v16.66: BorderFill 4면(0=LEFT,1=RIGHT,2=TOP,3=BOTTOM) 테두리 적용.
     *   anyRaw=false → 종전 4면 SOLID(type=1,width=1,color=0) 하드코딩과 byte 동일(격리 보장).
     *   anyRaw=true  → per-side: 명시변 SOLID(pb.widthHwp/colorRgb), 미명시(또는 none)변 NONE.
     */
    private static void applyCellBorders(BorderFill bf, boolean anyRaw,
            ParsedBorder pbL, ParsedBorder pbR, ParsedBorder pbT, ParsedBorder pbB) {
        if (!anyRaw) {
            Arrays.fill(bf.borderTypes, 1);
            Arrays.fill(bf.borderWidths, 1);
            Arrays.fill(bf.borderColors, 0L);
            return;
        }
        ParsedBorder[] sides = { pbL, pbR, pbT, pbB };   // 0=LEFT,1=RIGHT,2=TOP,3=BOTTOM
        for (int i = 0; i < 4; i++) {
            if (sides[i] != null) {
                bf.borderTypes[i]  = 1;                  // SOLID
                bf.borderWidths[i] = sides[i].widthHwp;
                bf.borderColors[i] = sides[i].colorRgb;
            } else {
                bf.borderTypes[i]  = 0;                  // NONE (선 없음)
                bf.borderWidths[i] = 0;
                bf.borderColors[i] = 0L;
            }
        }
    }

    /** v16.66: per-side 테두리 dedup 서명 — 4면 type/width/color 포함(4면 다른 셀 오폴딩 방지). */
    private static String perSideBorderSig(ParsedBorder pbL, ParsedBorder pbR, ParsedBorder pbT, ParsedBorder pbB) {
        return "L" + sideSig(pbL) + ",R" + sideSig(pbR) + ",T" + sideSig(pbT) + ",B" + sideSig(pbB);
    }

    private static String sideSig(ParsedBorder pb) {
        return pb == null ? "0" : ("1/" + pb.widthHwp + "/" + Long.toHexString(pb.colorRgb));
    }

    /**
     * v16t51 S3: ODT 셀 배경 이미지 href ("Pictures/imageN.{ext}") 의 raw bytes 를 G1 dedup 캐시
     * 통해 BinDataItem 으로 등록하고 1-based binDataId 반환. 미발견 시 0.
     */
    private int ensureCellBgBinData(String href) {
        if (href == null || currentModel == null) return 0;
        byte[] data = currentModel.images.get(href);
        if (data == null || data.length == 0) {
            int slash = href.lastIndexOf('/');
            if (slash >= 0) data = currentModel.images.get(href.substring(slash + 1));
        }
        if (data == null || data.length == 0) return 0;
        String ext = extractExtension(href);
        String dedupKey = ext + ":" + sha1Hex(data) + ":" + data.length;
        Integer binId = binDataDedupCache.get(dedupKey);
        if (binId != null) return binId;
        BinDataItem bdi = new BinDataItem();
        bdi.type = 1;
        bdi.binDataId = doc.binDataItems.size() + 1;
        bdi.relativePath = "BinData/" + String.format("BIN%04X.%s", bdi.binDataId, ext);
        bdi.extension = ext;
        bdi.data = data;
        doc.binDataItems.add(bdi);
        binDataDedupCache.put(dedupKey, bdi.binDataId);
        return bdi.binDataId;
    }

    /** v16t51 S7: ODT style:leader-style → HWP tabItem.fillType. dotted=3·dashed=2·solid=1·none=0. */
    private static int mapOdtLeaderToFillType(String leaderStyle) {
        if (leaderStyle == null) return 0;
        String s = leaderStyle.trim().toLowerCase(java.util.Locale.ROOT);
        switch (s) {
            case "dotted": return 3;
            case "dashed": return 2;
            case "solid":  return 1;
            case "none":
            default:       return 0;
        }
    }

    /** v16t51 S3: ODT style:repeat → HWP ImageFillType byte. 미명시·stretch→FitSize(5). */
    private static int mapOdtRepeatToImgType(String repeat) {
        if (repeat == null) return 5; // FitSize
        switch (repeat.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "repeat":     return 0; // TileAll
            case "no-repeat":  return 6; // Center
            case "stretch":
            default:           return 5; // FitSize (orig 실측 미러)
        }
    }

    // -------------------------------------------------------------------------
    //  CharShape / ParaShape / BorderFill 해결 + 캐싱
    // -------------------------------------------------------------------------

    /**
     * Heading 의 paraShape — {@code fo:border-bottom} 이 있으면 전용 borderFill + paraShape 생성.
     */
    private int resolveParaShapeForHeading(OdtStyle s) {
        if (s == null) return 0;
        // Task5/6 — heading 도 정렬/위·아래 여백 매핑 적용 (border 없는 경우도 처리)
        int alignType = 0;
        if (s.textAlign != null) {
            String a = s.textAlign.trim().toLowerCase(java.util.Locale.ROOT);
            // v16t51 S1: ODT 'start' 는 LTR default(=본문 정렬 미명시와 동등) — 방향1 hwp2odt 가
            //   JUSTIFY paragraph 를 'start' 로 잘못 수출하는 경우가 다수라 LEFT 과잉·JUSTIFY 부족 유발.
            //   'start' → alignType=0 (JUSTIFY) 으로 매핑. 'left' 명시는 그대로 LEFT 유지.
            if ("center".equals(a)) alignType = 3;
            else if ("right".equals(a) || "end".equals(a)) alignType = 2;
            else if ("left".equals(a)) alignType = 1;
            else if ("justify".equals(a) || "start".equals(a)) alignType = 0;
        }
        boolean hasMt = s.marginTop != null;
        boolean hasMb = s.marginBottom != null;
        int spaceBefore = hasMt ? lengthToHwpUnit(s.marginTop) : 0;
        int spaceAfterOverride = hasMb ? lengthToHwpUnit(s.marginBottom) : -1;
        boolean hasBorder = (s.borderBottom != null);
        if (!hasBorder && alignType == 0 && !hasMt && !hasMb) return 0;

        int borderFillId1Based = 0;
        int bfId = -1;
        if (hasBorder) {
            ParsedBorder pb = ParsedBorder.parse(s.borderBottom);
            if (pb != null) {
                // BorderFill 생성 — bottom 만 SOLID, 나머지 NONE
                // HWP 5.0 BorderFill 4면 순서 : index 0=LEFT, 1=RIGHT, 2=TOP, 3=BOTTOM.
                //   (HWPX 출력 검증으로 확인됨 — index 2 에 박으면 topBorder=SOLID 로 출력됨.)
                BorderFill bf = new BorderFill();
                Arrays.fill(bf.borderTypes, 0);   // NONE
                Arrays.fill(bf.borderWidths, 0);
                Arrays.fill(bf.borderColors, 0L);
                bf.borderTypes[3]  = 1;            // SOLID — bottom
                bf.borderWidths[3] = pb.widthHwp;
                bf.borderColors[3] = pb.colorRgb;
                bf.fillType = 0;
                String bfSig = "headingBorder:" + pb.widthHwp + ":" + Long.toHexString(pb.colorRgb);
                bfId = ensureBorderFill(bfSig, bf);
                // HwpWriter / hwp2hwpx 가 borderFill id 를 1-based 로 출력하므로
                //   paraShape.borderFillId 도 1-based (= ensureBorderFill 0-based id + 1) 로 부여.
                borderFillId1Based = bfId + 1;
            }
        }

        ParaShape ps = newParaShape(borderFillId1Based);
        if (borderFillId1Based > 0) ps.borderPadBottom = 50; // 약간의 여유
        // Task5 — property1 bit 2-4 = alignType
        ps.property1 = (ps.property1 & ~(0x7L << 2)) | (((long) alignType & 0x7) << 2);
        // Task6 — ODT 명시 margin-top/bottom 적용 (없으면 newParaShape 기본 유지)
        if (hasMt) ps.spaceBefore = spaceBefore;
        if (hasMb) ps.spaceAfter  = spaceAfterOverride;
        String psSig = "headingParaShape:" + bfId + ":al=" + alignType
                + ":mt=" + (hasMt ? spaceBefore : "n")
                + ":mb=" + (hasMb ? spaceAfterOverride : "n");
        return ensureParaShape(psSig, ps);
    }

    /**
     * ODT 스타일 → CharShape 매핑. 캐시 활용.
     */
    private int resolveCharShape(OdtStyle s) {
        if (s == null) return 0;
        long textColor = parseHexColor(s.textColor, 0L);
        boolean bold = Boolean.TRUE.equals(s.bold);
        boolean italic = Boolean.TRUE.equals(s.italic);
        boolean underline = Boolean.TRUE.equals(s.underline);
        boolean strike = Boolean.TRUE.equals(s.strike);
        int size = parseFontSize(s.fontSize, DEFAULT_FONT_SIZE_UNITS);
        CharShape cs = newCharShape(textColor, bold, italic, underline, strike, size);
        if (s.textBackgroundColor != null) {
            cs.shadeColor = parseHexColor(s.textBackgroundColor, 0xFFFFFFFFL);
        }
        // v16t52 T1-폰트 A2+B: ODT fontFamily/fontFamilyAsian 시그널 보존 — 미지정 paragraph 는
        //   fontId[*]=0(default '맑은 고딕') 종전 유지, 지정 시 ensureFaceName 으로 FaceName 그룹에 추가
        //   등록 후 CharShape.fontId 매핑.
        //   HWP FaceName 그룹 인덱스: 0=한글(HANGUL), 1=라틴(LATIN), 2=한자, 3=일본어, 4=기타, 5=기호, 6=사용자.
        //   따라서 fontFamilyAsian → group 0(한글 slot) / fontFamily → group 1(라틴 slot).
        String asian = (s.fontFamilyAsian != null) ? s.fontFamilyAsian : s.fontFamily;
        if (asian != null) {
            int fid = ensureFaceName(0, asian);
            cs.fontId[0] = fid;
        }
        if (s.fontFamily != null) {
            int fid = ensureFaceName(1, s.fontFamily);
            cs.fontId[1] = fid;
        }
        // v16t45 FIX E: fo:letter-spacing(절대길이) → HWP 자간 %(em 대비, INT8 ±50 클램프).
        //   hwp2odt FontMapper.letterSpacing(pt = pct/100 × fontPt) 의 역함수.
        //   자간 미지정 문서는 spacing 전부 0 (종전과 동일) — byte 불변.
        int spacingPct = letterSpacingPct(s.letterSpacing, size / 100.0);
        if (spacingPct != 0) Arrays.fill(cs.spacing, spacingPct);
        return ensureCharShape(signCharShape(cs), cs);
    }

    /**
     * v16t52 T1-폰트: FaceName 7개 그룹 중 group 에 폰트 이름을 등록(중복 시 기존 인덱스 반환).
     * 등록 시 fontType=1 (TTF)·hasAltFont/hasTypeInfo/hasDefaultFont=false 로 일관. 휴먼명조 등
     * 한국 글꼴 보존을 위한 v16t52 T1-폰트 진입점. group=0(영문)·1(한국어)·2~6(기타).
     */
    private int ensureFaceName(int group, String name) {
        if (group < 0 || group >= doc.faceNames.size() || name == null || name.isEmpty()) return 0;
        java.util.List<FaceName> list = doc.faceNames.get(group);
        for (int i = 0; i < list.size(); i++) {
            if (name.equals(list.get(i).name)) return i;
        }
        FaceName fn = new FaceName();
        fn.name = name;
        fn.fontType = 1;
        fn.hasAltFont = false;
        fn.hasTypeInfo = false;
        fn.hasDefaultFont = false;
        list.add(fn);
        return list.size() - 1;
    }

    /** v16t45 FIX E: 자간 절대길이("0.5pt"/"-0.018cm" 등) → %(pct=round(spPt/fontPt×100), clamp ±50). */
    private static int letterSpacingPct(String spec, double fontPt) {
        if (spec == null || spec.isEmpty() || "normal".equalsIgnoreCase(spec) || fontPt <= 0) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(-?\\d+(?:\\.\\d+)?)(pt|cm|mm|in)").matcher(spec.trim());
        if (!m.matches()) return 0;
        double v = Double.parseDouble(m.group(1));
        double pt;
        switch (m.group(2)) {
            case "cm": pt = v / 2.54 * 72.0; break;
            case "mm": pt = v / 25.4 * 72.0; break;
            case "in": pt = v * 72.0; break;
            default:   pt = v; break;
        }
        int pct = (int) Math.round(pt / fontPt * 100.0);
        return Math.max(-50, Math.min(50, pct));
    }

    private OdtStyle resolveStyleForParagraph(String styleName) {
        if (styleName == null) return null;
        OdtStyle s = styleMap.get(styleName);
        if (s == null) return null;
        return s.resolveText(styleMap);
    }

    // -------------------------------------------------------------------------
    //  ensure 캐시
    // -------------------------------------------------------------------------

    private int ensureCharShape(String sig, CharShape cs) {
        Integer id = charShapeCache.get(sig);
        if (id != null) return id;
        int newId = doc.charShapes.size();
        doc.charShapes.add(cs);
        charShapeCache.put(sig, newId);
        return newId;
    }

    private int ensureBorderFill(String sig, BorderFill bf) {
        Integer id = borderFillCache.get(sig);
        if (id != null) return id;
        int newId = doc.borderFills.size();
        doc.borderFills.add(bf);
        borderFillCache.put(sig, newId);
        return newId;
    }

    private int ensureParaShape(String sig, ParaShape ps) {
        Integer id = paraShapeCache.get(sig);
        if (id != null) return id;
        int newId = doc.paraShapes.size();
        doc.paraShapes.add(ps);
        paraShapeCache.put(sig, newId);
        return newId;
    }

    private static String signCharShape(CharShape cs) {
        StringBuilder fontKey = new StringBuilder();
        if (cs.fontId != null) {
            for (int i = 0; i < cs.fontId.length; i++) {
                if (i > 0) fontKey.append(',');
                fontKey.append(cs.fontId[i]);
            }
        }
        return "cs:" + cs.baseSize + ":" + Long.toHexString(cs.textColor) + ":" + Long.toHexString(cs.property)
                + ":" + Long.toHexString(cs.shadeColor) + ":f[" + fontKey + "]";
    }

    // -------------------------------------------------------------------------
    //  값 파싱
    // -------------------------------------------------------------------------

    /** "#RRGGBB" 또는 "#RGB" → 0x00BBGGRR 형식 (HWP 컬러 인코딩). 실패 시 defVal. */
    static long parseHexColor(String hex, long defVal) {
        if (hex == null) return defVal;
        String h = hex.trim();
        if (h.startsWith("#")) h = h.substring(1);
        try {
            int rgb;
            if (h.length() == 3) {
                rgb = ((Character.digit(h.charAt(0), 16) * 0x11) << 16)
                    | ((Character.digit(h.charAt(1), 16) * 0x11) << 8)
                    | (Character.digit(h.charAt(2), 16) * 0x11);
            } else if (h.length() == 6) {
                rgb = (int) Long.parseLong(h, 16);
            } else {
                return defVal;
            }
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            return ((long) b << 16) | ((long) g << 8) | r; // HWP : little-endian BGR
        } catch (NumberFormatException e) {
            return defVal;
        }
    }

    /** "16pt" → 1600 (1/100 pt 단위). */
    static int parseFontSize(String size, int defUnits) {
        if (size == null) return defUnits;
        String s = size.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if (s.endsWith("pt")) {
                double pt = Double.parseDouble(s.substring(0, s.length() - 2));
                return (int) Math.round(pt * 100);
            } else if (s.endsWith("in")) {
                double in = Double.parseDouble(s.substring(0, s.length() - 2));
                return (int) Math.round(in * 72 * 100);
            } else if (s.endsWith("mm")) {
                double mm = Double.parseDouble(s.substring(0, s.length() - 2));
                return (int) Math.round(mm / 25.4 * 72 * 100);
            }
        } catch (NumberFormatException ignored) { /* fall through */ }
        return defUnits;
    }

    // -------------------------------------------------------------------------
    //  ParsedBorder — "1pt solid #2C3E50" 파싱
    // -------------------------------------------------------------------------

    /**
     * ODT shorthand border 값을 파싱.
     *   ex) {@code "1pt solid #2C3E50"} → width 1pt, style solid, color #2C3E50.
     *   HWP 단위로 환산하여 보관.
     */
    static final class ParsedBorder {
        /** HWP border width : 0=0.1mm, 1=0.12mm, ... — 단순화하여 1pt 미만 = 1, 이상 = round(pt*4). */
        final int widthHwp;
        /** HWP 컬러 (BGR little-endian long). */
        final long colorRgb;

        private ParsedBorder(int widthHwp, long colorRgb) {
            this.widthHwp = widthHwp;
            this.colorRgb = colorRgb;
        }

        static ParsedBorder parse(String spec) {
            if (spec == null) return null;
            String s = spec.trim();
            if (s.isEmpty() || "none".equalsIgnoreCase(s)) return null;
            String[] parts = s.split("\\s+");
            double widthPt = 1.0;
            long color = 0L; // 기본 검정
            for (String p : parts) {
                if (p.endsWith("pt")) {
                    try { widthPt = Double.parseDouble(p.substring(0, p.length() - 2)); } catch (NumberFormatException ignored) {}
                } else if (p.endsWith("cm")) {
                    // v16.66: LibreOffice 기본 단위 cm 지원 (1cm = 10mm)
                    try { widthPt = Double.parseDouble(p.substring(0, p.length() - 2)) * 10.0 / 25.4 * 72; } catch (NumberFormatException ignored) {}
                } else if (p.endsWith("mm")) {
                    try { widthPt = Double.parseDouble(p.substring(0, p.length() - 2)) / 25.4 * 72; } catch (NumberFormatException ignored) {}
                } else if (p.endsWith("in")) {
                    try { widthPt = Double.parseDouble(p.substring(0, p.length() - 2)) * 72; } catch (NumberFormatException ignored) {}
                } else if (p.startsWith("#")) {
                    color = parseHexColor(p, 0L);
                }
                // "solid" / "dashed" / "dotted" 는 단순화 — 모두 SOLID 로 매핑
            }
            // HWP width index 매핑 단순화 : 0.4mm ≈ 4 units 가정
            int widthHwp = Math.max(1, (int) Math.round(widthPt * 4));
            return new ParsedBorder(widthHwp, color);
        }
    }
}
