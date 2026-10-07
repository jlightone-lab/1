package kr.n.nframe.newfeature.hwp2odt;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.Inflater;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bindata.EmbeddedBinaryData;
import kr.dogfoot.hwplib.object.docinfo.BinData;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.RelativeArrange;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlPicture;

import kr.n.nframe.newfeature.odtcommon.OdtBuildContext;
import kr.n.nframe.newfeature.odtcommon.Units;

/**
 * HWP 이미지(ControlPicture) → ODT draw:frame + Pictures/ 임베드.
 * xlink:show=embed, manifest 미디어타입 완결(서연 리서치 §8).
 */
public final class ImageMapper {

    private final HWPFile hwp;
    private final OdtBuildContext ctx;

    public ImageMapper(HWPFile hwp, OdtBuildContext ctx) {
        this.hwp = hwp;
        this.ctx = ctx;
    }

    public boolean isPicture(Control c) {
        return c instanceof ControlPicture;
    }

    /** 글자처럼취급(as-char) 여부. 알 수 없으면 inline(true) 기본. */
    public boolean isInline(Control c) {
        try {
            Object h = c.getClass().getMethod("getHeader").invoke(c);
            if (h != null) {
                Object prop = h.getClass().getMethod("getProperty").invoke(h);
                if (prop != null) {
                    Object like = prop.getClass().getMethod("isLikeLetter").invoke(prop);
                    if (like instanceof Boolean) return (Boolean) like;
                }
            }
        } catch (Throwable ignore) {}
        return true;
    }

    /**
     * 그림 가로 정렬 → ODT 문단 정렬 키워드(center/end) 또는 null(좌측 기본).
     * HWP GsoHeaderProperty.getHorzRelativeArrange(): TopOrLeft/Center/BottomOrRight.
     */
    public String alignKeyword(Control c) {
        RelativeArrange a = horzArrange(c);
        if (a == RelativeArrange.Center) return "center";
        if (a == RelativeArrange.BottomOrRight) return "end";
        return null; // TopOrLeft/기타 → 기본 좌측
    }

    private RelativeArrange horzArrange(Control c) {
        try {
            Object h = c.getClass().getMethod("getHeader").invoke(c);
            if (h != null) {
                Object prop = h.getClass().getMethod("getProperty").invoke(h);
                if (prop != null) {
                    Object arr = prop.getClass().getMethod("getHorzRelativeArrange").invoke(prop);
                    if (arr instanceof RelativeArrange) return (RelativeArrange) arr;
                }
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /**
     * BinData ID 로 임베드 이미지를 추출·디코딩·등록하고 "Pictures/imageN.ext" 반환(실패 시 null).
     * 셀 배경 이미지필(ImageFill) 등 draw:frame 외 경로에서 재사용한다.
     */
    public String registerByBinId(int binId) {
        byte[] data = embeddedBytes(binId);
        if (data == null || data.length == 0) return null;
        byte[] decoded = maybeInflate(data);
        String ext = extFromMagic(decoded, extensionFor(binId));
        return ctx.pictures.add(decoded, ext);
    }

    public void emit(Control c, StringBuilder out, boolean inline) {
        if (!(c instanceof ControlPicture)) return;
        ControlPicture pic = (ControlPicture) c;

        int binId;
        try {
            binId = pic.getShapeComponentPicture().getPictureInfo().getBinItemID();
        } catch (Throwable t) { return; }

        String href = registerByBinId(binId);
        if (href == null) return;

        String w = dim(c, true);
        String h = dim(c, false);

        out.append("<draw:frame text:anchor-type=\"")
           .append(inline ? "as-char" : "paragraph").append("\"");
        if (w != null) out.append(" svg:width=\"").append(w).append("\"");
        if (h != null) out.append(" svg:height=\"").append(h).append("\"");
        out.append(">");
        out.append("<draw:image xlink:href=\"").append(href)
           .append("\" xlink:type=\"simple\" xlink:show=\"embed\" xlink:actuate=\"onLoad\"/>");
        out.append("</draw:frame>");
    }

    private byte[] embeddedBytes(int binId) {
        try {
            if (hwp.getBinData() == null) return null;
            List<EmbeddedBinaryData> emb = hwp.getBinData().getEmbeddedBinaryDataList();
            if (emb == null || emb.isEmpty()) return null;
            // 저장된 이름(BIN+4자리 16진수+확장자)으로 매칭. DocInfo BinDataList 와
            // EmbeddedBinaryDataList 의 순서가 다르므로 인덱스로 찾으면 안 된다.
            String prefix = String.format("BIN%04X", binId);    // 16진(대문자)
            String prefixDec = String.format("BIN%04d", binId); // 10진(안전망)
            for (EmbeddedBinaryData e : emb) {
                if (e == null) continue;
                String name = e.getName();
                if (name == null) continue;
                String up = name.toUpperCase();
                if (up.startsWith(prefix) || up.startsWith(prefixDec)) {
                    return e.getData();
                }
            }
            // 이름이 규약을 따르지 않는 파일을 위한 폴백(기존 동작): 위치 기반.
            int idx = binDataIndex(binId);
            if (idx < 0 || idx >= emb.size()) idx = Math.max(0, Math.min(emb.size() - 1, binId - 1));
            EmbeddedBinaryData e = emb.get(idx);
            return e == null ? null : e.getData();
        } catch (Throwable t) { return null; }
    }

    /** 디코딩된 바이트의 매직으로 확장자 추정(DocInfo 확장자 신뢰 불가). */
    private static String extFromMagic(byte[] d, String fallback) {
        if (d == null || d.length < 4) return fallback;
        int b0 = d[0] & 0xFF, b1 = d[1] & 0xFF, b2 = d[2] & 0xFF, b3 = d[3] & 0xFF;
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return "png"; // PNG
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return "jpg";               // JPEG
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return "gif";               // GIF
        if (b0 == 0x42 && b1 == 0x4D) return "bmp";                             // BMP
        if (b0 == 0xD7 && b1 == 0xCD) return "wmf";                             // WMF (placeable)
        if (b0 == 0x01 && b1 == 0x00 && b2 == 0x00 && b3 == 0x00) return "emf"; // EMF
        return fallback;
    }

    private int binDataIndex(int binId) {
        try {
            List<BinData> list = hwp.getDocInfo().getBinDataList();
            if (list == null) return -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).getBinDataID() == binId) return i;
            }
        } catch (Throwable ignore) {}
        return -1;
    }

