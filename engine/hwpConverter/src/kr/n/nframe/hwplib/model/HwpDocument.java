package kr.n.nframe.hwplib.model;

import java.util.ArrayList;
import java.util.List;

public class HwpDocument {
    public DocProperties docProperties = new DocProperties();
    public IdMappings idMappings = new IdMappings();
    public List<BinDataItem> binDataItems = new ArrayList<>();
    public List<List<FaceName>> faceNames = new ArrayList<>(); // 7개 언어 그룹
    public List<BorderFill> borderFills = new ArrayList<>();
    public List<CharShape> charShapes = new ArrayList<>();
    public List<TabDef> tabDefs = new ArrayList<>();
    public List<Numbering> numberings = new ArrayList<>();
    public List<Bullet> bullets = new ArrayList<>();
    public List<ParaShape> paraShapes = new ArrayList<>();
    public List<Style> styles = new ArrayList<>();
    public List<Section> sections = new ArrayList<>();

    // ---- 호환 설정 (HWPX header.xml 에서 읽음) ----
    /** 0=HWPCurrent, 1=HWP2007, 2=MSWord (HWP 측 매핑). */
    public int compatTargetProgram = 0;
    /** HWPTAG_LAYOUT_COMPATIBILITY용 5개의 UINT32 레벨 마스크. */
    public long[] layoutCompatLevels = new long[] { 0, 0, 0, 0, 0 };
    /** false면 COMPATIBLE_DOCUMENT 레코드를 생략한다(ODT 소스 경로). */
    public boolean emitCompatibleDocument = true;
}
