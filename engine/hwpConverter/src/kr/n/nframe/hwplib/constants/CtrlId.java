package kr.n.nframe.hwplib.constants;

public final class CtrlId {
    private CtrlId() {}

    public static int make(char a, char b, char c, char d) {
        return ((a & 0xFF) << 24) | ((b & 0xFF) << 16) | ((c & 0xFF) << 8) | (d & 0xFF);
    }

    // 개체 컨트롤
    public static final int TABLE     = make('t', 'b', 'l', ' ');
    public static final int LINE      = make('$', 'l', 'i', 'n');
    public static final int RECTANGLE = make('$', 'r', 'e', 'c');
    public static final int ELLIPSE   = make('$', 'e', 'l', 'l');
    public static final int ARC       = make('$', 'a', 'r', 'c');
    public static final int POLYGON   = make('$', 'p', 'o', 'l');
    public static final int CURVE     = make('$', 'c', 'u', 'r');
    public static final int EQUATION  = make('e', 'q', 'e', 'd');
    public static final int PICTURE   = make('$', 'p', 'i', 'c');
    public static final int GSO       = make('g', 's', 'o', ' ');
    public static final int OLE       = make('$', 'o', 'l', 'e');
    public static final int CONTAINER = make('$', 'c', 'o', 'n');

    // 개체가 아닌 컨트롤
    public static final int SECTION_DEF  = make('s', 'e', 'c', 'd');
    public static final int COLUMN_DEF   = make('c', 'o', 'l', 'd');
    public static final int HEADER       = make('h', 'e', 'a', 'd');
    public static final int FOOTER       = make('f', 'o', 'o', 't');
    public static final int FOOTNOTE     = make('f', 'n', ' ', ' ');
    public static final int ENDNOTE      = make('e', 'n', ' ', ' ');
    public static final int AUTO_NUMBER  = make('a', 't', 'n', 'o');
    public static final int NEW_NUMBER   = make('n', 'w', 'n', 'o');
    public static final int PAGE_HIDE    = make('p', 'g', 'h', 'd');
    public static final int PAGE_ODD_EVEN = make('p', 'g', 'c', 't');
    public static final int PAGE_NUM_POS = make('p', 'g', 'n', 'p');
    public static final int INDEX_MARK   = make('i', 'd', 'x', 'm');
    public static final int BOOKMARK     = make('b', 'o', 'k', 'm');
    public static final int TEXT_OVERLAP = make('t', 'c', 'p', 's');
    public static final int RUBY_TEXT    = make('t', 'd', 'u', 't');
    public static final int HIDDEN_COMMENT = make('t', 'c', 'm', 't');

    // 필드 컨트롤
    public static final int FIELD_HYPERLINK  = make('%', 'h', 'l', 'k');
    public static final int FIELD_BOOKMARK   = make('%', 'b', 'm', 'k');
    public static final int FIELD_CLICKHERE  = make('%', 'c', 'l', 'k');
    public static final int FIELD_FORMULA    = make('%', 'f', 'm', 'u');
    public static final int FIELD_DATE       = make('%', 'd', 't', 'e');
    public static final int FIELD_UNKNOWN    = make('%', 'u', 'n', 'k');
    public static final int FIELD_MEMO       = make('%', '%', 'm', 'e');
    public static final int FIELD_CROSSREF   = make('%', 'x', 'r', 'f');
    public static final int FIELD_PRIVATE_INFO = make('%', 'c', 'p', 'r');
    public static final int FIELD_TOC        = make('%', 't', 'o', 'c');
}
