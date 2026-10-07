package kr.n.nframe.newfeature.hwp2odt;

import java.util.ArrayList;
import java.util.List;

import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlType;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;

import kr.n.nframe.newfeature.odtcommon.OdtBuildContext;

/**
 * 머리글/바닥글 컨트롤 → styles.xml master-page 의 style:header / style:footer.
 * 내부 문단은 Traverser 로 재귀 발화.
 */
public final class HeaderFooterMapper {

    private final HwpDocumentTraverser traverser;
    private final OdtBuildContext ctx;

    public HeaderFooterMapper(HwpDocumentTraverser traverser, OdtBuildContext ctx) {
        this.traverser = traverser;
        this.ctx = ctx;
    }

    public void emit(Control c, ControlType type) {
        List<Paragraph> paras = paragraphsOf(c);
        if (paras.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        traverser.emitParagraphs(paras, sb);
        if (type == ControlType.Header) ctx.appendHeader(sb.toString());
        else ctx.appendFooter(sb.toString());
    }

    private List<Paragraph> paragraphsOf(Control c) {
        List<Paragraph> list = new ArrayList<>();
        try {
            Object pl = c.getClass().getMethod("getParagraphList").invoke(c);
            if (pl == null) return list;
            int cnt = (int) pl.getClass().getMethod("getParagraphCount").invoke(pl);
            java.lang.reflect.Method getM = pl.getClass().getMethod("getParagraph", int.class);
            for (int i = 0; i < cnt; i++) list.add((Paragraph) getM.invoke(pl, i));
        } catch (Throwable ignore) {}
        return list;
    }
}
