package kr.n.nframe.test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.Inflater;
import org.apache.poi.poifs.filesystem.*;

/**
 * HWP 5.0 바이너리 덤프 및 검증 도구 (종합).
 *
 * HWP 5.0 명세(revision 1.3:20181108)에 따라 HWP 파일의 모든 바이트를
 * 파싱합니다. 모든 필드를 명세와 대조하여 검증합니다.
 * 두 개의 인자로 실행하면 두 파일을 필드 단위로 비교합니다.
 *
 * 사용법:
 *   java HwpDumper file.hwp                  -- 단일 파일 덤프
 *   java HwpDumper ref.hwp output.hwp        -- 두 파일 비교
 *
 * 출력은 ASCII 전용입니다 (한글 문자 없음).
 *
 * "본 제품은 한글과컴퓨터의 한/글 문서 파일(.hwp) 공개 문서를
 *  참고하여 개발되었습니다."
 */
public class HwpDumper {

    // ========================================================================
    // 통계
    // ========================================================================
    static int errorCount = 0;
    static int warnCount = 0;
    static int passCount = 0;

    static void error(String msg)   { System.out.println("  ERROR: " + msg); errorCount++; }
    static void warn(String msg)    { System.out.println("  WARN : " + msg); warnCount++; }
    static void pass(String msg)    { System.out.println("  PASS : " + msg); passCount++; }
    static void info(String msg)    { System.out.println("  INFO : " + msg); }
    static void field(String name, String value) {
        System.out.printf("    %-40s = %s%n", name, value);
    }
    static void fieldHex(String name, long value) {
        System.out.printf("    %-40s = 0x%08X (%d)%n", name, value, value);
    }
    static void fieldHex16(String name, int value) {
        System.out.printf("    %-40s = 0x%04X (%d)%n", name, value, value);
    }

    // ========================================================================
    // 바이트 읽기 유틸리티 (little-endian)
    // ========================================================================
    static int u8(byte[] d, int o) {
        if (o < 0 || o >= d.length) return 0;
        return d[o] & 0xFF;
    }
    static int u16(byte[] d, int o) {
        if (o + 1 >= d.length) return 0;
        return (d[o] & 0xFF) | ((d[o+1] & 0xFF) << 8);
    }
    static int i16(byte[] d, int o) {
        int v = u16(d, o);
        return (v >= 0x8000) ? v - 0x10000 : v;
    }
    static long u32(byte[] d, int o) {
        if (o + 3 >= d.length) return 0;
        return ((long)(d[o]&0xFF)) | ((long)(d[o+1]&0xFF)<<8)
             | ((long)(d[o+2]&0xFF)<<16) | ((long)(d[o+3]&0xFF)<<24);
    }
    static int i32(byte[] d, int o) {
        if (o + 3 >= d.length) return 0;
        return (d[o]&0xFF) | ((d[o+1]&0xFF)<<8) | ((d[o+2]&0xFF)<<16) | ((d[o+3]&0xFF)<<24);
    }

    static String colorStr(long c) {
        int r = (int)(c & 0xFF);
        int g = (int)((c >> 8) & 0xFF);
        int b = (int)((c >> 16) & 0xFF);
        return String.format("RGB(%d,%d,%d) [0x%06X]", r, g, b, c & 0xFFFFFF);
    }

    static String hexDump(byte[] d, int off, int len) {
        StringBuilder sb = new StringBuilder();
        int end = Math.min(off + len, d.length);
        for (int i = off; i < end; i++) {
            if (i > off) sb.append(' ');
            sb.append(String.format("%02X", d[i] & 0xFF));
        }
        return sb.toString();
    }

