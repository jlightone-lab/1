package kr.n.nframe.newfeature.odtcommon;

/**
 * 변환 진행 중 누적되는 출력 상태의 단일 보관소.
 *
 * <p>Mapper 들은 이 컨텍스트에만 기록한다(OdtWriter/Packager 를 직접 알지 못함 — 단방향 비순환).
 * body = content.xml 의 &lt;office:text&gt; 내부 / masterContent = 머리글·바닥글(styles.xml master-page).
 */
public final class OdtBuildContext {

    public final StyleRegistry styles = new StyleRegistry();
    public final PictureRegistry pictures = new PictureRegistry();
    /** 임베디드 수식(ODF Math) 객체 누적. ObjectNN/content.xml 페이로드 보관. */
    public final MathRegistry maths = new MathRegistry();

    /** content.xml 본문 (office:text 내부). */
    private final StringBuilder body = new StringBuilder(4096);

    /** styles.xml 의 master-page 안에 들어갈 머리글 XML(<style:header> 내부). */
    private final StringBuilder headerContent = new StringBuilder();
    /** 〃 바닥글(<style:footer> 내부). */
    private final StringBuilder footerContent = new StringBuilder();

    private String title = "";
    private int bookmarkSeq = 0;

    public void append(String xml) { body.append(xml); }
    public String body() { return body.toString(); }

    public void appendHeader(String xml) { headerContent.append(xml); }
    public void appendFooter(String xml) { footerContent.append(xml); }
    public boolean hasHeader() { return headerContent.length() > 0; }
    public boolean hasFooter() { return footerContent.length() > 0; }
    public String headerContent() { return headerContent.toString(); }
    public String footerContent() { return footerContent.toString(); }

    public void setTitle(String t) { if (t != null) this.title = t; }
    public String title() { return title; }

    public int nextBookmarkId() { return ++bookmarkSeq; }
}
