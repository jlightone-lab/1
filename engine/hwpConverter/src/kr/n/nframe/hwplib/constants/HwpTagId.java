package kr.n.nframe.hwplib.constants;

public final class HwpTagId {
    private HwpTagId() {}

    public static final int HWPTAG_BEGIN = 0x010;

    // DocInfo 태그
    public static final int DOCUMENT_PROPERTIES     = HWPTAG_BEGIN;      // 0x010
    public static final int ID_MAPPINGS             = HWPTAG_BEGIN + 1;  // 0x011
    public static final int BIN_DATA                = HWPTAG_BEGIN + 2;  // 0x012
    public static final int FACE_NAME               = HWPTAG_BEGIN + 3;  // 0x013
    public static final int BORDER_FILL             = HWPTAG_BEGIN + 4;  // 0x014
    public static final int CHAR_SHAPE              = HWPTAG_BEGIN + 5;  // 0x015
    public static final int TAB_DEF                 = HWPTAG_BEGIN + 6;  // 0x016
    public static final int NUMBERING               = HWPTAG_BEGIN + 7;  // 0x017
    public static final int BULLET                  = HWPTAG_BEGIN + 8;  // 0x018
    public static final int PARA_SHAPE              = HWPTAG_BEGIN + 9;  // 0x019
    public static final int STYLE                   = HWPTAG_BEGIN + 10; // 0x01A
    public static final int DOC_DATA                = HWPTAG_BEGIN + 11; // 0x01B
    public static final int DISTRIBUTE_DOC_DATA     = HWPTAG_BEGIN + 12; // 0x01C
    public static final int COMPATIBLE_DOCUMENT     = HWPTAG_BEGIN + 14; // 0x01E
    public static final int LAYOUT_COMPATIBILITY    = HWPTAG_BEGIN + 15; // 0x01F
    public static final int TRACKCHANGE             = HWPTAG_BEGIN + 16; // 0x020
    public static final int MEMO_SHAPE              = HWPTAG_BEGIN + 76; // 0x05C
    public static final int FORBIDDEN_CHAR          = HWPTAG_BEGIN + 78; // 0x05E
    public static final int TRACK_CHANGE            = HWPTAG_BEGIN + 80; // 0x060
    public static final int TRACK_CHANGE_AUTHOR     = HWPTAG_BEGIN + 81; // 0x061

    // BodyText 태그
    public static final int PARA_HEADER             = HWPTAG_BEGIN + 50; // 0x042
    public static final int PARA_TEXT               = HWPTAG_BEGIN + 51; // 0x043
    public static final int PARA_CHAR_SHAPE         = HWPTAG_BEGIN + 52; // 0x044
    public static final int PARA_LINE_SEG           = HWPTAG_BEGIN + 53; // 0x045
    public static final int PARA_RANGE_TAG          = HWPTAG_BEGIN + 54; // 0x046
    public static final int CTRL_HEADER             = HWPTAG_BEGIN + 55; // 0x047
    public static final int LIST_HEADER             = HWPTAG_BEGIN + 56; // 0x048
    public static final int PAGE_DEF                = HWPTAG_BEGIN + 57; // 0x049
    public static final int FOOTNOTE_SHAPE          = HWPTAG_BEGIN + 58; // 0x04A
    public static final int PAGE_BORDER_FILL        = HWPTAG_BEGIN + 59; // 0x04B
    public static final int SHAPE_COMPONENT         = HWPTAG_BEGIN + 60; // 0x04C
    public static final int TABLE                   = HWPTAG_BEGIN + 61; // 0x04D
    public static final int SHAPE_COMPONENT_LINE    = HWPTAG_BEGIN + 62; // 0x04E
    public static final int SHAPE_COMPONENT_RECT    = HWPTAG_BEGIN + 63; // 0x04F
    public static final int SHAPE_COMPONENT_ELLIPSE = HWPTAG_BEGIN + 64; // 0x050
    public static final int SHAPE_COMPONENT_ARC     = HWPTAG_BEGIN + 65; // 0x051
    public static final int SHAPE_COMPONENT_POLYGON = HWPTAG_BEGIN + 66; // 0x052
    public static final int SHAPE_COMPONENT_CURVE   = HWPTAG_BEGIN + 67; // 0x053
    public static final int SHAPE_COMPONENT_OLE     = HWPTAG_BEGIN + 68; // 0x054
    public static final int SHAPE_COMPONENT_PICTURE = HWPTAG_BEGIN + 69; // 0x055
    public static final int SHAPE_COMPONENT_CONTAINER = HWPTAG_BEGIN + 70; // 0x056
    public static final int CTRL_DATA               = HWPTAG_BEGIN + 71; // 0x057
    public static final int EQEDIT                  = HWPTAG_BEGIN + 72; // 0x058
    public static final int SHAPE_COMPONENT_TEXTART = HWPTAG_BEGIN + 74; // 0x05A
    public static final int FORM_OBJECT             = HWPTAG_BEGIN + 75; // 0x05B
    public static final int MEMO_LIST               = HWPTAG_BEGIN + 77; // 0x05D
    public static final int CHART_DATA              = HWPTAG_BEGIN + 79; // 0x05F
    public static final int VIDEO_DATA              = HWPTAG_BEGIN + 82; // 0x062
    public static final int SHAPE_COMPONENT_UNKNOWN = HWPTAG_BEGIN + 99; // 0x073
}