    /** UTF-16LE WCHAR 문자열 읽기 */
    static String readWchar(byte[] d, int off, int wcharCount) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < wcharCount; i++) {
            int idx = off + i * 2;
            if (idx + 1 >= d.length) break;
            int ch = u16(d, idx);
            if (ch >= 0x20 && ch < 0x7F) {
                sb.append((char) ch);
            } else {
                sb.append(String.format("\\u%04X", ch));
            }
        }
        return sb.toString();
    }

    /** 4바이트 control ID (big-endian packed)를 ASCII 문자열로 변환 */
    static String ctrlIdStr(long id) {
        char c1 = (char)((id >> 24) & 0xFF);
        char c2 = (char)((id >> 16) & 0xFF);
        char c3 = (char)((id >> 8) & 0xFF);
        char c4 = (char)(id & 0xFF);
        return "" + c1 + c2 + c3 + c4;
    }

    /** MAKE_4CHID처럼 4문자 ID 생성 */
    static long make4chid(char a, char b, char c, char d) {
        return (((long)a) << 24) | (((long)b) << 16) | (((long)c) << 8) | ((long)d);
    }

    // ========================================================================
    // Tag ID 상수 (HWPTAG_BEGIN = 0x010)
    // ========================================================================
    static final int HWPTAG_BEGIN = 0x010;

    // DocInfo 태그
    static final int TAG_DOCUMENT_PROPERTIES     = HWPTAG_BEGIN;      // 0x010
    static final int TAG_ID_MAPPINGS             = HWPTAG_BEGIN + 1;  // 0x011
    static final int TAG_BIN_DATA                = HWPTAG_BEGIN + 2;  // 0x012
    static final int TAG_FACE_NAME               = HWPTAG_BEGIN + 3;  // 0x013
    static final int TAG_BORDER_FILL             = HWPTAG_BEGIN + 4;  // 0x014
    static final int TAG_CHAR_SHAPE              = HWPTAG_BEGIN + 5;  // 0x015
    static final int TAG_TAB_DEF                 = HWPTAG_BEGIN + 6;  // 0x016
    static final int TAG_NUMBERING               = HWPTAG_BEGIN + 7;  // 0x017
    static final int TAG_BULLET                  = HWPTAG_BEGIN + 8;  // 0x018
    static final int TAG_PARA_SHAPE              = HWPTAG_BEGIN + 9;  // 0x019
    static final int TAG_STYLE                   = HWPTAG_BEGIN + 10; // 0x01A
    static final int TAG_DOC_DATA                = HWPTAG_BEGIN + 11; // 0x01B
    static final int TAG_DISTRIBUTE_DOC_DATA     = HWPTAG_BEGIN + 12; // 0x01C
    static final int TAG_COMPATIBLE_DOCUMENT     = HWPTAG_BEGIN + 14; // 0x01E
    static final int TAG_LAYOUT_COMPATIBILITY    = HWPTAG_BEGIN + 15; // 0x01F
    static final int TAG_TRACKCHANGE             = HWPTAG_BEGIN + 16; // 0x020
    static final int TAG_MEMO_SHAPE              = HWPTAG_BEGIN + 76; // 0x05C
    static final int TAG_FORBIDDEN_CHAR          = HWPTAG_BEGIN + 78; // 0x05E
    static final int TAG_TRACK_CHANGE            = HWPTAG_BEGIN + 80; // 0x060
    static final int TAG_TRACK_CHANGE_AUTHOR     = HWPTAG_BEGIN + 81; // 0x061

    // Body (Section) 태그
    static final int TAG_PARA_HEADER             = HWPTAG_BEGIN + 50; // 0x042
    static final int TAG_PARA_TEXT               = HWPTAG_BEGIN + 51; // 0x043
    static final int TAG_PARA_CHAR_SHAPE         = HWPTAG_BEGIN + 52; // 0x044
    static final int TAG_PARA_LINE_SEG           = HWPTAG_BEGIN + 53; // 0x045
    static final int TAG_PARA_RANGE_TAG          = HWPTAG_BEGIN + 54; // 0x046
    static final int TAG_CTRL_HEADER             = HWPTAG_BEGIN + 55; // 0x047
    static final int TAG_LIST_HEADER             = HWPTAG_BEGIN + 56; // 0x048
    static final int TAG_PAGE_DEF                = HWPTAG_BEGIN + 57; // 0x049
    static final int TAG_FOOTNOTE_SHAPE          = HWPTAG_BEGIN + 58; // 0x04A
    static final int TAG_PAGE_BORDER_FILL        = HWPTAG_BEGIN + 59; // 0x04B
    static final int TAG_SHAPE_COMPONENT         = HWPTAG_BEGIN + 60; // 0x04C
    static final int TAG_TABLE                   = HWPTAG_BEGIN + 61; // 0x04D
    static final int TAG_SHAPE_COMPONENT_LINE    = HWPTAG_BEGIN + 62; // 0x04E
    static final int TAG_SHAPE_COMPONENT_RECT    = HWPTAG_BEGIN + 63; // 0x04F
    static final int TAG_SHAPE_COMPONENT_ELLIPSE = HWPTAG_BEGIN + 64; // 0x050
    static final int TAG_SHAPE_COMPONENT_ARC     = HWPTAG_BEGIN + 65; // 0x051
    static final int TAG_SHAPE_COMPONENT_POLYGON = HWPTAG_BEGIN + 66; // 0x052
    static final int TAG_SHAPE_COMPONENT_CURVE   = HWPTAG_BEGIN + 67; // 0x053
    static final int TAG_SHAPE_COMPONENT_OLE     = HWPTAG_BEGIN + 68; // 0x054
    static final int TAG_SHAPE_COMPONENT_PICTURE = HWPTAG_BEGIN + 69; // 0x055
    static final int TAG_SHAPE_COMPONENT_CONTAINER = HWPTAG_BEGIN + 70; // 0x056
    static final int TAG_CTRL_DATA               = HWPTAG_BEGIN + 71; // 0x057
    static final int TAG_EQEDIT                  = HWPTAG_BEGIN + 72; // 0x058
    static final int TAG_SHAPE_COMPONENT_TEXTART = HWPTAG_BEGIN + 74; // 0x05A
    static final int TAG_FORM_OBJECT             = HWPTAG_BEGIN + 75; // 0x05B
    static final int TAG_MEMO_LIST               = HWPTAG_BEGIN + 77; // 0x05D
    static final int TAG_CHART_DATA              = HWPTAG_BEGIN + 79; // 0x05F
    static final int TAG_VIDEO_DATA              = HWPTAG_BEGIN + 82; // 0x062
    static final int TAG_SHAPE_COMPONENT_UNKNOWN = HWPTAG_BEGIN + 99; // 0x073

    static final Map<Integer, String> TAG_NAMES = new LinkedHashMap<>();
    static {
        TAG_NAMES.put(TAG_DOCUMENT_PROPERTIES, "DOCUMENT_PROPERTIES");
        TAG_NAMES.put(TAG_ID_MAPPINGS, "ID_MAPPINGS");
        TAG_NAMES.put(TAG_BIN_DATA, "BIN_DATA");
        TAG_NAMES.put(TAG_FACE_NAME, "FACE_NAME");
        TAG_NAMES.put(TAG_BORDER_FILL, "BORDER_FILL");
        TAG_NAMES.put(TAG_CHAR_SHAPE, "CHAR_SHAPE");
        TAG_NAMES.put(TAG_TAB_DEF, "TAB_DEF");
        TAG_NAMES.put(TAG_NUMBERING, "NUMBERING");
        TAG_NAMES.put(TAG_BULLET, "BULLET");
        TAG_NAMES.put(TAG_PARA_SHAPE, "PARA_SHAPE");
        TAG_NAMES.put(TAG_STYLE, "STYLE");
        TAG_NAMES.put(TAG_DOC_DATA, "DOC_DATA");
        TAG_NAMES.put(TAG_DISTRIBUTE_DOC_DATA, "DISTRIBUTE_DOC_DATA");
        TAG_NAMES.put(TAG_COMPATIBLE_DOCUMENT, "COMPATIBLE_DOCUMENT");
        TAG_NAMES.put(TAG_LAYOUT_COMPATIBILITY, "LAYOUT_COMPATIBILITY");
        TAG_NAMES.put(TAG_TRACKCHANGE, "TRACKCHANGE");
        TAG_NAMES.put(TAG_MEMO_SHAPE, "MEMO_SHAPE");
        TAG_NAMES.put(TAG_FORBIDDEN_CHAR, "FORBIDDEN_CHAR");
        TAG_NAMES.put(TAG_TRACK_CHANGE, "TRACK_CHANGE");
        TAG_NAMES.put(TAG_TRACK_CHANGE_AUTHOR, "TRACK_CHANGE_AUTHOR");
        TAG_NAMES.put(TAG_PARA_HEADER, "PARA_HEADER");
        TAG_NAMES.put(TAG_PARA_TEXT, "PARA_TEXT");
        TAG_NAMES.put(TAG_PARA_CHAR_SHAPE, "PARA_CHAR_SHAPE");
        TAG_NAMES.put(TAG_PARA_LINE_SEG, "PARA_LINE_SEG");
        TAG_NAMES.put(TAG_PARA_RANGE_TAG, "PARA_RANGE_TAG");
        TAG_NAMES.put(TAG_CTRL_HEADER, "CTRL_HEADER");
        TAG_NAMES.put(TAG_LIST_HEADER, "LIST_HEADER");
        TAG_NAMES.put(TAG_PAGE_DEF, "PAGE_DEF");
        TAG_NAMES.put(TAG_FOOTNOTE_SHAPE, "FOOTNOTE_SHAPE");
        TAG_NAMES.put(TAG_PAGE_BORDER_FILL, "PAGE_BORDER_FILL");
        TAG_NAMES.put(TAG_SHAPE_COMPONENT, "SHAPE_COMPONENT");
        TAG_NAMES.put(TAG_TABLE, "TABLE");
        TAG_NAMES.put(TAG_SHAPE_COMPONENT_LINE, "SHAPE_COMPONENT_LINE");
        TAG_NAMES.put(TAG_SHAPE_COMPONENT_RECT, "SHAPE_COMPONENT_RECTANGLE");
        TAG_NAMES.put(TAG_SHAPE_COMPONENT_ELLIPSE, "SHAPE_COMPONENT_ELLIPSE");
        TAG_NAMES.put(TAG_SHAPE_COMPONENT_ARC, "SHAPE_COMPONENT_ARC");
        TAG_NAMES.put(TAG_SHAPE_COMPONENT_POLYGON, "SHAPE_COMPONENT_POLYGON");
        TAG_NAMES.put(TAG_SHAPE_COMPONENT_CURVE, "SHAPE_COMPONENT_CURVE");
        TAG_NAMES.put(TAG_SHAPE_COMPONENT_OLE, "SHAPE_COMPONENT_OLE");
        TAG_NAMES.put(TAG_SHAPE_COMPONENT_PICTURE, "SHAPE_COMPONENT_PICTURE");
        TAG_NAMES.put(TAG_SHAPE_COMPONENT_CONTAINER, "SHAPE_COMPONENT_CONTAINER");
        TAG_NAMES.put(TAG_CTRL_DATA, "CTRL_DATA");
        TAG_NAMES.put(TAG_EQEDIT, "EQEDIT");
        TAG_NAMES.put(TAG_SHAPE_COMPONENT_TEXTART, "SHAPE_COMPONENT_TEXTART");
        TAG_NAMES.put(TAG_FORM_OBJECT, "FORM_OBJECT");
        TAG_NAMES.put(TAG_MEMO_LIST, "MEMO_LIST");
        TAG_NAMES.put(TAG_CHART_DATA, "CHART_DATA");
        TAG_NAMES.put(TAG_VIDEO_DATA, "VIDEO_DATA");
        TAG_NAMES.put(TAG_SHAPE_COMPONENT_UNKNOWN, "SHAPE_COMPONENT_UNKNOWN");
    }

    static String tagName(int tag) {
        String name = TAG_NAMES.get(tag);
        return name != null ? name : String.format("UNKNOWN_TAG(0x%03X)", tag);
    }

    // ========================================================================
    // 알려진 Control ID 목록
    // ========================================================================
    static final Set<Long> KNOWN_CTRL_IDS = new HashSet<>();
    static {
        // 객체 컨트롤
        KNOWN_CTRL_IDS.add(make4chid('t','b','l',' ')); // 표
        KNOWN_CTRL_IDS.add(make4chid('$','l','i','n')); // 선
        KNOWN_CTRL_IDS.add(make4chid('$','r','e','c')); // 사각형
        KNOWN_CTRL_IDS.add(make4chid('$','e','l','l')); // 타원
        KNOWN_CTRL_IDS.add(make4chid('$','a','r','c')); // 호
        KNOWN_CTRL_IDS.add(make4chid('$','p','o','l')); // 다각형
        KNOWN_CTRL_IDS.add(make4chid('$','c','u','r')); // 곡선
        KNOWN_CTRL_IDS.add(make4chid('e','q','e','d')); // 수식
        KNOWN_CTRL_IDS.add(make4chid('$','p','i','c')); // 그림
        KNOWN_CTRL_IDS.add(make4chid('$','o','l','e')); // OLE
        KNOWN_CTRL_IDS.add(make4chid('$','c','o','n')); // 컨테이너 (그룹)
        // 비객체 컨트롤
        KNOWN_CTRL_IDS.add(make4chid('s','e','c','d')); // 구역 정의
        KNOWN_CTRL_IDS.add(make4chid('c','o','l','d')); // 단 정의
        KNOWN_CTRL_IDS.add(make4chid('h','e','a','d')); // 머리말
        KNOWN_CTRL_IDS.add(make4chid('f','o','o','t')); // 꼬리말
        KNOWN_CTRL_IDS.add(make4chid('f','n',' ',' ')); // 각주
        KNOWN_CTRL_IDS.add(make4chid('e','n',' ',' ')); // 미주
        KNOWN_CTRL_IDS.add(make4chid('a','t','n','o')); // 자동 번호
        KNOWN_CTRL_IDS.add(make4chid('n','w','n','o')); // 새 번호
        KNOWN_CTRL_IDS.add(make4chid('p','g','h','d')); // 페이지 감추기
        KNOWN_CTRL_IDS.add(make4chid('p','g','c','t')); // 홀짝 페이지 조정
        KNOWN_CTRL_IDS.add(make4chid('p','g','n','p')); // 쪽 번호 위치
        KNOWN_CTRL_IDS.add(make4chid('i','d','x','m')); // 찾아보기 표식
        KNOWN_CTRL_IDS.add(make4chid('b','o','k','m')); // 책갈피
        KNOWN_CTRL_IDS.add(make4chid('t','c','p','s')); // 글자 겹침
        KNOWN_CTRL_IDS.add(make4chid('t','d','u','t')); // 덧말
        KNOWN_CTRL_IDS.add(make4chid('t','c','m','t')); // 숨은 설명
        // 필드 컨트롤
        KNOWN_CTRL_IDS.add(make4chid('%','u','n','k')); // FIELD_UNKNOWN
        KNOWN_CTRL_IDS.add(make4chid('%','d','t','e')); // FIELD_DATE
        KNOWN_CTRL_IDS.add(make4chid('%','d','d','t')); // FIELD_DOCDATE
        KNOWN_CTRL_IDS.add(make4chid('%','p','a','t')); // FIELD_PATH
        KNOWN_CTRL_IDS.add(make4chid('%','b','m','k')); // FIELD_BOOKMARK
        KNOWN_CTRL_IDS.add(make4chid('%','m','m','g')); // FIELD_MAILMERGE
        KNOWN_CTRL_IDS.add(make4chid('%','x','r','f')); // FIELD_CROSSREF
        KNOWN_CTRL_IDS.add(make4chid('%','f','m','u')); // FIELD_FORMULA
        KNOWN_CTRL_IDS.add(make4chid('%','c','l','k')); // FIELD_CLICKHERE
        KNOWN_CTRL_IDS.add(make4chid('%','s','m','r')); // FIELD_SUMMARY
        KNOWN_CTRL_IDS.add(make4chid('%','u','s','r')); // FIELD_USERINFO
        KNOWN_CTRL_IDS.add(make4chid('%','h','l','k')); // FIELD_HYPERLINK
        KNOWN_CTRL_IDS.add(make4chid('%','s','i','g')); // FIELD_REVISION_SIGN
        KNOWN_CTRL_IDS.add(make4chid('%','%','*','d')); // FIELD_REVISION_DELETE
        KNOWN_CTRL_IDS.add(make4chid('%','%','*','a')); // FIELD_REVISION_ATTACH
        KNOWN_CTRL_IDS.add(make4chid('%','%','*','C')); // FIELD_REVISION_CLIPPING
        KNOWN_CTRL_IDS.add(make4chid('%','%','*','S')); // FIELD_REVISION_SAWTOOTH
        KNOWN_CTRL_IDS.add(make4chid('%','%','*','T')); // FIELD_REVISION_THINKING
        KNOWN_CTRL_IDS.add(make4chid('%','%','*','P')); // FIELD_REVISION_PRAISE
        KNOWN_CTRL_IDS.add(make4chid('%','%','*','L')); // FIELD_REVISION_LINE
        KNOWN_CTRL_IDS.add(make4chid('%','%','*','c')); // FIELD_REVISION_SIMPLECHANGE
        KNOWN_CTRL_IDS.add(make4chid('%','%','*','h')); // FIELD_REVISION_HYPERLINK
        KNOWN_CTRL_IDS.add(make4chid('%','%','*','A')); // FIELD_REVISION_LINEATTACH
        KNOWN_CTRL_IDS.add(make4chid('%','%','*','i')); // FIELD_REVISION_LINELINK
        KNOWN_CTRL_IDS.add(make4chid('%','%','*','t')); // FIELD_REVISION_LINETRANSFER
        KNOWN_CTRL_IDS.add(make4chid('%','%','*','r')); // FIELD_REVISION_RIGHTMOVE
        KNOWN_CTRL_IDS.add(make4chid('%','%','*','l')); // FIELD_REVISION_LEFTMOVE
        KNOWN_CTRL_IDS.add(make4chid('%','%','*','n')); // FIELD_REVISION_TRANSFER
        KNOWN_CTRL_IDS.add(make4chid('%','%','*','e')); // FIELD_REVISION_SIMPLEINSERT
        KNOWN_CTRL_IDS.add(make4chid('%','s','p','l')); // FIELD_REVISION_SPLIT
        KNOWN_CTRL_IDS.add(make4chid('%','%','m','r')); // FIELD_REVISION_CHANGE
        KNOWN_CTRL_IDS.add(make4chid('%','%','m','e')); // FIELD_MEMO
        KNOWN_CTRL_IDS.add(make4chid('%','c','p','r')); // FIELD_PRIVATE_INFO_SECURITY
        KNOWN_CTRL_IDS.add(make4chid('%','t','o','c')); // FIELD_TABLEOFCONTENTS
        // GSO (generic shape object) -- 내부 사용
        KNOWN_CTRL_IDS.add(make4chid('g','s','o',' ')); // gso
    }

    // 확장 컨트롤 문자 코드 (8 WCHAR = 16 바이트)
    static final Set<Integer> EXTENDED_CTRL_CHARS = new HashSet<>(
        Arrays.asList(1, 2, 3, 11, 12, 14, 15, 16, 17, 21, 22, 23)
    );
    // Char 타입 컨트롤 문자 (1 WCHAR = 2 바이트)
    static final Set<Integer> CHAR_CTRL_CHARS = new HashSet<>(
        Arrays.asList(10, 13, 24, 25, 26, 27, 28, 29, 30, 31)
    );
    // Inline 컨트롤 문자 (8 WCHAR = 16 바이트)
    static final Set<Integer> INLINE_CTRL_CHARS = new HashSet<>(
        Arrays.asList(4, 5, 6, 7, 8, 9, 18, 19, 20)
    );

    // ========================================================================
    // Record 구조
    // ========================================================================
    static class Rec {
        int index;       // 스트림 내 record 인덱스
        int tag;
        int level;
        int size;
        byte[] data;
        int streamOffset; // 압축 해제된 스트림 내의 바이트 오프셋

        Rec(int index, int tag, int level, int size, byte[] data, int streamOffset) {
            this.index = index;
            this.tag = tag;
            this.level = level;
            this.size = size;
            this.data = data;
            this.streamOffset = streamOffset;
        }
    }

    static List<Rec> parseRecords(byte[] raw) {
        List<Rec> recs = new ArrayList<>();
        int pos = 0;
        int idx = 0;
        while (pos + 4 <= raw.length) {
            int recStart = pos;
            long h = u32(raw, pos); pos += 4;
            int tag = (int)(h & 0x3FF);
            int level = (int)((h >> 10) & 0x3FF);
            int size = (int)((h >> 20) & 0xFFF);
            if (size == 0xFFF) {
                if (pos + 4 > raw.length) {
                    error("Extended size DWORD missing at offset " + pos);
                    break;
                }
                size = i32(raw, pos); pos += 4;
            }
            if (pos + size > raw.length) {
                error(String.format("Record #%d at offset %d extends beyond data (tag=0x%03X size=%d remaining=%d)",
                    idx, recStart, tag, size, raw.length - pos));
                break;
            }
            byte[] d = Arrays.copyOfRange(raw, pos, pos + size);
            recs.add(new Rec(idx, tag, level, size, d, recStart));
            pos += size;
            idx++;
        }
        return recs;
    }

    // ========================================================================
    // OLE2 / 압축 해제 헬퍼
    // ========================================================================
    static byte[] readStream(POIFSFileSystem poi, String... path) throws IOException {
        DirectoryEntry dir = poi.getRoot();
        for (int i = 0; i < path.length - 1; i++) {
            dir = (DirectoryEntry) dir.getEntry(path[i]);
        }
        DocumentEntry doc = (DocumentEntry) dir.getEntry(path[path.length - 1]);
        byte[] data = new byte[doc.getSize()];
        try (InputStream is = new DocumentInputStream(doc)) {
            int total = 0;
            while (total < data.length) {
                int n = is.read(data, total, data.length - total);
                if (n < 0) break;
                total += n;
            }
        }
        return data;
    }

    static boolean hasEntry(DirectoryEntry dir, String name) {
        try { dir.getEntry(name); return true; }
        catch (Exception e) { return false; }
    }

    static boolean hasStorage(DirectoryEntry dir, String name) {
        try {
            return dir.getEntry(name) instanceof DirectoryEntry;
        } catch (Exception e) { return false; }
    }

    static byte[] decompress(byte[] raw) {
        // nowrap=true (raw deflate) 먼저 시도한 뒤 nowrap=false (zlib 헤더) 시도
        byte[] result = tryInflate(raw, true);
        if (result != null) return result;
        result = tryInflate(raw, false);
        if (result != null) return result;
        error("zlib decompression failed for both raw deflate and zlib modes");
        return raw;
    }

    static byte[] tryInflate(byte[] raw, boolean nowrap) {
        try {
            Inflater inf = new Inflater(nowrap);
            inf.setInput(raw);
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length * 4);
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && inf.needsInput()) break;
                out.write(buf, 0, n);
            }
            inf.end();
            byte[] result = out.toByteArray();
            if (result.length == 0) return null;
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    // ========================================================================
    // 비교를 위한 파싱 결과 데이터
    // ========================================================================
    static class ParsedField {
        String path;     // 예: "FileHeader.signature" 또는 "DocInfo.rec#3.CHAR_SHAPE.baseSize"
        String value;

        ParsedField(String path, String value) {
            this.path = path;
            this.value = value;
        }
    }

    static class ParsedFile {
        List<ParsedField> fields = new ArrayList<>();
        Map<String, String> fieldMap = new LinkedHashMap<>();

        void add(String path, String value) {
            fields.add(new ParsedField(path, value));
            fieldMap.put(path, value);
        }
    }

    static ParsedFile currentParsed = null;
    static String currentPrefix = "";

    static void addParsed(String name, String value) {
        if (currentParsed != null) {
            currentParsed.add(currentPrefix + "." + name, value);
        }
    }

    static void addParsed(String name, long value) {
        addParsed(name, String.valueOf(value));
    }

    // ========================================================================
    // FileHeader 파싱 (256 바이트, 명세 3.2.1)
    // ========================================================================
    static void dumpFileHeader(byte[] hdr) {
        System.out.println("\n========== FileHeader (256 bytes, spec 3.2.1) ==========");

        if (hdr.length != 256) {
            error("FileHeader size is " + hdr.length + ", expected 256");
        } else {
            pass("FileHeader size = 256");
        }

        // Signature (32 바이트)
        String sig = new String(hdr, 0, Math.min(32, hdr.length), StandardCharsets.US_ASCII).trim();
        // 인쇄 불가 문자 치환
        StringBuilder sigClean = new StringBuilder();
        for (char c : sig.toCharArray()) {
            if (c >= 0x20 && c < 0x7F) sigClean.append(c);
            else sigClean.append('.');
        }
        field("Signature[32]", "\"" + sigClean + "\"");
        addParsed("Signature", sigClean.toString());
        if (!sigClean.toString().startsWith("HWP Document File")) {
            error("Signature does not start with 'HWP Document File'");
        } else {
            pass("Signature valid");
        }

        // Version (offset 32의 DWORD)
        if (hdr.length >= 36) {
            long ver = u32(hdr, 32);
            int mm = (int)((ver >> 24) & 0xFF);
            int nn = (int)((ver >> 16) & 0xFF);
            int pp = (int)((ver >> 8) & 0xFF);
            int rr = (int)(ver & 0xFF);
            field("Version", String.format("%d.%d.%d.%d (raw=0x%08X)", mm, nn, pp, rr, ver));
            addParsed("Version", String.format("%d.%d.%d.%d", mm, nn, pp, rr));
            if (mm != 5) {
                error("Major version is " + mm + ", expected 5 for HWP 5.0");
            } else {
                pass("Major version = 5");
            }
        }

        // Properties (offset 36의 DWORD)
        if (hdr.length >= 40) {
            long props = u32(hdr, 36);
            fieldHex("Properties", props);
            addParsed("Properties", String.format("0x%08X", props));
            boolean compressed = (props & 0x01) != 0;
            boolean encrypted = (props & 0x02) != 0;
            boolean distDoc = (props & 0x04) != 0;
            boolean scriptSaved = (props & 0x08) != 0;
            boolean drm = (props & 0x10) != 0;
            boolean xmlTemplate = (props & 0x20) != 0;
            boolean docHistory = (props & 0x40) != 0;
            boolean digiSign = (props & 0x80) != 0;
            boolean certEncrypt = (props & 0x100) != 0;
            boolean signReserve = (props & 0x200) != 0;
            boolean certDrm = (props & 0x400) != 0;
            boolean ccl = (props & 0x800) != 0;
            boolean mobileOpt = (props & 0x1000) != 0;
            boolean privacyDoc = (props & 0x2000) != 0;
            boolean trackChange = (props & 0x4000) != 0;
            boolean kogl = (props & 0x8000) != 0;
            boolean hasVideo = (props & 0x10000) != 0;
            boolean hasTOCField = (props & 0x20000) != 0;

            field("  Compressed", String.valueOf(compressed));
            field("  Encrypted", String.valueOf(encrypted));
            field("  DistributionDoc", String.valueOf(distDoc));
            field("  ScriptSaved", String.valueOf(scriptSaved));
            field("  DRM", String.valueOf(drm));
            field("  XMLTemplate", String.valueOf(xmlTemplate));
            field("  DocHistory", String.valueOf(docHistory));
            field("  DigitalSignature", String.valueOf(digiSign));
            field("  CertEncrypt", String.valueOf(certEncrypt));
            field("  SignReserve", String.valueOf(signReserve));
            field("  CertDRM", String.valueOf(certDrm));
            field("  CCL", String.valueOf(ccl));
            field("  MobileOptimized", String.valueOf(mobileOpt));
            field("  PrivacyDoc", String.valueOf(privacyDoc));
            field("  TrackChange", String.valueOf(trackChange));
            field("  KOGL", String.valueOf(kogl));
            field("  HasVideo", String.valueOf(hasVideo));
            field("  HasTOCField", String.valueOf(hasTOCField));

            addParsed("Compressed", String.valueOf(compressed));
            addParsed("Encrypted", String.valueOf(encrypted));

            if (encrypted) {
                warn("File is encrypted -- cannot parse DocInfo/BodyText contents");
            }
        }

        // License (offset 40의 DWORD)
        if (hdr.length >= 44) {
            long license = u32(hdr, 40);
            fieldHex("License", license);
            addParsed("License", String.format("0x%08X", license));
            field("  CCL/KOGL info", String.valueOf(license & 0x01));
            field("  CopyRestrict", String.valueOf((license >> 1) & 0x01));
            field("  SameConditionCopy", String.valueOf((license >> 2) & 0x01));
        }

        // EncryptVersion (offset 44의 DWORD)
        if (hdr.length >= 48) {
            long encVer = u32(hdr, 44);
            String encDesc;
            switch ((int) encVer) {
                case 0: encDesc = "None"; break;
                case 1: encDesc = "HWP 2.5 or lower"; break;
                case 2: encDesc = "HWP 3.0 Enhanced"; break;
                case 3: encDesc = "HWP 3.0 Old"; break;
                case 4: encDesc = "HWP 7.0 or later"; break;
                default: encDesc = "Unknown(" + encVer + ")"; break;
            }
            field("EncryptVersion", encDesc + " (raw=" + encVer + ")");
            addParsed("EncryptVersion", String.valueOf(encVer));
        }

        // KOGLCountry (offset 48의 BYTE)
        if (hdr.length >= 49) {
            int kogl = u8(hdr, 48);
            String koglDesc;
            switch (kogl) {
                case 6: koglDesc = "KOR"; break;
                case 15: koglDesc = "US"; break;
                default: koglDesc = "Unknown(" + kogl + ")"; break;
            }
            field("KOGLCountry", koglDesc + " (raw=" + kogl + ")");
            addParsed("KOGLCountry", String.valueOf(kogl));
        }

        // 예약 바이트 (49..255)
        field("Reserved[207]", hexDump(hdr, 49, Math.min(32, hdr.length - 49)) + "...");
    }

    // ========================================================================
    // DocInfo record 파싱
    // ========================================================================
    static int lastParaNChars = 0; // PARA_TEXT 검증을 위한 추적용

    static void dumpDocInfoRecords(List<Rec> recs) {
        System.out.println("\n========== DocInfo Records ==========");
        System.out.println("  Total records: " + recs.size());

        // 순서 검증: 첫 record는 반드시 DOCUMENT_PROPERTIES 이어야 함
        if (!recs.isEmpty()) {
            if (recs.get(0).tag != TAG_DOCUMENT_PROPERTIES) {
                error("First DocInfo record is " + tagName(recs.get(0).tag) +
                      ", expected DOCUMENT_PROPERTIES (0x010)");
            } else {
                pass("First DocInfo record is DOCUMENT_PROPERTIES");
            }
        }

        // level 계층 구조 검증
        validateLevelHierarchy(recs, "DocInfo");

        for (int i = 0; i < recs.size(); i++) {
            Rec r = recs.get(i);
            currentPrefix = "DocInfo.rec#" + i + "." + tagName(r.tag);
            System.out.printf("\n  --- Record #%d: %s (tag=0x%03X, level=%d, size=%d, offset=%d) ---%n",
                i, tagName(r.tag), r.tag, r.level, r.size, r.streamOffset);

            switch (r.tag) {
                case TAG_DOCUMENT_PROPERTIES: dumpDocumentProperties(r); break;
                case TAG_ID_MAPPINGS:         dumpIdMappings(r); break;
                case TAG_BIN_DATA:            dumpBinData(r); break;
                case TAG_FACE_NAME:           dumpFaceName(r); break;
                case TAG_BORDER_FILL:         dumpBorderFill(r); break;
                case TAG_CHAR_SHAPE:          dumpCharShape(r); break;
                case TAG_TAB_DEF:             dumpTabDef(r); break;
                case TAG_NUMBERING:           dumpNumbering(r); break;
                case TAG_BULLET:              dumpBullet(r); break;
                case TAG_PARA_SHAPE:          dumpParaShape(r); break;
                case TAG_STYLE:               dumpStyle(r); break;
                case TAG_DOC_DATA:            dumpDocData(r); break;
                case TAG_COMPATIBLE_DOCUMENT: dumpCompatibleDocument(r); break;
                case TAG_LAYOUT_COMPATIBILITY:dumpLayoutCompatibility(r); break;
                case TAG_DISTRIBUTE_DOC_DATA: dumpDistributeDocData(r); break;
                case TAG_FORBIDDEN_CHAR:      dumpGenericRecord(r, "ForbiddenChar"); break;
                case TAG_MEMO_SHAPE:          dumpGenericRecord(r, "MemoShape"); break;
                case TAG_TRACK_CHANGE:        dumpGenericRecord(r, "TrackChange"); break;
                case TAG_TRACK_CHANGE_AUTHOR: dumpGenericRecord(r, "TrackChangeAuthor"); break;
                case TAG_TRACKCHANGE:         dumpGenericRecord(r, "Trackchange"); break;
                default:
                    info("Unhandled DocInfo tag: " + tagName(r.tag) + " (size=" + r.size + ")");
                    field("Raw data (first 64 bytes)", hexDump(r.data, 0, Math.min(64, r.size)));
                    break;
            }
        }
    }

    static void dumpDocumentProperties(Rec r) {
        byte[] d = r.data;
        if (d.length < 26) {
            error("DOCUMENT_PROPERTIES size=" + d.length + ", expected >= 26");
            return;
        }
        // 명세는 26 바이트라 되어 있지만 일부 버전은 30 바이트
        if (d.length == 26) {
            pass("DOCUMENT_PROPERTIES size=26 (matches spec)");
        } else if (d.length == 30) {
            pass("DOCUMENT_PROPERTIES size=30 (includes extra 4 bytes, common in newer versions)");
        } else {
            warn("DOCUMENT_PROPERTIES size=" + d.length + ", spec says 26 or 30");
        }

        int sectionCount = u16(d, 0);
        int pageStart    = u16(d, 2);
        int footnoteStart= u16(d, 4);
        int endnoteStart = u16(d, 6);
        int pictureStart = u16(d, 8);
        int tableStart   = u16(d, 10);
        int equationStart= u16(d, 12);
        long caretListId = u32(d, 14);
        long caretParaId = u32(d, 18);
        long caretPos    = u32(d, 22);

        field("SectionCount", String.valueOf(sectionCount));
        field("PageStartNumber", String.valueOf(pageStart));
        field("FootnoteStartNumber", String.valueOf(footnoteStart));
        field("EndnoteStartNumber", String.valueOf(endnoteStart));
        field("PictureStartNumber", String.valueOf(pictureStart));
        field("TableStartNumber", String.valueOf(tableStart));
        field("EquationStartNumber", String.valueOf(equationStart));
        fieldHex("CaretListId", caretListId);
        fieldHex("CaretParaId", caretParaId);
        fieldHex("CaretPos", caretPos);

        addParsed("SectionCount", sectionCount);
        addParsed("PageStartNumber", pageStart);
        addParsed("FootnoteStartNumber", footnoteStart);
        addParsed("EndnoteStartNumber", endnoteStart);
        addParsed("PictureStartNumber", pictureStart);
        addParsed("TableStartNumber", tableStart);
        addParsed("EquationStartNumber", equationStart);
        addParsed("CaretListId", caretListId);
        addParsed("CaretParaId", caretParaId);
        addParsed("CaretPos", caretPos);

        if (sectionCount == 0) {
            error("SectionCount is 0 -- document has no sections");
        }
    }

    static void dumpIdMappings(Rec r) {
        byte[] d = r.data;
        // 명세는 72 바이트 (18 x INT32)이지만 버전에 따라 다름
        String[] names = {
            "BinData", "HangulFont", "EnglishFont", "HanjaFont",
            "JapaneseFont", "OtherFont", "SymbolFont", "UserFont",
            "BorderFill", "CharShape", "TabDef", "Numbering",
            "Bullet", "ParaShape", "Style", "MemoShape",
            "TrackChange", "TrackChangeAuthor"
        };

        int count = d.length / 4;
        if (d.length < 72) {
            warn("ID_MAPPINGS size=" + d.length + ", expected >= 72 (may be older version)");
        } else if (d.length == 72) {
            pass("ID_MAPPINGS size=72 (18 x INT32, matches spec)");
        } else {
            info("ID_MAPPINGS size=" + d.length + " (extended version with " + count + " entries)");
        }

        for (int i = 0; i < count && i < names.length; i++) {
            int val = i32(d, i * 4);
            field("Count[" + i + "] " + names[i], String.valueOf(val));
            addParsed("Count_" + names[i], val);
        }
        // 18개를 초과하는 추가 엔트리
        for (int i = names.length; i < count; i++) {
            int val = i32(d, i * 4);
            field("Count[" + i + "] Unknown", String.valueOf(val));
        }
    }

    static void dumpBinData(Rec r) {
        byte[] d = r.data;
        if (d.length < 2) { error("BIN_DATA too small: " + d.length); return; }

        int prop = u16(d, 0);
        int type = prop & 0x0F;
        int compress = (prop >> 4) & 0x03;
        int state = (prop >> 8) & 0x03;

        String typeStr;
        switch (type) {
            case 0: typeStr = "LINK"; break;
            case 1: typeStr = "EMBEDDING"; break;
            case 2: typeStr = "STORAGE"; break;
            default: typeStr = "Unknown(" + type + ")"; break;
        }
        String compStr;
        switch (compress) {
            case 0: compStr = "default"; break;
            case 1: compStr = "forced_compress"; break;
            case 2: compStr = "forced_no_compress"; break;
            default: compStr = "unknown(" + compress + ")"; break;
        }
        String stateStr;
        switch (state) {
            case 0: stateStr = "never_accessed"; break;
            case 1: stateStr = "access_success"; break;
            case 2: stateStr = "access_error"; break;
            case 3: stateStr = "link_error_ignored"; break;
            default: stateStr = "unknown(" + state + ")"; break;
        }

        fieldHex16("Property", prop);
        field("  Type", typeStr);
        field("  Compression", compStr);
        field("  State", stateStr);
        addParsed("Type", typeStr);

        int pos = 2;
        if (type == 1 || type == 2) {
            // EMBEDDING 또는 STORAGE: binDataId
            if (pos + 2 <= d.length) {
                int binId = u16(d, pos); pos += 2;
                field("BinDataId", String.valueOf(binId));
                addParsed("BinDataId", binId);
            }
            if (type == 1 && pos + 2 <= d.length) {
                // 확장자
                int extLen = u16(d, pos); pos += 2;
                if (extLen > 0 && pos + extLen * 2 <= d.length) {
                    String ext = readWchar(d, pos, extLen);
                    pos += extLen * 2;
                    field("Extension", ext);
                    addParsed("Extension", ext);
                }
            }
        } else if (type == 0) {
            // LINK: 절대 경로, 상대 경로
            if (pos + 2 <= d.length) {
                int absLen = u16(d, pos); pos += 2;
                if (absLen > 0 && pos + absLen * 2 <= d.length) {
                    String absPath = readWchar(d, pos, absLen);
                    pos += absLen * 2;
                    field("AbsolutePath", absPath);
                }
            }
            if (pos + 2 <= d.length) {
                int relLen = u16(d, pos); pos += 2;
                if (relLen > 0 && pos + relLen * 2 <= d.length) {
                    String relPath = readWchar(d, pos, relLen);
                    pos += relLen * 2;
                    field("RelativePath", relPath);
                }
            }
        }
    }

    static void dumpFaceName(Rec r) {
        byte[] d = r.data;
        if (d.length < 3) { error("FACE_NAME too small: " + d.length); return; }

        int pos = 0;
        int prop = u8(d, pos); pos++;
        boolean hasAlt     = (prop & 0x80) != 0;
        boolean hasType    = (prop & 0x40) != 0;
        boolean hasDefault = (prop & 0x20) != 0;

        field("Property", String.format("0x%02X (hasAlt=%b, hasType=%b, hasDefault=%b)",
            prop, hasAlt, hasType, hasDefault));
        addParsed("FaceNameProp", prop);

        if (pos + 2 > d.length) return;
        int nameLen = u16(d, pos); pos += 2;
        String name = "";
        if (nameLen > 0 && pos + nameLen * 2 <= d.length) {
            name = readWchar(d, pos, nameLen);
            pos += nameLen * 2;
        }
        field("FontName", "\"" + name + "\" (len=" + nameLen + ")");
        addParsed("FontName", name);

        if (hasAlt && pos + 3 <= d.length) {
            int altType = u8(d, pos); pos++;
            String altTypeStr;
            switch (altType) {
                case 0: altTypeStr = "Unknown"; break;
                case 1: altTypeStr = "TrueType(TTF)"; break;
                case 2: altTypeStr = "HWP_Specific(HFT)"; break;
                default: altTypeStr = "Unknown(" + altType + ")"; break;
            }
            field("AltFontType", altTypeStr);

            if (pos + 2 <= d.length) {
                int altLen = u16(d, pos); pos += 2;
                if (altLen > 0 && pos + altLen * 2 <= d.length) {
                    String altName = readWchar(d, pos, altLen);
                    pos += altLen * 2;
                    field("AltFontName", "\"" + altName + "\"");
                }
            }
        }

        if (hasType && pos + 10 <= d.length) {
            field("TypeInfo", hexDump(d, pos, 10) + " (family/serif/weight/proportion/contrast/" +
                  "strokeDev/armType/letterform/midline/xHeight)");
            pos += 10;
        }

        if (hasDefault && pos + 2 <= d.length) {
            int defLen = u16(d, pos); pos += 2;
            if (defLen > 0 && pos + defLen * 2 <= d.length) {
                String defName = readWchar(d, pos, defLen);
                pos += defLen * 2;
                field("DefaultFontName", "\"" + defName + "\"");
            }
        }
    }

    static void dumpBorderFill(Rec r) {
        byte[] d = r.data;
        if (d.length < 32) {
            warn("BORDER_FILL size=" + d.length + ", expected >= 32");
        }

        int pos = 0;
        int prop = (pos + 2 <= d.length) ? u16(d, pos) : 0; pos += 2;
        fieldHex16("Property", prop);
        field("  3D_effect", String.valueOf(prop & 1));
        field("  Shadow_effect", String.valueOf((prop >> 1) & 1));
        addParsed("BorderFillProp", prop);

        String[] sides = {"Left", "Right", "Top", "Bottom"};
        // 테두리 타입
        for (int i = 0; i < 4 && pos < d.length; i++) {
            int bt = u8(d, pos); pos++;
            field("BorderType_" + sides[i], borderTypeStr(bt) + " (" + bt + ")");
        }
        // 테두리 두께
        for (int i = 0; i < 4 && pos < d.length; i++) {
            int bw = u8(d, pos); pos++;
            field("BorderWidth_" + sides[i], borderWidthStr(bw) + " (" + bw + ")");
        }
        // 테두리 색상
        for (int i = 0; i < 4 && pos + 4 <= d.length; i++) {
            long bc = u32(d, pos); pos += 4;
            field("BorderColor_" + sides[i], colorStr(bc));
        }

        // 대각선 타입, 두께, 색상
        if (pos + 6 <= d.length) {
            int diagType = u8(d, pos); pos++;
            int diagWidth = u8(d, pos); pos++;
            long diagColor = u32(d, pos); pos += 4;
            field("DiagonalType", String.valueOf(diagType));
            field("DiagonalWidth", borderWidthStr(diagWidth) + " (" + diagWidth + ")");
            field("DiagonalColor", colorStr(diagColor));
        }

        // 채우기 정보
        if (pos + 4 <= d.length) {
            long fillType = u32(d, pos); pos += 4;
            fieldHex("FillType", fillType);
            addParsed("FillType", fillType);

            if ((fillType & 0x01) != 0 && pos + 12 <= d.length) {
                // 단색 채우기
                long bgColor = u32(d, pos); pos += 4;
                long patColor = u32(d, pos); pos += 4;
                int patType = i32(d, pos); pos += 4;
                field("SolidFill.BgColor", colorStr(bgColor));
                field("SolidFill.PatternColor", colorStr(patColor));
                field("SolidFill.PatternType", String.valueOf(patType));
            }
            if ((fillType & 0x04) != 0 && pos + 12 <= d.length) {
                // 그라데이션 채우기
                int gradType = i16(d, pos); pos += 2;
                int gradAngle = i16(d, pos); pos += 2;
                int gradCX = i16(d, pos); pos += 2;
                int gradCY = i16(d, pos); pos += 2;
                int gradBlur = i16(d, pos); pos += 2;
                int numColors = i16(d, pos); pos += 2;
                field("GradientFill.Type", String.valueOf(gradType));
                field("GradientFill.Angle", String.valueOf(gradAngle));
                field("GradientFill.CenterX", String.valueOf(gradCX));
                field("GradientFill.CenterY", String.valueOf(gradCY));
                field("GradientFill.Blur", String.valueOf(gradBlur));
                field("GradientFill.NumColors", String.valueOf(numColors));
                // position과 color 배열 스킵
                if (numColors > 2) pos += 4 * numColors; // 위치 배열
                pos += 4 * numColors; // 색상 배열
            }
            if ((fillType & 0x02) != 0 && pos + 6 <= d.length) {
                // 이미지 채우기
                int imgType = u8(d, pos); pos++;
                field("ImageFill.Type", String.valueOf(imgType));
                // 그림 정보 (5 바이트): 밝기, 명암, 효과, binItemId
                if (pos + 5 <= d.length) {
                    field("ImageFill.PicInfo", hexDump(d, pos, 5));
                    pos += 5;
                }
            }
        }

        if (pos < d.length) {
            field("Remaining bytes", hexDump(d, pos, Math.min(32, d.length - pos)));
        }
    }

    static String borderTypeStr(int v) {
        String[] types = {"Solid","LongDash","Dash","DashDot","DashDotDot",
            "LongDashLine","LargeCircle","Double","ThinThick","ThickThin",
            "ThinThickThin","Wave","DoubleWave","Thick3D","Thick3DInv","3DSingle","3DSingleInv"};
        return (v >= 0 && v < types.length) ? types[v] : "Unknown(" + v + ")";
    }

    static String borderWidthStr(int v) {
        String[] widths = {"0.1mm","0.12mm","0.15mm","0.2mm","0.25mm","0.3mm",
            "0.4mm","0.5mm","0.6mm","0.7mm","1.0mm","1.5mm","2.0mm","3.0mm","4.0mm","5.0mm"};
        return (v >= 0 && v < widths.length) ? widths[v] : "Unknown(" + v + ")";
    }

    static void dumpCharShape(Rec r) {
        byte[] d = r.data;
        // 명세는 72 바이트; strikeColor 포함 시 76 바이트; 확장 가능
        if (d.length < 72) {
            warn("CHAR_SHAPE size=" + d.length + ", expected >= 72");
        } else if (d.length >= 72 && d.length <= 76) {
            pass("CHAR_SHAPE size=" + d.length + " (within spec range 72-76)");
        }

        String[] langs = {"Hangul","English","Hanja","Japanese","Other","Symbol","User"};

        // FontIds: offset 0의 WORD[7]
        for (int i = 0; i < 7 && i * 2 + 1 < d.length; i++) {
            int fid = u16(d, i * 2);
            field("FontId_" + langs[i], String.valueOf(fid));
            addParsed("FontId_" + langs[i], fid);
        }

        // Ratios: offset 14의 UINT8[7]
        for (int i = 0; i < 7 && 14 + i < d.length; i++) {
            int ratio = u8(d, 14 + i);
            field("Ratio_" + langs[i], ratio + "%");
        }

        // Spacings: offset 21의 INT8[7]
        for (int i = 0; i < 7 && 21 + i < d.length; i++) {
            int sp = (byte) d[21 + i];
            field("Spacing_" + langs[i], sp + "%");
        }

        // RelativeSizes: offset 28의 UINT8[7]
        for (int i = 0; i < 7 && 28 + i < d.length; i++) {
            int rs = u8(d, 28 + i);
            field("RelSize_" + langs[i], rs + "%");
        }

        // Offsets: offset 35의 INT8[7]
        for (int i = 0; i < 7 && 35 + i < d.length; i++) {
            int off = (byte) d[35 + i];
            field("Offset_" + langs[i], off + "%");
        }

        // BaseSize: offset 42의 INT32
        if (d.length >= 46) {
            int baseSize = i32(d, 42);
            field("BaseSize", baseSize + " (" + String.format("%.1fpt", baseSize / 100.0) + ")");
            addParsed("BaseSize", baseSize);
        }

        // Property: offset 46의 UINT32
        if (d.length >= 50) {
            long prop = u32(d, 46);
            fieldHex("Property", prop);
            field("  Italic", String.valueOf(prop & 1));
            field("  Bold", String.valueOf((prop >> 1) & 1));
            field("  Underline_type", String.valueOf((prop >> 2) & 3));
            field("  Underline_shape", String.valueOf((prop >> 4) & 0xF));
            field("  Outline_type", String.valueOf((prop >> 8) & 7));
            field("  Shadow_type", String.valueOf((prop >> 11) & 3));
            field("  Emboss", String.valueOf((prop >> 13) & 1));
            field("  Engrave", String.valueOf((prop >> 14) & 1));
            field("  Superscript", String.valueOf((prop >> 15) & 1));
            field("  Subscript", String.valueOf((prop >> 16) & 1));
            field("  Strikethrough", String.valueOf((prop >> 18) & 7));
            field("  EmphasisMark", String.valueOf((prop >> 21) & 0xF));
            field("  UseFontSpace", String.valueOf((prop >> 25) & 1));
            field("  StrikethroughShape", String.valueOf((prop >> 26) & 0xF));
            field("  Kerning", String.valueOf((prop >> 30) & 1));
            addParsed("CharShapeProp", prop);
        }

        // Shadow offsets: 50, 51의 INT8
        if (d.length >= 52) {
            int shadowX = (byte) d[50];
            int shadowY = (byte) d[51];
            field("ShadowOffsetX", shadowX + "%");
            field("ShadowOffsetY", shadowY + "%");
        }

        // Colors: 52, 56, 60, 64의 COLORREF x4
        String[] colorNames = {"TextColor", "UnderlineColor", "ShadeColor", "ShadowColor"};
        for (int i = 0; i < 4 && 52 + (i + 1) * 4 <= d.length; i++) {
            long c = u32(d, 52 + i * 4);
            field(colorNames[i], colorStr(c));
            addParsed(colorNames[i], String.format("0x%08X", c));
        }

        // BorderFillId: 68의 UINT16
        if (d.length >= 70) {
            int bfid = u16(d, 68);
            field("BorderFillId", String.valueOf(bfid));
            addParsed("BorderFillId", bfid);
        }

        // StrikeColor: 70의 COLORREF (5.0.3.0+)
        if (d.length >= 74) {
            long sc = u32(d, 70);
            field("StrikeColor", colorStr(sc));
            addParsed("StrikeColor", String.format("0x%08X", sc));
        }
    }

    static void dumpTabDef(Rec r) {
        byte[] d = r.data;
        if (d.length < 8) {
            warn("TAB_DEF size=" + d.length + ", expected >= 8");
            return;
        }

        long prop = u32(d, 0);
        fieldHex("Property", prop);
        field("  AutoTabAtParaLeft", String.valueOf(prop & 1));
        field("  AutoTabAtParaRight", String.valueOf((prop >> 1) & 1));

        // 명세에는 offset 4의 INT16이라 되어 있으나 일부 버전은 INT32 사용
        int count = i16(d, 4);
        // 명세상 total = 8 + (8 * count)이지만 count 필드는 offset 4에 INT16으로 존재
        // 사실 명세에는 UINT32(4) + INT16(4)로 되어 있으나 offset 4가 이상해서 재확인 필요
        // 명세: UINT32 property (4 바이트) + INT16 count (4 바이트??)
        // 실제로 명세 표에는 "INT16 4 count"로 기재되어 4 바이트로 표시되지만 혼동 가능
        // offset 4에서 count를 i32로 읽어 시도
        int countAlt = i32(d, 4);
        // i16이 합리적인 count 값을 주면 i16을 사용
        int expectedSize = 8 + count * 8;
        int expectedSizeAlt = 8 + countAlt * 8;
        if (Math.abs(expectedSize - d.length) <= Math.abs(expectedSizeAlt - d.length) && count >= 0) {
            // i16이 더 적합
        } else if (countAlt >= 0 && countAlt < 1000) {
            count = countAlt;
        }

        field("TabCount", String.valueOf(count));
        addParsed("TabCount", count);

        int pos = 8;
        for (int i = 0; i < count && pos + 8 <= d.length; i++) {
            int tabPos = i32(d, pos); pos += 4;
            int tabType = u8(d, pos); pos++;
            int tabFill = u8(d, pos); pos++;
            int tabPad = u16(d, pos); pos += 2;
            String typeStr;
            switch (tabType) {
                case 0: typeStr = "Left"; break;
                case 1: typeStr = "Right"; break;
                case 2: typeStr = "Center"; break;
                case 3: typeStr = "Decimal"; break;
                default: typeStr = "Unknown(" + tabType + ")"; break;
            }
            field("Tab[" + i + "]", "pos=" + tabPos + " type=" + typeStr + " fill=" + tabFill);
        }
    }

    static void dumpNumbering(Rec r) {
        byte[] d = r.data;
        field("Size", String.valueOf(d.length));
        field("Raw (first 64 bytes)", hexDump(d, 0, Math.min(64, d.length)));
        // 가변 길이의 record -- 주요 필드만 덤프
        // 7 x (8바이트 paraHeadInfo + WORD len + WCHAR[len] formatStr)
        // 그 뒤에 UINT16 startNum 등
    }

    static void dumpBullet(Rec r) {
        byte[] d = r.data;
        field("Size", String.valueOf(d.length));
        if (d.length >= 20) {
            pass("BULLET size=" + d.length + " (spec: 20)");
        }
        field("Raw (first 32 bytes)", hexDump(d, 0, Math.min(32, d.length)));
    }

    static void dumpParaShape(Rec r) {
        byte[] d = r.data;
        // 명세상 기본 54 바이트, padding 포함 시 58 바이트일 수 있음
        if (d.length < 54) {
            warn("PARA_SHAPE size=" + d.length + ", expected >= 54");
        } else {
            pass("PARA_SHAPE size=" + d.length + " (spec base: 54)");
        }

        if (d.length < 4) return;

        long prop1 = u32(d, 0);
        fieldHex("Property1", prop1);
        int lineSpaceType = (int)(prop1 & 3);
        int alignment = (int)((prop1 >> 2) & 7);
        String[] alignStrs = {"Justify","Left","Right","Center","Distribute","Divide"};
        field("  LineSpaceType", String.valueOf(lineSpaceType));
        field("  Alignment", (alignment < alignStrs.length ? alignStrs[alignment] : "?") + " (" + alignment + ")");
        field("  WordBreakEnglish", String.valueOf((prop1 >> 5) & 3));
        field("  WordBreakKorean", String.valueOf((prop1 >> 7) & 1));
        field("  UseGridLines", String.valueOf((prop1 >> 8) & 1));
        field("  WidowOrphan", String.valueOf((prop1 >> 16) & 1));
        field("  KeepWithNext", String.valueOf((prop1 >> 17) & 1));
        field("  KeepTogether", String.valueOf((prop1 >> 18) & 1));
        field("  PageBreakBefore", String.valueOf((prop1 >> 19) & 1));
        field("  VertAlign", String.valueOf((prop1 >> 20) & 3));
        field("  HeadingType", String.valueOf((prop1 >> 23) & 3));
        field("  HeadingLevel", String.valueOf((prop1 >> 25) & 7));
        field("  BorderConnect", String.valueOf((prop1 >> 28) & 1));
        field("  IgnoreMargin", String.valueOf((prop1 >> 29) & 1));
        addParsed("ParaShapeProp1", prop1);

        if (d.length >= 30) {
            int leftMargin = i32(d, 4);
            int rightMargin = i32(d, 8);
            int indent = i32(d, 12);
            int spaceAbove = i32(d, 16);
            int spaceBelow = i32(d, 20);
            int lineSpace = i32(d, 24);
            field("LeftMargin", leftMargin + " (HWPUNIT)");
            field("RightMargin", rightMargin + " (HWPUNIT)");
            field("Indent", indent + " (HWPUNIT)");
            field("SpaceAbove", spaceAbove + " (HWPUNIT)");
            field("SpaceBelow", spaceBelow + " (HWPUNIT)");
            field("LineSpace", lineSpace + " (HWPUNIT)");
            addParsed("LeftMargin", leftMargin);
            addParsed("RightMargin", rightMargin);
            addParsed("Indent", indent);
            addParsed("LineSpace", lineSpace);
        }

        if (d.length >= 36) {
            int tabDefId = u16(d, 28);
            int numberingId = u16(d, 30);
            int borderFillId = u16(d, 32);
            field("TabDefId", String.valueOf(tabDefId));
            field("NumberingOrBulletId", String.valueOf(numberingId));
            field("BorderFillId", String.valueOf(borderFillId));
            addParsed("TabDefId", tabDefId);
        }

        if (d.length >= 44) {
            int bpLeft = i16(d, 34);
            int bpRight = i16(d, 36);
            int bpTop = i16(d, 38);
            int bpBottom = i16(d, 40);
            field("BorderPaddingLeft", String.valueOf(bpLeft));
            field("BorderPaddingRight", String.valueOf(bpRight));
            field("BorderPaddingTop", String.valueOf(bpTop));
            field("BorderPaddingBottom", String.valueOf(bpBottom));
        }

        if (d.length >= 46) {
            long prop2 = u32(d, 42);
            fieldHex("Property2", prop2);
            addParsed("ParaShapeProp2", prop2);
        }

        if (d.length >= 50) {
            long prop3 = u32(d, 46);
            fieldHex("Property3", prop3);
            addParsed("ParaShapeProp3", prop3);
        }

        if (d.length >= 54) {
            long lineSpace2 = u32(d, 50);
            fieldHex("LineSpace_v2", lineSpace2);
            addParsed("LineSpace_v2", lineSpace2);
        }
    }

    static void dumpStyle(Rec r) {
        byte[] d = r.data;
        int pos = 0;

        // 로컬(한글) 스타일 이름
        if (pos + 2 > d.length) { error("STYLE too small"); return; }
        int nameLen = u16(d, pos); pos += 2;
        String localName = "";
        if (nameLen > 0 && pos + nameLen * 2 <= d.length) {
            localName = readWchar(d, pos, nameLen);
            pos += nameLen * 2;
        }
        field("LocalName", "\"" + localName + "\" (len=" + nameLen + ")");
        addParsed("StyleLocalName", localName);

        // 영문 스타일 이름
        if (pos + 2 > d.length) return;
        int engLen = u16(d, pos); pos += 2;
        String engName = "";
        if (engLen > 0 && pos + engLen * 2 <= d.length) {
            engName = readWchar(d, pos, engLen);
            pos += engLen * 2;
        }
        field("EnglishName", "\"" + engName + "\" (len=" + engLen + ")");
        addParsed("StyleEnglishName", engName);

        if (pos + 8 <= d.length) {
            int type = u8(d, pos); pos++;
            int nextStyle = u8(d, pos); pos++;
            int langId = i16(d, pos); pos += 2;
            int paraShapeId = u16(d, pos); pos += 2;
            int charShapeId = u16(d, pos); pos += 2;

            String typeStr = (type == 0) ? "Paragraph" : (type == 1) ? "Character" : "Unknown(" + type + ")";
            field("Type", typeStr);
            field("NextStyleId", String.valueOf(nextStyle));
            field("LanguageId", String.valueOf(langId));
            field("ParaShapeId", String.valueOf(paraShapeId));
            field("CharShapeId", String.valueOf(charShapeId));
            addParsed("StyleType", typeStr);
            addParsed("ParaShapeId", paraShapeId);
            addParsed("CharShapeId", charShapeId);
        }

        // LockForm (5.0.3.2+)
        if (pos + 2 <= d.length) {
            int lockForm = u16(d, pos); pos += 2;
            field("LockForm", String.valueOf(lockForm));
        }
    }

    static void dumpDocData(Rec r) {
        field("Size", String.valueOf(r.size));
        field("Raw (first 64 bytes)", hexDump(r.data, 0, Math.min(64, r.size)));
    }

    static void dumpCompatibleDocument(Rec r) {
        byte[] d = r.data;
        if (d.length < 4) { error("COMPATIBLE_DOCUMENT size=" + d.length + ", expected 4"); return; }
        if (d.length == 4) pass("COMPATIBLE_DOCUMENT size=4 (matches spec)");

        long target = u32(d, 0);
        String targetStr;
        switch ((int) target) {
            case 0: targetStr = "HWP_Current"; break;
            case 1: targetStr = "HWP_2007_Compat"; break;
            case 2: targetStr = "MS_Word_Compat"; break;
            default: targetStr = "Unknown(" + target + ")"; break;
        }
        field("TargetProgram", targetStr);
        addParsed("TargetProgram", targetStr);
    }

    static void dumpLayoutCompatibility(Rec r) {
        byte[] d = r.data;
        if (d.length < 20) { warn("LAYOUT_COMPATIBILITY size=" + d.length + ", expected 20"); return; }
        if (d.length == 20) pass("LAYOUT_COMPATIBILITY size=20 (matches spec)");

        fieldHex("CharUnit", u32(d, 0));
        fieldHex("ParaUnit", u32(d, 4));
        fieldHex("SectionUnit", u32(d, 8));
        fieldHex("ObjectUnit", u32(d, 12));
        fieldHex("FieldUnit", u32(d, 16));

        addParsed("LayoutCompat.CharUnit", u32(d, 0));
        addParsed("LayoutCompat.ParaUnit", u32(d, 4));
        addParsed("LayoutCompat.SectionUnit", u32(d, 8));
        addParsed("LayoutCompat.ObjectUnit", u32(d, 12));
        addParsed("LayoutCompat.FieldUnit", u32(d, 16));
    }

    static void dumpDistributeDocData(Rec r) {
        if (r.size != 256) {
            warn("DISTRIBUTE_DOC_DATA size=" + r.size + ", expected 256");
        } else {
            pass("DISTRIBUTE_DOC_DATA size=256");
        }
        field("Raw (first 32 bytes)", hexDump(r.data, 0, Math.min(32, r.size)));
    }

    static void dumpGenericRecord(Rec r, String desc) {
        field("Description", desc);
        field("Size", String.valueOf(r.size));
        field("Raw (first 64 bytes)", hexDump(r.data, 0, Math.min(64, r.size)));
    }

    // ========================================================================
    // Section (BodyText) record 파싱
    // ========================================================================
    static void dumpSectionRecords(List<Rec> recs, String sectionName) {
        System.out.println("\n========== " + sectionName + " Records ==========");
        System.out.println("  Total records: " + recs.size());

        // level 계층 구조 검증
        validateLevelHierarchy(recs, sectionName);

        lastParaNChars = 0;
        int paraIndex = 0;

        for (int i = 0; i < recs.size(); i++) {
            Rec r = recs.get(i);
            currentPrefix = sectionName + ".rec#" + i + "." + tagName(r.tag);
            System.out.printf("\n  --- Record #%d: %s (tag=0x%03X, level=%d, size=%d, offset=%d) ---%n",
                i, tagName(r.tag), r.tag, r.level, r.size, r.streamOffset);

            switch (r.tag) {
                case TAG_PARA_HEADER:       dumpParaHeader(r); paraIndex++; break;
                case TAG_PARA_TEXT:          dumpParaText(r); break;
                case TAG_PARA_CHAR_SHAPE:   dumpParaCharShape(r); break;
                case TAG_PARA_LINE_SEG:     dumpParaLineSeg(r); break;
                case TAG_PARA_RANGE_TAG:    dumpParaRangeTag(r); break;
                case TAG_CTRL_HEADER:       dumpCtrlHeader(r); break;
                case TAG_LIST_HEADER:       dumpListHeader(r); break;
                case TAG_PAGE_DEF:          dumpPageDef(r); break;
                case TAG_FOOTNOTE_SHAPE:    dumpFootnoteShape(r); break;
                case TAG_PAGE_BORDER_FILL:  dumpPageBorderFill(r); break;
                case TAG_TABLE:             dumpTable(r); break;
                case TAG_SHAPE_COMPONENT:   dumpShapeComponent(r); break;
                case TAG_CTRL_DATA:         dumpGenericRecord(r, "CtrlData/ParameterSet"); break;
                case TAG_EQEDIT:            dumpGenericRecord(r, "Equation"); break;
                case TAG_FORM_OBJECT:       dumpGenericRecord(r, "FormObject"); break;
                case TAG_MEMO_SHAPE:        dumpGenericRecord(r, "MemoShape"); break;
                case TAG_MEMO_LIST:         dumpGenericRecord(r, "MemoList"); break;
                case TAG_CHART_DATA:        dumpGenericRecord(r, "ChartData"); break;
                case TAG_VIDEO_DATA:        dumpGenericRecord(r, "VideoData"); break;
                case TAG_SHAPE_COMPONENT_LINE:      dumpShapeComponentLine(r); break;
                case TAG_SHAPE_COMPONENT_RECT:      dumpShapeComponentRect(r); break;
                case TAG_SHAPE_COMPONENT_ELLIPSE:   dumpShapeComponentEllipse(r); break;
                case TAG_SHAPE_COMPONENT_ARC:       dumpShapeComponentArc(r); break;
                case TAG_SHAPE_COMPONENT_POLYGON:   dumpShapeComponentPolygon(r); break;
                case TAG_SHAPE_COMPONENT_CURVE:     dumpShapeComponentCurve(r); break;
                case TAG_SHAPE_COMPONENT_OLE:       dumpGenericRecord(r, "OLE_Shape"); break;
                case TAG_SHAPE_COMPONENT_PICTURE:   dumpGenericRecord(r, "Picture_Shape"); break;
                case TAG_SHAPE_COMPONENT_CONTAINER: dumpGenericRecord(r, "Container_Shape"); break;
                case TAG_SHAPE_COMPONENT_TEXTART:   dumpGenericRecord(r, "TextArt_Shape"); break;
                case TAG_SHAPE_COMPONENT_UNKNOWN:   dumpGenericRecord(r, "Unknown_Shape"); break;
                default:
                    info("Unhandled section tag: " + tagName(r.tag) + " (size=" + r.size + ")");
                    field("Raw (first 64 bytes)", hexDump(r.data, 0, Math.min(64, r.size)));
                    break;
            }
        }
    }

    static void dumpParaHeader(Rec r) {
        byte[] d = r.data;
        // 명세상 22 바이트 (구버전) 또는 24 바이트 (5.0.3.2+)
        if (d.length < 22) {
            error("PARA_HEADER size=" + d.length + ", expected >= 22");
            return;
        }

        long nCharsRaw = u32(d, 0);
        int nChars = (int)(nCharsRaw & 0x7FFFFFFF);
        boolean hasSpecialFlag = (nCharsRaw & 0x80000000L) != 0;
        long controlMask = u32(d, 4);
        int paraShapeId = u16(d, 8);
        int styleId = u8(d, 10);
        int columnBreak = u8(d, 11);
        int charShapeCount = u16(d, 12);
        int rangeTagCount = u16(d, 14);
        int lineSegCount = u16(d, 16);
        long instanceId = u32(d, 18);

        field("nChars (raw)", String.format("0x%08X", nCharsRaw));
        field("nChars (masked)", String.valueOf(nChars));
        field("SpecialFlag (bit31)", String.valueOf(hasSpecialFlag));
        fieldHex("ControlMask", controlMask);
        field("ParaShapeId", String.valueOf(paraShapeId));
        field("StyleId", String.valueOf(styleId));
        field("ColumnBreakType", columnBreakStr(columnBreak));
        field("CharShapeCount", String.valueOf(charShapeCount));
        field("RangeTagCount", String.valueOf(rangeTagCount));
        field("LineSegCount", String.valueOf(lineSegCount));
        fieldHex("InstanceId", instanceId);

        addParsed("nChars", nChars);
        addParsed("ControlMask", controlMask);
        addParsed("ParaShapeId", paraShapeId);
        addParsed("StyleId", styleId);
        addParsed("CharShapeCount", charShapeCount);
        addParsed("LineSegCount", lineSegCount);
        addParsed("InstanceId", instanceId);

        if (d.length >= 24) {
            int mergeFlag = u16(d, 22);
            field("MergeFlag", String.valueOf(mergeFlag));
            addParsed("MergeFlag", mergeFlag);
            if (d.length == 24) {
                pass("PARA_HEADER size=24 (includes merge flag)");
            }
        } else if (d.length == 22) {
            pass("PARA_HEADER size=22 (older version, no merge flag)");
        }

        // PARA_TEXT 검증을 위해 nChars 저장
        lastParaNChars = nChars;

        // 검증: nChars == 0이면 의심스러움
        if (nChars == 0) {
            warn("nChars = 0 in PARA_HEADER (typically at least 1 for paragraph break)");
        }
    }

    static String columnBreakStr(int v) {
        switch (v) {
            case 0x01: return "SectionBreak";
            case 0x02: return "MultiColumnBreak";
            case 0x04: return "PageBreak";
            case 0x08: return "ColumnBreak";
            default: return String.format("0x%02X", v);
        }
    }

    static void dumpParaText(Rec r) {
        byte[] d = r.data;
        field("Size", d.length + " bytes (" + (d.length / 2) + " WCHARs)");
        addParsed("ParaTextSize", d.length);

        // 핵심 검증: nChars * 2 == PARA_TEXT 크기
        if (lastParaNChars > 0) {
            int expectedSize = lastParaNChars * 2;
            if (expectedSize != d.length) {
                error(String.format("PARA_TEXT size mismatch: nChars=%d -> expected %d bytes, got %d bytes",
                    lastParaNChars, expectedSize, d.length));
            } else {
                pass(String.format("PARA_TEXT size matches nChars: %d * 2 = %d", lastParaNChars, d.length));
            }
        }

        // 컨트롤 문자와 텍스트 파싱
        int pos = 0;
        int charIdx = 0;
        StringBuilder textPreview = new StringBuilder();
        int extendedCtrlCount = 0;
        int inlineCtrlCount = 0;
        int charCtrlCount = 0;
        int normalCharCount = 0;

        while (pos + 2 <= d.length) {
            int ch = u16(d, pos);

            if (ch < 32) {
                // 컨트롤 문자
                if (EXTENDED_CTRL_CHARS.contains(ch)) {
                    // 확장 컨트롤: 8 WCHAR (16 바이트)
                    if (pos + 16 > d.length) {
                        error(String.format("Extended control char %d at WCHAR pos %d: insufficient data (need 16 bytes from pos %d, have %d)",
                            ch, charIdx, pos, d.length - pos));
                        break;
                    }
                    // 검증: 바이트 2-5는 ctrlId (big-endian 4 ASCII 문자)를 포함해야 함
                    // 실제로는 바이트 오프셋 pos+2..pos+5에 UINT32 LE로 저장됨
                    long ctrlIdRaw = u32(d, pos + 2);
                    String ctrlIdAscii = ctrlIdStr(ctrlIdRaw);

                    // 검증: 마지막 2 바이트 (pos+14..pos+15)는 첫 2 바이트 (pos..pos+1)의 복사본이어야 함
                    int firstWchar = u16(d, pos);
                    int lastWchar = u16(d, pos + 14);

                    field(String.format("ExtCtrl[%d] code=%d", extendedCtrlCount, ch),
                        String.format("ctrlId='%s' (0x%08X), copy=%s, raw=%s",
                            ctrlIdAscii, ctrlIdRaw,
                            (firstWchar == lastWchar) ? "OK" : "MISMATCH(first=0x" + Integer.toHexString(firstWchar) + " last=0x" + Integer.toHexString(lastWchar) + ")",
                            hexDump(d, pos, 16)));

                    if (firstWchar != lastWchar) {
                        error(String.format("Extended control at pos %d: trailing WCHAR 0x%04X != leading WCHAR 0x%04X",
                            pos, lastWchar, firstWchar));
                    }

                    // 검증: ctrlId는 인쇄 가능 ASCII여야 함 (또는 알려진 값)
                    boolean validAscii = true;
                    for (int ci = 0; ci < 4; ci++) {
                        int cb = (int)((ctrlIdRaw >> (24 - ci * 8)) & 0xFF);
                        if (cb < 0x20 || cb > 0x7E) { validAscii = false; break; }
                    }
                    if (!validAscii) {
                        warn(String.format("Extended control ctrlId '%s' (0x%08X) contains non-ASCII bytes",
                            ctrlIdAscii, ctrlIdRaw));
                    }

                    textPreview.append("{EXT:").append(ctrlIdAscii).append("}");
                    extendedCtrlCount++;
                    pos += 16; // 8 WCHARs
                    charIdx += 8;
                } else if (INLINE_CTRL_CHARS.contains(ch)) {
                    // Inline 컨트롤: 8 WCHAR (16 바이트)
                    if (pos + 16 > d.length) {
                        error(String.format("Inline control char %d at WCHAR pos %d: insufficient data", ch, charIdx));
                        break;
                    }
                    field(String.format("InlineCtrl[%d] code=%d", inlineCtrlCount, ch),
                        hexDump(d, pos, 16));
                    textPreview.append("{INL:").append(ch).append("}");
                    inlineCtrlCount++;
                    pos += 16;
                    charIdx += 8;
                } else if (CHAR_CTRL_CHARS.contains(ch)) {
                    // Char 컨트롤: 1 WCHAR (2 바이트)
                    String desc;
                    switch (ch) {
                        case 10: desc = "LineBreak"; break;
                        case 13: desc = "ParaBreak"; break;
                        case 24: desc = "Hyphen"; break;
                        case 30: desc = "NbSpace"; break;
                        case 31: desc = "FixedWidthSpace"; break;
                        default: desc = "CharCtrl(" + ch + ")"; break;
                    }
                    textPreview.append("{").append(desc).append("}");
                    charCtrlCount++;
                    pos += 2;
                    charIdx += 1;
                } else {
                    // 0-31 범위의 알 수 없는 컨트롤 문자
                    textPreview.append("{UNK:").append(ch).append("}");
                    warn("Unknown control character code " + ch + " at WCHAR pos " + charIdx);
                    pos += 2;
                    charIdx += 1;
                }
            } else {
                // 일반 문자
                if (ch >= 0x20 && ch < 0x7F) {
                    textPreview.append((char) ch);
                } else {
                    textPreview.append(String.format("\\u%04X", ch));
                }
                normalCharCount++;
                pos += 2;
                charIdx += 1;
            }
        }

        field("ExtendedControls", String.valueOf(extendedCtrlCount));
        field("InlineControls", String.valueOf(inlineCtrlCount));
        field("CharControls", String.valueOf(charCtrlCount));
        field("NormalChars", String.valueOf(normalCharCount));

        // 프리뷰 잘라내기
        String preview = textPreview.toString();
        if (preview.length() > 200) {
            preview = preview.substring(0, 200) + "...";
        }
        field("TextPreview", preview);
    }

    static void dumpParaCharShape(Rec r) {
        byte[] d = r.data;
        int count = d.length / 8;
        field("Size", d.length + " bytes (" + count + " entries)");
        addParsed("ParaCharShapeCount", count);

        if (d.length % 8 != 0) {
            warn("PARA_CHAR_SHAPE size " + d.length + " is not a multiple of 8");
        }

        for (int i = 0; i < count && i * 8 + 8 <= d.length; i++) {
            long startPos = u32(d, i * 8);
            long charShapeId = u32(d, i * 8 + 4);
            field(String.format("CharShapeRun[%d]", i),
                String.format("startPos=%d, charShapeId=%d", startPos, charShapeId));
            if (i == 0 && startPos != 0) {
                error("First PARA_CHAR_SHAPE entry has startPos=" + startPos + ", expected 0");
            }
        }
    }

    static void dumpParaLineSeg(Rec r) {
        byte[] d = r.data;
        int count = d.length / 36;
        field("Size", d.length + " bytes (" + count + " line segments)");

        if (d.length % 36 != 0) {
            warn("PARA_LINE_SEG size " + d.length + " is not a multiple of 36");
        }

        for (int i = 0; i < count && i < 10; i++) { // 최대 10개까지 표시
            int off = i * 36;
            long textStart = u32(d, off);
            int lineY = i32(d, off + 4);
            int lineHeight = i32(d, off + 8);
            int textHeight = i32(d, off + 12);
            int baseLine = i32(d, off + 16);
            int lineSpacing = i32(d, off + 20);
            int colStart = i32(d, off + 24);
            int segWidth = i32(d, off + 28);
            long tag = u32(d, off + 32);

            field(String.format("LineSeg[%d]", i),
                String.format("textStart=%d y=%d h=%d textH=%d baseline=%d spacing=%d colStart=%d width=%d tag=0x%08X",
                    textStart, lineY, lineHeight, textHeight, baseLine, lineSpacing, colStart, segWidth, tag));
        }
        if (count > 10) {
            field("...", "(" + (count - 10) + " more line segments)");
        }
    }

    static void dumpParaRangeTag(Rec r) {
        byte[] d = r.data;
        int count = d.length / 12;
        field("Size", d.length + " bytes (" + count + " range tags)");

        if (d.length % 12 != 0) {
            warn("PARA_RANGE_TAG size " + d.length + " is not a multiple of 12");
        }

        for (int i = 0; i < count; i++) {
            int off = i * 12;
            long start = u32(d, off);
            long end = u32(d, off + 4);
            long tagVal = u32(d, off + 8);
            field(String.format("RangeTag[%d]", i),
                String.format("start=%d end=%d tag=0x%08X", start, end, tagVal));
        }
    }

    static void dumpCtrlHeader(Rec r) {
        byte[] d = r.data;
        if (d.length < 4) {
            error("CTRL_HEADER size=" + d.length + ", expected >= 4");
            return;
        }

        long ctrlId = u32(d, 0);
        String ctrlStr = ctrlIdStr(ctrlId);
        field("CtrlId", String.format("'%s' (0x%08X)", ctrlStr, ctrlId));
        addParsed("CtrlId", ctrlStr);

        // 알려진 ctrl ID 검증
        if (KNOWN_CTRL_IDS.contains(ctrlId)) {
            pass("CtrlId '" + ctrlStr + "' is a known control ID");
        } else {
            warn("CtrlId '" + ctrlStr + "' (0x" + Long.toHexString(ctrlId) + ") is not in the known control ID list");
        }

        // 타입별 검증
        if (ctrlId == make4chid('s','e','c','d')) {
            // 구역 정의: 명세상 ctrlId 뒤에 26 바이트의 secd 데이터
            if (d.length < 30) {
                warn("CTRL_HEADER 'secd' size=" + d.length + ", expected >= 30 (4 ctrlId + 26 secd data)");
            } else {
                pass("CTRL_HEADER 'secd' size=" + d.length + " (>= 30)");
                // 구역 정의 필드 파싱
                long secdProp = u32(d, 4);
                int colGap = u16(d, 8);
                int vertGrid = u16(d, 10);
                int horizGrid = u16(d, 12);
                long defaultTab = u32(d, 14);
                int numShapeId = u16(d, 18);
                int pageNum = u16(d, 20);

                fieldHex("SecdProperty", secdProp);
                field("  HideHeader", String.valueOf(secdProp & 1));
                field("  HideFooter", String.valueOf((secdProp >> 1) & 1));
                field("  HideMasterPage", String.valueOf((secdProp >> 2) & 1));
                field("  HideBorder", String.valueOf((secdProp >> 3) & 1));
                field("  HideBg", String.valueOf((secdProp >> 4) & 1));
                field("  HidePageNumPos", String.valueOf((secdProp >> 5) & 1));
                field("  TextDirection", String.valueOf((secdProp >> 16) & 7));
                field("ColumnGap", String.valueOf(colGap));
                field("VertGrid", String.valueOf(vertGrid));
                field("HorizGrid", String.valueOf(horizGrid));
                field("DefaultTabInterval", String.valueOf(defaultTab));
                field("NumberingShapeId", String.valueOf(numShapeId));
                field("PageNumber", String.valueOf(pageNum));

                if (d.length >= 30) {
                    // pic/table/equation 시작 번호
                    int picStart = u16(d, 22);
                    int tblStart = u16(d, 24);
                    int eqStart = u16(d, 26);
                    int langId = (d.length >= 30) ? u16(d, 28) : 0;
                    field("PicStartNum", String.valueOf(picStart));
                    field("TableStartNum", String.valueOf(tblStart));
                    field("EquationStartNum", String.valueOf(eqStart));
                    field("LanguageId", String.valueOf(langId));
                }
            }
        } else if (ctrlId == make4chid('t','b','l',' ') || ctrlId == make4chid('g','s','o',' ')
                || (ctrlId & 0xFF000000L) == (((long)'$') << 24)) {
            // 공통 객체 속성을 가진 객체 컨트롤
            if (d.length < 50) { // 4 ctrlId + 공통 obj 속성 최소 46 바이트
                warn("Object CTRL_HEADER '" + ctrlStr + "' size=" + d.length + ", expected >= 50 (common obj props)");
            } else {
                pass("Object CTRL_HEADER '" + ctrlStr + "' size=" + d.length + " (has common obj properties)");
                dumpCommonObjProps(d, 4);
            }
        } else if (ctrlId == make4chid('c','o','l','d')) {
            field("Type", "ColumnDefinition");
            if (d.length >= 6) {
                int colType = u16(d, 4);
                field("ColumnType", String.valueOf(colType));
            }
        } else if (ctrlId == make4chid('h','e','a','d') || ctrlId == make4chid('f','o','o','t')) {
            field("Type", ctrlId == make4chid('h','e','a','d') ? "Header" : "Footer");
        } else if (ctrlId == make4chid('f','n',' ',' ') || ctrlId == make4chid('e','n',' ',' ')) {
            field("Type", ctrlId == make4chid('f','n',' ',' ') ? "Footnote" : "Endnote");
        } else {
            // 일반: 남은 바이트 덤프
            if (d.length > 4) {
                field("RemainingData (first 60 bytes)", hexDump(d, 4, Math.min(60, d.length - 4)));
            }
        }
    }

    static void dumpCommonObjProps(byte[] d, int off) {
        // 공통 객체 속성 (명세 표 69에 따라 46 + len 바이트)
        if (off + 46 > d.length) {
            warn("Not enough data for common object properties");
            return;
        }

        long objCtrlId = u32(d, off);
        long objProp = u32(d, off + 4);
        int vertOffset = i32(d, off + 8);
        int horizOffset = i32(d, off + 12);
        int width = i32(d, off + 16);
        int height = i32(d, off + 20);
        int zOrder = i32(d, off + 24);
        // 외곽 여백: off+28의 HWPUNIT16[4]
        int mLeft = i16(d, off + 28);
        int mRight = i16(d, off + 30);
        int mTop = i16(d, off + 32);
        int mBottom = i16(d, off + 34);
        long instId = u32(d, off + 36);
        int preventPageBreak = i32(d, off + 40);
        int descLen = u16(d, off + 44);

        field("ObjCommon.CtrlId", String.format("'%s' (0x%08X)", ctrlIdStr(objCtrlId), objCtrlId));
        fieldHex("ObjCommon.Property", objProp);
        field("  TreatAsChar", String.valueOf(objProp & 1));
        field("  AffectLineSpacing", String.valueOf((objProp >> 2) & 1));
        field("  VertRelTo", String.valueOf((objProp >> 3) & 3));
        field("  VertAlign", String.valueOf((objProp >> 5) & 7));
        field("  HorizRelTo", String.valueOf((objProp >> 8) & 3));
        field("  HorizAlign", String.valueOf((objProp >> 10) & 7));
        field("  FlowType", String.valueOf((objProp >> 21) & 7));
        field("  TextSide", String.valueOf((objProp >> 24) & 3));
        field("  NumberCategory", String.valueOf((objProp >> 26) & 7));
        field("ObjCommon.VertOffset", vertOffset + " (HWPUNIT)");
        field("ObjCommon.HorizOffset", horizOffset + " (HWPUNIT)");
        field("ObjCommon.Width", width + " (HWPUNIT)");
        field("ObjCommon.Height", height + " (HWPUNIT)");
        field("ObjCommon.ZOrder", String.valueOf(zOrder));
        field("ObjCommon.Margins", String.format("L=%d R=%d T=%d B=%d", mLeft, mRight, mTop, mBottom));
        fieldHex("ObjCommon.InstanceId", instId);
        field("ObjCommon.PreventPageBreak", String.valueOf(preventPageBreak));
        field("ObjCommon.DescriptionLen", String.valueOf(descLen));

        if (descLen > 0 && off + 46 + descLen * 2 <= d.length) {
            String desc = readWchar(d, off + 46, descLen);
            field("ObjCommon.Description", "\"" + desc + "\"");
        }

        addParsed("ObjWidth", width);
        addParsed("ObjHeight", height);
        addParsed("ObjInstanceId", instId);
    }

    static void dumpListHeader(Rec r) {
        byte[] d = r.data;
        if (d.length < 6) {
            warn("LIST_HEADER size=" + d.length + ", expected >= 6");
            return;
        }

        int paraCount = i16(d, 0);
        long prop = u32(d, 2);

        field("ParaCount", String.valueOf(paraCount));
        fieldHex("Property", prop);
        int textDir = (int)(prop & 7);
        int lineWrap = (int)((prop >> 3) & 3);
        int vertAlign = (int)((prop >> 5) & 3);
        field("  TextDirection", textDir == 0 ? "Horizontal" : "Vertical");
        field("  LineWrap", lineWrap == 0 ? "Normal" : lineWrap == 1 ? "KeepOneLine" : "ExpandWidth");
        field("  VertAlign", vertAlign == 0 ? "Top" : vertAlign == 1 ? "Center" : "Bottom");

        addParsed("ListParaCount", paraCount);
        addParsed("ListProp", prop);

        // 셀 속성 (표의 셀인 경우): 특정 offset부터 26 바이트
        // 셀용 list header 구조: 6 바이트 헤더 + padding + 26 바이트 셀 속성
        if (d.length >= 32) {
            // offset 6에서 셀 속성 읽기 시도
            int cellCol = u16(d, 6);
            int cellRow = u16(d, 8);
            int colSpan = u16(d, 10);
            int rowSpan = u16(d, 12);
            int cellWidth = i32(d, 14);
            int cellHeight = i32(d, 18);

            field("Cell.Col", String.valueOf(cellCol));
            field("Cell.Row", String.valueOf(cellRow));
            field("Cell.ColSpan", String.valueOf(colSpan));
            field("Cell.RowSpan", String.valueOf(rowSpan));
            field("Cell.Width", cellWidth + " (HWPUNIT)");
            field("Cell.Height", cellHeight + " (HWPUNIT)");

            if (d.length >= 32) {
                int cmLeft = i16(d, 22);
                int cmRight = i16(d, 24);
                int cmTop = i16(d, 26);
                int cmBottom = i16(d, 28);
                int bfId = u16(d, 30);
                field("Cell.Margins", String.format("L=%d R=%d T=%d B=%d", cmLeft, cmRight, cmTop, cmBottom));
                field("Cell.BorderFillId", String.valueOf(bfId));
                addParsed("CellBorderFillId", bfId);
            }
        }

        if (d.length > 32) {
            field("Remaining", hexDump(d, 32, Math.min(32, d.length - 32)));
        }
    }

    static void dumpPageDef(Rec r) {
        byte[] d = r.data;
        if (d.length < 40) {
            error("PAGE_DEF size=" + d.length + ", expected 40");
            return;
        }
        if (d.length == 40) {
            pass("PAGE_DEF size=40 (matches spec)");
        }

        int paperW = i32(d, 0);
        int paperH = i32(d, 4);
        int marginL = i32(d, 8);
        int marginR = i32(d, 12);
        int marginT = i32(d, 16);
        int marginB = i32(d, 20);
        int headerM = i32(d, 24);
        int footerM = i32(d, 28);
        int gutterM = i32(d, 32);
        long pageProp = u32(d, 36);

        field("PaperWidth", paperW + " (HWPUNIT, " + String.format("%.1fmm", paperW / 283.46) + ")");
        field("PaperHeight", paperH + " (HWPUNIT, " + String.format("%.1fmm", paperH / 283.46) + ")");
        field("MarginLeft", marginL + " (HWPUNIT)");
        field("MarginRight", marginR + " (HWPUNIT)");
        field("MarginTop", marginT + " (HWPUNIT)");
        field("MarginBottom", marginB + " (HWPUNIT)");
        field("HeaderMargin", headerM + " (HWPUNIT)");
        field("FooterMargin", footerM + " (HWPUNIT)");
        field("GutterMargin", gutterM + " (HWPUNIT)");
        fieldHex("Property", pageProp);
        int orient = (int)(pageProp & 1);
        int binding = (int)((pageProp >> 1) & 3);
        field("  Orientation", orient == 0 ? "Portrait" : "Landscape");
        field("  Binding", binding == 0 ? "SingleSided" : binding == 1 ? "DoubleSided" : "TopFlip");

        addParsed("PaperWidth", paperW);
        addParsed("PaperHeight", paperH);
        addParsed("MarginLeft", marginL);
        addParsed("MarginRight", marginR);
        addParsed("MarginTop", marginT);
        addParsed("MarginBottom", marginB);
        addParsed("Orientation", orient == 0 ? "Portrait" : "Landscape");
    }

    static void dumpFootnoteShape(Rec r) {
        byte[] d = r.data;
        if (d.length < 26) {
            warn("FOOTNOTE_SHAPE size=" + d.length + ", expected >= 26");
            return;
        }
        if (d.length == 26) pass("FOOTNOTE_SHAPE size=26 (matches spec)");

        long prop = u32(d, 0);
        int userSymbol = u16(d, 4);
        int prefixChar = u16(d, 6);
        int suffixChar = u16(d, 8);
        int startNum = u16(d, 10);
        int dividerLen = i16(d, 12);
        int aboveMargin = i16(d, 14);
        int belowMargin = i16(d, 16);
        int noteSpacing = i16(d, 18);
        int lineType = u8(d, 20);
        int lineWidth = u8(d, 21);
        long lineColor = u32(d, 22);

        fieldHex("Property", prop);
        field("  NumberFormat", String.valueOf(prop & 0xFF));
        field("  MultiColumnPlacement", String.valueOf((prop >> 8) & 3));
        field("  Numbering", String.valueOf((prop >> 10) & 3));
        fieldHex16("UserSymbol", userSymbol);
        fieldHex16("PrefixChar", prefixChar);
        fieldHex16("SuffixChar", suffixChar);
        field("StartNumber", String.valueOf(startNum));
        field("DividerLength", String.valueOf(dividerLen));
        field("AboveMargin", String.valueOf(aboveMargin));
        field("BelowMargin", String.valueOf(belowMargin));
        field("NoteSpacing", String.valueOf(noteSpacing));
        field("LineType", borderTypeStr(lineType));
        field("LineWidth", borderWidthStr(lineWidth));
        field("LineColor", colorStr(lineColor));

        addParsed("FootnoteStartNum", startNum);
    }

    static void dumpPageBorderFill(Rec r) {
        byte[] d = r.data;
        if (d.length < 14) {
            warn("PAGE_BORDER_FILL size=" + d.length + ", expected >= 14");
            return;
        }
        if (d.length == 14) pass("PAGE_BORDER_FILL size=14 (matches spec)");

        long prop = u32(d, 0);
        int padLeft = i16(d, 4);
        int padRight = i16(d, 6);
        int padTop = i16(d, 8);
        int padBottom = i16(d, 10);
        int bfId = u16(d, 12);

        fieldHex("Property", prop);
        field("Padding", String.format("L=%d R=%d T=%d B=%d", padLeft, padRight, padTop, padBottom));
        field("BorderFillId", String.valueOf(bfId));

        addParsed("PageBorderFillId", bfId);
    }

    static void dumpTable(Rec r) {
        byte[] d = r.data;
        if (d.length < 22) {
            warn("TABLE size=" + d.length + ", expected >= 22");
            return;
        }

        int pos = 0;
        long prop = u32(d, pos); pos += 4;
        int rows = u16(d, pos); pos += 2;
        int cols = u16(d, pos); pos += 2;
        int cellSpacing = i16(d, pos); pos += 2;
        // 내부 padding: 4 x HWPUNIT16
        int padLeft = i16(d, pos); pos += 2;
        int padRight = i16(d, pos); pos += 2;
        int padTop = i16(d, pos); pos += 2;
        int padBottom = i16(d, pos); pos += 2;

        fieldHex("Property", prop);
        field("  PageBreak", String.valueOf(prop & 3));
        field("  RepeatHeaderRow", String.valueOf((prop >> 2) & 1));
        field("Rows", String.valueOf(rows));
        field("Cols", String.valueOf(cols));
        field("CellSpacing", String.valueOf(cellSpacing));
        field("InnerPadding", String.format("L=%d R=%d T=%d B=%d", padLeft, padRight, padTop, padBottom));

        addParsed("TableRows", rows);
        addParsed("TableCols", cols);

        // 행 높이: WORD[rows]
        StringBuilder rowHStr = new StringBuilder();
        for (int i = 0; i < rows && pos + 2 <= d.length; i++) {
            int rh = u16(d, pos); pos += 2;
            if (i > 0) rowHStr.append(", ");
            rowHStr.append(rh);
        }
        field("RowHeights", rowHStr.toString());

        // BorderFillId
        if (pos + 2 <= d.length) {
            int bfId = u16(d, pos); pos += 2;
            field("BorderFillId", String.valueOf(bfId));
            addParsed("TableBorderFillId", bfId);
        }

        // Zone 개수 (5.0.1.0+)
        if (pos + 2 <= d.length) {
            int zoneCount = u16(d, pos); pos += 2;
            field("ZoneCount", String.valueOf(zoneCount));

            for (int i = 0; i < zoneCount && pos + 10 <= d.length; i++) {
                int startCol = u16(d, pos);
                int startRow = u16(d, pos + 2);
                int endCol = u16(d, pos + 4);
                int endRow = u16(d, pos + 6);
                int zoneBfId = u16(d, pos + 8);
                pos += 10;
                field(String.format("Zone[%d]", i),
                    String.format("(%d,%d)-(%d,%d) borderFillId=%d", startCol, startRow, endCol, endRow, zoneBfId));
            }
        }
    }

    static void dumpShapeComponent(Rec r) {
        byte[] d = r.data;
        if (d.length < 4) {
            error("SHAPE_COMPONENT size=" + d.length + ", expected >= 4");
            return;
        }
        long ctrlId = u32(d, 0);
        field("CtrlId", String.format("'%s' (0x%08X)", ctrlIdStr(ctrlId), ctrlId));

        // Shape component는 offset 4부터 element 속성이 시작됨
        // GenShapeObject의 경우 ctrlId가 두 번 기록됨
        if (d.length >= 8) {
            long ctrlId2 = u32(d, 4);
            if (ctrlId == ctrlId2) {
                field("CtrlId (duplicate)", String.format("'%s' -- GenShapeObject (id written twice)", ctrlIdStr(ctrlId2)));
            }
        }

        // 데이터가 충분하면 shape element 공통 속성 파싱
        int off = (d.length >= 8 && u32(d, 0) == u32(d, 4)) ? 8 : 4;
        if (off + 42 <= d.length) {
            int xOff = i32(d, off);
            int yOff = i32(d, off + 4);
            int groupCnt = u16(d, off + 8);
            int localVer = u16(d, off + 10);
            long initW = u32(d, off + 12);
            long initH = u32(d, off + 16);
            long curW = u32(d, off + 20);
            long curH = u32(d, off + 24);
            long flipProp = u32(d, off + 28);
            int rotAngle = i16(d, off + 32);
            int rotCX = i32(d, off + 34);
            int rotCY = i32(d, off + 38);

            field("GroupXOffset", String.valueOf(xOff));
            field("GroupYOffset", String.valueOf(yOff));
            field("GroupLevel", String.valueOf(groupCnt));
            field("LocalVersion", String.valueOf(localVer));
            field("InitialSize", initW + " x " + initH);
            field("CurrentSize", curW + " x " + curH);
            field("Flip", String.valueOf(flipProp));
            field("RotationAngle", String.valueOf(rotAngle));
            field("RotationCenter", rotCX + ", " + rotCY);
        }

        if (d.length > 4) {
            field("Total size", String.valueOf(d.length));
        }
    }

    static void dumpShapeComponentLine(Rec r) {
        byte[] d = r.data;
        if (d.length < 18) {
            warn("SHAPE_COMPONENT_LINE size=" + d.length + ", expected >= 18");
        }
        if (d.length >= 18) {
            int x1 = i32(d, 0), y1 = i32(d, 4);
            int x2 = i32(d, 8), y2 = i32(d, 12);
            int attr = u16(d, 16);
            field("StartPoint", x1 + ", " + y1);
            field("EndPoint", x2 + ", " + y2);
            field("Attribute", String.valueOf(attr));
        }
    }

    static void dumpShapeComponentRect(Rec r) {
        byte[] d = r.data;
        if (d.length < 33) {
            warn("SHAPE_COMPONENT_RECTANGLE size=" + d.length + ", expected >= 33");
        }
        if (d.length >= 33) {
            int roundness = u8(d, 0);
            field("Roundness", roundness + "%");
            for (int i = 0; i < 4; i++) {
                int x = i32(d, 1 + i * 4);
                int y = i32(d, 17 + i * 4);
                field("Corner[" + i + "]", x + ", " + y);
            }
        }
    }

    static void dumpShapeComponentEllipse(Rec r) {
        byte[] d = r.data;
        if (d.length < 60) {
            warn("SHAPE_COMPONENT_ELLIPSE size=" + d.length + ", expected >= 60");
        }
        if (d.length >= 60) {
            long eprop = u32(d, 0);
            int cx = i32(d, 4), cy = i32(d, 8);
            int ax1x = i32(d, 12), ax1y = i32(d, 16);
            int ax2x = i32(d, 20), ax2y = i32(d, 24);
            field("Property", String.format("0x%08X", eprop));
            field("Center", cx + ", " + cy);
            field("Axis1", ax1x + ", " + ax1y);
            field("Axis2", ax2x + ", " + ax2y);
            field("StartPos", i32(d, 28) + ", " + i32(d, 32));
            field("EndPos", i32(d, 36) + ", " + i32(d, 40));
            field("StartPos2", i32(d, 44) + ", " + i32(d, 48));
            field("EndPos2", i32(d, 52) + ", " + i32(d, 56));
        }
    }

    static void dumpShapeComponentArc(Rec r) {
        byte[] d = r.data;
        if (d.length >= 28) {
            long aprop = u32(d, 0);
            field("Property", String.format("0x%08X", aprop));
            field("Center", i32(d, 4) + ", " + i32(d, 8));
            field("Axis1", i32(d, 12) + ", " + i32(d, 16));
            field("Axis2", i32(d, 20) + ", " + i32(d, 24));
        }
    }

    static void dumpShapeComponentPolygon(Rec r) {
        byte[] d = r.data;
        if (d.length >= 2) {
            int cnt = i16(d, 0);
            field("PointCount", String.valueOf(cnt));
            for (int i = 0; i < cnt && 2 + i * 4 + 4 <= d.length; i++) {
                int x = i32(d, 2 + i * 4);
                field("X[" + i + "]", String.valueOf(x));
            }
            int yOff = 2 + cnt * 4;
            for (int i = 0; i < cnt && yOff + i * 4 + 4 <= d.length; i++) {
                int y = i32(d, yOff + i * 4);
                field("Y[" + i + "]", String.valueOf(y));
            }
        }
    }

    static void dumpShapeComponentCurve(Rec r) {
        byte[] d = r.data;
        if (d.length >= 2) {
            int cnt = i16(d, 0);
            field("PointCount", String.valueOf(cnt));
            field("ExpectedSize", String.valueOf(2 + cnt * 8 + (cnt - 1)));
        }
        field("Raw (first 64 bytes)", hexDump(d, 0, Math.min(64, d.length)));
    }

    // ========================================================================
    // level 계층 구조 검증
    // ========================================================================
    static void validateLevelHierarchy(List<Rec> recs, String streamName) {
        int prevLevel = -1;
        boolean valid = true;
        for (int i = 0; i < recs.size(); i++) {
            Rec r = recs.get(i);
            if (i == 0) {
                // 일반적으로 첫 record의 level은 0이어야 함
                if (r.level != 0) {
                    warn(streamName + " first record level is " + r.level + ", expected 0");
                }
            } else {
                // level은 부모 대비 최대 1까지만 증가 가능, 감소는 임의 level로 가능
                if (r.level > prevLevel + 1) {
                    warn(String.format("%s record #%d (%s): level=%d jumps from previous level=%d (max increase is 1)",
                        streamName, i, tagName(r.tag), r.level, prevLevel));
                    valid = false;
                }
            }
            prevLevel = r.level;
        }
        if (valid) {
            pass(streamName + " level hierarchy valid (children have level > parent, no gaps)");
        }
    }

    // ========================================================================
    // 비교 모드
    // ========================================================================
    static void compareFiles(String refPath, String outPath) throws Exception {
        System.out.println("======================================================");
        System.out.println("HWP COMPARISON MODE");
        System.out.println("  Reference: " + refPath);
        System.out.println("  Output:    " + outPath);
        System.out.println("======================================================");

        // 두 파일 모두 파싱
        ParsedFile refParsed = parseFile(refPath, false);
        ParsedFile outParsed = parseFile(outPath, false);

        System.out.println("\n======================================================");
        System.out.println("COMPARISON RESULTS");
        System.out.println("======================================================");

        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(refParsed.fieldMap.keySet());
        allKeys.addAll(outParsed.fieldMap.keySet());

        int matchCount = 0;
        int diffCount = 0;
        int refOnlyCount = 0;
        int outOnlyCount = 0;
        List<String> diffs = new ArrayList<>();

        for (String key : allKeys) {
            String refVal = refParsed.fieldMap.get(key);
            String outVal = outParsed.fieldMap.get(key);

            if (refVal == null) {
                outOnlyCount++;
                diffs.add(String.format("  EXTRA in output: %-60s = %s", key, outVal));
            } else if (outVal == null) {
                refOnlyCount++;
                diffs.add(String.format("  MISSING in output: %-60s (ref=%s)", key, refVal));
            } else if (refVal.equals(outVal)) {
                matchCount++;
            } else {
                diffCount++;
                diffs.add(String.format("  DIFFER: %-60s ref=%-30s out=%s", key, refVal, outVal));
            }
        }

        System.out.println("\n  Fields matching:         " + matchCount);
        System.out.println("  Fields differing:        " + diffCount);
        System.out.println("  Fields only in reference:" + refOnlyCount);
        System.out.println("  Fields only in output:   " + outOnlyCount);

        if (!diffs.isEmpty()) {
            System.out.println("\n  --- Differences ---");
            for (String d : diffs) {
                System.out.println(d);
            }
        } else {
            System.out.println("\n  All parsed fields match.");
        }

        // raw record 개수 및 순서도 비교
        System.out.println("\n  --- Record-level comparison ---");
        compareRecordStructure(refPath, outPath);
    }

    static void compareRecordStructure(String refPath, String outPath) throws Exception {
        POIFSFileSystem refPoi = new POIFSFileSystem(new FileInputStream(refPath));
        POIFSFileSystem outPoi = new POIFSFileSystem(new FileInputStream(outPath));

        byte[] refHdr = readStream(refPoi, "FileHeader");
        byte[] outHdr = readStream(outPoi, "FileHeader");
        boolean refCompressed = refHdr.length >= 40 && (u32(refHdr, 36) & 0x01) != 0;
        boolean outCompressed = outHdr.length >= 40 && (u32(outHdr, 36) & 0x01) != 0;

        // DocInfo record 비교
        compareStreamRecords(refPoi, outPoi, refCompressed, outCompressed, "DocInfo");

        // Section0 비교
        try {
            compareStreamRecords(refPoi, outPoi, refCompressed, outCompressed, "BodyText", "Section0");
        } catch (Exception e) {
            System.out.println("  Could not compare Section0: " + e.getMessage());
        }

        refPoi.close();
        outPoi.close();
    }

    static void compareStreamRecords(POIFSFileSystem refPoi, POIFSFileSystem outPoi,
                                     boolean refComp, boolean outComp, String... path) throws IOException {
        String streamPath = String.join("/", path);
        byte[] refRaw = readStream(refPoi, path);
        byte[] outRaw = readStream(outPoi, path);
        if (refComp) refRaw = decompress(refRaw);
        if (outComp) outRaw = decompress(outRaw);

        List<Rec> refRecs = parseRecords(refRaw);
        List<Rec> outRecs = parseRecords(outRaw);

        System.out.println("\n  " + streamPath + ": ref=" + refRecs.size() + " records, out=" + outRecs.size() + " records");

        int minLen = Math.min(refRecs.size(), outRecs.size());
        int tagMismatches = 0;
        int sizeMismatches = 0;
        int dataMismatches = 0;

        for (int i = 0; i < minLen; i++) {
            Rec rr = refRecs.get(i);
            Rec or = outRecs.get(i);

            if (rr.tag != or.tag) {
                System.out.printf("    Record #%d: TAG MISMATCH ref=%s(0x%03X) out=%s(0x%03X)%n",
                    i, tagName(rr.tag), rr.tag, tagName(or.tag), or.tag);
                tagMismatches++;
            } else if (rr.size != or.size) {
                System.out.printf("    Record #%d (%s): SIZE MISMATCH ref=%d out=%d%n",
                    i, tagName(rr.tag), rr.size, or.size);
                sizeMismatches++;
            } else if (!Arrays.equals(rr.data, or.data)) {
                // 최초로 다른 바이트 위치 찾기
                int firstDiff = -1;
                for (int b = 0; b < rr.data.length; b++) {
                    if (rr.data[b] != or.data[b]) { firstDiff = b; break; }
                }
                System.out.printf("    Record #%d (%s): DATA MISMATCH at byte %d (ref=0x%02X out=0x%02X)%n",
                    i, tagName(rr.tag), firstDiff,
                    rr.data[firstDiff] & 0xFF, or.data[firstDiff] & 0xFF);
                dataMismatches++;
            }
        }

        if (refRecs.size() != outRecs.size()) {
            System.out.printf("    Extra records: ref has %d more%n",
                refRecs.size() - outRecs.size());
        }

        System.out.printf("    Summary: %d tag mismatches, %d size mismatches, %d data mismatches (of %d compared)%n",
            tagMismatches, sizeMismatches, dataMismatches, minLen);
    }

    // ========================================================================
    // 전체 파일 파싱
    // ========================================================================
    static ParsedFile parseFile(String path, boolean verbose) throws Exception {
        ParsedFile parsed = new ParsedFile();
        currentParsed = parsed;

        if (verbose) {
            System.out.println("======================================================");
            System.out.println("HWP 5.0 BINARY DUMP AND VALIDATION");
            System.out.println("  File: " + path);
            System.out.println("======================================================");
        } else {
            // 비교 파싱 중에는 출력을 리다이렉트하여 억제
            // (플래그만 설정해서 체크함)
        }

        POIFSFileSystem poi = new POIFSFileSystem(new FileInputStream(path));

        // ---- FileHeader ----
        currentPrefix = "FileHeader";
        byte[] fileHeader = readStream(poi, "FileHeader");
        if (verbose) dumpFileHeader(fileHeader);
        else parseFileHeaderSilent(fileHeader, parsed);

        boolean compressed = fileHeader.length >= 40 && (u32(fileHeader, 36) & 0x01) != 0;
        boolean encrypted = fileHeader.length >= 40 && (u32(fileHeader, 36) & 0x02) != 0;

        if (encrypted) {
            if (verbose) System.out.println("\n  *** File is encrypted - cannot parse DocInfo/BodyText ***");
            poi.close();
            currentParsed = null;
            return parsed;
        }

        // ---- DocInfo ----
        byte[] docInfoRaw = readStream(poi, "DocInfo");
        if (compressed) docInfoRaw = decompress(docInfoRaw);
        List<Rec> docInfoRecs = parseRecords(docInfoRaw);
        if (verbose) {
            dumpDocInfoRecords(docInfoRecs);
        } else {
            parseDocInfoSilent(docInfoRecs, parsed);
        }

        // ---- Sections ----
        DirectoryEntry root = poi.getRoot();
        if (hasStorage(root, "BodyText")) {
            DirectoryEntry bodyText = (DirectoryEntry) root.getEntry("BodyText");
            for (int sec = 0; sec < 100; sec++) {
                String secName = "Section" + sec;
                if (!hasEntry(bodyText, secName)) break;

                byte[] secRaw = readStream(poi, "BodyText", secName);
                if (compressed) secRaw = decompress(secRaw);
                List<Rec> secRecs = parseRecords(secRaw);
                if (verbose) {
                    dumpSectionRecords(secRecs, "BodyText/" + secName);
                } else {
                    parseSectionSilent(secRecs, parsed, "BodyText/" + secName);
                }
            }
        }

        // ---- 기타 storage 목록 ----
        if (verbose) {
            System.out.println("\n========== OLE2 Storage Structure ==========");
            listEntries(root, "  ");
        }

        poi.close();
        currentParsed = null;
        return parsed;
    }

    static void parseFileHeaderSilent(byte[] hdr, ParsedFile parsed) {
        currentPrefix = "FileHeader";
        if (hdr.length >= 36) {
            long ver = u32(hdr, 32);
            parsed.add("FileHeader.Version", String.format("%d.%d.%d.%d",
                (ver >> 24) & 0xFF, (ver >> 16) & 0xFF, (ver >> 8) & 0xFF, ver & 0xFF));
        }
        if (hdr.length >= 40) {
            parsed.add("FileHeader.Properties", String.format("0x%08X", u32(hdr, 36)));
        }
        if (hdr.length >= 44) {
            parsed.add("FileHeader.License", String.format("0x%08X", u32(hdr, 40)));
        }
        if (hdr.length >= 48) {
            parsed.add("FileHeader.EncryptVersion", String.valueOf(u32(hdr, 44)));
        }
        if (hdr.length >= 49) {
            parsed.add("FileHeader.KOGLCountry", String.valueOf(u8(hdr, 48)));
        }
    }

    static void parseDocInfoSilent(List<Rec> recs, ParsedFile parsed) {
        for (int i = 0; i < recs.size(); i++) {
            Rec r = recs.get(i);
            String prefix = "DocInfo.rec#" + i + "." + tagName(r.tag);
            currentPrefix = prefix;
            parsed.add(prefix + ".tag", String.format("0x%03X", r.tag));
            parsed.add(prefix + ".level", String.valueOf(r.level));
            parsed.add(prefix + ".size", String.valueOf(r.size));

            // 잘 알려진 record 타입의 주요 필드 파싱
            switch (r.tag) {
                case TAG_DOCUMENT_PROPERTIES:
                    if (r.data.length >= 26) {
                        parsed.add(prefix + ".SectionCount", String.valueOf(u16(r.data, 0)));
                    }
                    break;
                case TAG_CHAR_SHAPE:
                    if (r.data.length >= 46) {
                        parsed.add(prefix + ".BaseSize", String.valueOf(i32(r.data, 42)));
                    }
                    if (r.data.length >= 50) {
                        parsed.add(prefix + ".Property", String.format("0x%08X", u32(r.data, 46)));
                    }
                    break;
                case TAG_PARA_SHAPE:
                    if (r.data.length >= 4) {
                        parsed.add(prefix + ".Property1", String.format("0x%08X", u32(r.data, 0)));
                    }
                    break;
            }
        }
    }

    static void parseSectionSilent(List<Rec> recs, ParsedFile parsed, String sectionName) {
        lastParaNChars = 0;
        for (int i = 0; i < recs.size(); i++) {
            Rec r = recs.get(i);
            String prefix = sectionName + ".rec#" + i + "." + tagName(r.tag);
            currentPrefix = prefix;
            parsed.add(prefix + ".tag", String.format("0x%03X", r.tag));
            parsed.add(prefix + ".level", String.valueOf(r.level));
            parsed.add(prefix + ".size", String.valueOf(r.size));

            switch (r.tag) {
                case TAG_PARA_HEADER:
                    if (r.data.length >= 4) {
                        int nChars = (int)(u32(r.data, 0) & 0x7FFFFFFF);
                        parsed.add(prefix + ".nChars", String.valueOf(nChars));
                        lastParaNChars = nChars;
                    }
                    if (r.data.length >= 10) {
                        parsed.add(prefix + ".ParaShapeId", String.valueOf(u16(r.data, 8)));
                        parsed.add(prefix + ".StyleId", String.valueOf(u8(r.data, 10)));
                    }
                    break;
                case TAG_PARA_TEXT:
                    parsed.add(prefix + ".Size", String.valueOf(r.data.length));
                    break;
                case TAG_CTRL_HEADER:
                    if (r.data.length >= 4) {
                        parsed.add(prefix + ".CtrlId", ctrlIdStr(u32(r.data, 0)));
                    }
                    break;
                case TAG_PAGE_DEF:
                    if (r.data.length >= 40) {
                        parsed.add(prefix + ".PaperWidth", String.valueOf(i32(r.data, 0)));
                        parsed.add(prefix + ".PaperHeight", String.valueOf(i32(r.data, 4)));
                    }
                    break;
                case TAG_TABLE:
                    if (r.data.length >= 8) {
                        parsed.add(prefix + ".Rows", String.valueOf(u16(r.data, 4)));
                        parsed.add(prefix + ".Cols", String.valueOf(u16(r.data, 6)));
                    }
                    break;
            }
        }
    }

    static void listEntries(DirectoryEntry dir, String indent) {
        for (Iterator<Entry> it = dir.getEntries(); it.hasNext();) {
            Entry entry = it.next();
            if (entry instanceof DirectoryEntry) {
                System.out.println(indent + "[DIR]  " + entry.getName());
                listEntries((DirectoryEntry) entry, indent + "  ");
            } else if (entry instanceof DocumentEntry) {
                DocumentEntry doc = (DocumentEntry) entry;
                System.out.println(indent + "[FILE] " + entry.getName() + " (" + doc.getSize() + " bytes)");
            }
        }
    }

    // ========================================================================
    // Main
    // ========================================================================
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage:");
            System.out.println("  java HwpDumper <file.hwp>             -- dump single file");
            System.out.println("  java HwpDumper <ref.hwp> <output.hwp> -- compare two files");
            System.exit(1);
        }

        if (args.length == 1) {
            // 단일 파일 덤프 모드
            errorCount = 0; warnCount = 0; passCount = 0;
            parseFile(args[0], true);

            System.out.println("\n======================================================");
            System.out.println("VALIDATION SUMMARY");
            System.out.println("======================================================");
            System.out.println("  PASS:    " + passCount);
            System.out.println("  WARN:    " + warnCount);
            System.out.println("  ERROR:   " + errorCount);
            if (errorCount > 0) {
                System.out.println("  RESULT: FAILED (" + errorCount + " errors found)");
            } else if (warnCount > 0) {
                System.out.println("  RESULT: PASSED WITH WARNINGS");
            } else {
                System.out.println("  RESULT: PASSED");
            }
        } else {
            // 비교 모드
            compareFiles(args[0], args[1]);
        }
    }
}
