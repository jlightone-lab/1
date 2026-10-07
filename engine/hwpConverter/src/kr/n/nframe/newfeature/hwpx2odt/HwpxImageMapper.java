package kr.n.nframe.newfeature.hwpx2odt;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.common.AttachedFile;
import kr.dogfoot.hwpxlib.object.content.context_hpf.ManifestItem;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Picture;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.shapeobject.ShapePosition;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.shapeobject.ShapeSize;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.HorzAlign;

import kr.n.nframe.newfeature.odtcommon.OdtBuildContext;
import kr.n.nframe.newfeature.odtcommon.Units;

/**
 * HWPX 이미지(Picture) → ODT draw:frame + Pictures/ 임베드.
 * 바이너리는 매니페스트(binaryItemIDRef)에서 이미 디코딩된 byte[] 로 획득(인플레이트 불필요).
 */
public final class HwpxImageMapper {

    private final HWPXFile hwpx;
    private final OdtBuildContext ctx;

    public HwpxImageMapper(HWPXFile hwpx, OdtBuildContext ctx) {
        this.hwpx = hwpx;
        this.ctx = ctx;
    }

    /** 글자처럼취급(as-char) 여부. pos 없거나 treatAsChar=true 면 inline. */
    public boolean isInline(Picture pic) {
        ShapePosition pos = pic.pos();
        if (pos == null) return true;
        Boolean tac = pos.treatAsChar();
        return tac == null ? true : tac;
    }

    /** 그림 가로 정렬 → ODT 문단 정렬 키워드(center/end) 또는 null(좌측 기본). */
    public String alignKeyword(Picture pic) {
        ShapePosition pos = pic.pos();
        if (pos == null) return null;
        HorzAlign a = pos.horzAlign();
        if (a == HorzAlign.CENTER) return "center";
        if (a == HorzAlign.RIGHT || a == HorzAlign.OUTSIDE) return "end";
        return null;
    }

    /**
     * 매니페스트 binaryItemIDRef 로 임베드 이미지를 추출·등록하고 "Pictures/imageN.ext" 반환.
     * 추출 실패 시 null. 셀 배경 이미지필(imgBrush) 등 draw:frame 외 경로에서 재사용한다.
     */
    public String registerByBinId(String binId) {
        if (binId == null || binId.isEmpty()) return null;
        ManifestItem mi = hwpx.contentHPFFile() == null ? null
                : hwpx.contentHPFFile().getManifestItemById(binId);
        if (mi == null || !mi.hasAttachedFile()) return null;
        AttachedFile af = mi.attachedFile();
        byte[] data = (af == null) ? null : af.data();
        if (data == null || data.length == 0) return null;
        return ctx.pictures.add(data, extensionFor(mi));
    }

    public void emit(Picture pic, StringBuilder out, boolean inline) {
        String binId = (pic.img() == null) ? null : pic.img().binaryItemIDRef();
        if (binId == null || binId.isEmpty()) return;

        String href = registerByBinId(binId);
        if (href == null) return;

        String w = dim(pic.sz(), true);
        String h = dim(pic.sz(), false);

        out.append("<draw:frame text:anchor-type=\"")
           .append(inline ? "as-char" : "paragraph").append("\"");
        if (w != null) out.append(" svg:width=\"").append(w).append("\"");
        if (h != null) out.append(" svg:height=\"").append(h).append("\"");
        out.append(">");
        out.append("<draw:image xlink:href=\"").append(href)
           .append("\" xlink:type=\"simple\" xlink:show=\"embed\" xlink:actuate=\"onLoad\"/>");
        out.append("</draw:frame>");
    }

    /** href 확장자 우선, 없으면 mediaType 에서 추정. */
    private static String extensionFor(ManifestItem mi) {
        String href = mi.href();
        if (href != null) {
            int dot = href.lastIndexOf('.');
            if (dot >= 0 && dot < href.length() - 1) {
                String e = href.substring(dot + 1).trim();
                if (!e.isEmpty()) return e;
            }
        }
        String mt = mi.mediaType();
        if (mt != null) {
            int slash = mt.lastIndexOf('/');
            if (slash >= 0 && slash < mt.length() - 1) {
                String e = mt.substring(slash + 1);
                if (e.startsWith("x-")) e = e.substring(2);
                return e;
            }
        }
        return "png";
    }

    private static String dim(ShapeSize sz, boolean width) {
        if (sz == null) return null;
        Long v = width ? sz.width() : sz.height();
        if (v == null || v <= 0) return null;
        return Units.hwpToCm(v);
    }
}