    private String extensionFor(int binId) {
        try {
            List<BinData> list = hwp.getDocInfo().getBinDataList();
            int idx = binDataIndex(binId);
            if (list != null && idx >= 0 && idx < list.size()) {
                String e = list.get(idx).getExtensionForEmbedding();
                if (e != null && !e.isEmpty()) return e;
            }
        } catch (Throwable ignore) {}
        return "png";
    }

    /** 이미지 매직이 없으면 raw deflate 로 간주하여 해제 시도. */
    private static byte[] maybeInflate(byte[] data) {
        if (looksLikeImage(data)) return data;
        byte[] r = inflate(data, true);
        if (looksLikeImage(r)) return r;
        r = inflate(data, false);
        if (looksLikeImage(r)) return r;
        return data; // 알 수 없으면 원본 그대로(매니페스트로 LibreOffice 가 판단)
    }

    private static boolean looksLikeImage(byte[] d) {
        if (d == null || d.length < 4) return false;
        int b0 = d[0] & 0xFF, b1 = d[1] & 0xFF, b2 = d[2] & 0xFF, b3 = d[3] & 0xFF;
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return true; // PNG
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return true;               // JPEG
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return true;               // GIF
        if (b0 == 0x42 && b1 == 0x4D) return true;                             // BMP
        if (b0 == 0xD7 && b1 == 0xCD) return true;                             // WMF (placeable)
        if (b0 == 0x01 && b1 == 0x00 && b2 == 0x00 && b3 == 0x00) return true; // EMF
        return false;
    }

    private static byte[] inflate(byte[] data, boolean nowrap) {
        try {
            Inflater inf = new Inflater(nowrap);
            inf.setInput(data);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, data.length * 3));
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                }
                bos.write(buf, 0, n);
            }
            inf.end();
            return bos.toByteArray();
        } catch (Throwable t) { return new byte[0]; }
    }

    /** width(true)/height(false) → cm 문자열. CtrlHeaderGso getWidth/getHeight(HWPUNIT). */
    private static String dim(Control c, boolean width) {
        try {
            Object h = c.getClass().getMethod("getHeader").invoke(c);
            if (h != null) {
                Object v = h.getClass().getMethod(width ? "getWidth" : "getHeight").invoke(h);
                if (v instanceof Number) {
                    long hu = ((Number) v).longValue();
                    if (hu > 0) return Units.hwpToCm(hu);
                }
            }
        } catch (Throwable ignore) {}
        return null;
    }
}
