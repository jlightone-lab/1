package kr.n.nframe.newfeature.odtcommon;

/**
 * task9/10: HWP/HWPX 전용 폰트(HY견고딕 등)가 Supplementary PUA-A(U+F0000~U+FFFFD)에
 * 할당한 커스텀 글리프(원문자 ①·화살표 →·도장 ㊞ 등)를 표준 Unicode 로 치환한다.
 *
 * <p>HWP→MD 경로(HwpMdConverter.mapHwpPuaCodePoint)와 동일한 매핑을 ODT 양 파이프라인
 * (hwp2odt / hwpx2odt)에서 공유하기 위한 단일 지점. ODT 본문 텍스트 직렬화 직전(esc)에 적용된다.
 *
 * <p>매핑된 코드포인트 → 표준 글리프. 매핑이 없는 Supplementary PUA 는 알 수 없는 글리프(.notdef
 * 사각형/물음표) 노출을 막기 위해 제거한다(MD 경로와 동일 정책). BMP 영역 문자는 건드리지 않는다.
 */
public final class PuaCharMapper {
    private PuaCharMapper() {}

    /** 문자열에 Supplementary PUA(서로게이트 페어) 또는 BMP 글리프 교정 대상이 있을 때만 치환. */
    public static String sanitize(String s) {
        if (s == null || s.isEmpty()) return s;
        boolean needFix = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // Supplementary PUA(서로게이트 상위) 또는 BMP 교정 대상(◊ U+25CA 등)
            if ((c >= 0xD800 && c <= 0xDBFF) || mapBmp(c) != null) { needFix = true; break; }
        }
        if (!needFix) return s; // 일반 텍스트 — 변경 없음

        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int n = Character.charCount(cp);
            String bmp;
            if (cp >= 0xF0000 && cp <= 0xFFFFD) {
                String mapped = map(cp);
                if (mapped != null) {
                    b.append(mapped);
                } else {
                    // task11(D): 매핑 없는 PUA 를 제거하면 해당 기호가 "아예 안 보이고" 단락이
                    // 깨진다. drop 금지 → 원문 코드포인트 그대로 통과(passthrough). 글리프가 없으면
                    // 폰트 폴백으로 렌더되며, 적어도 문자가 사라지지 않는다.
                    b.appendCodePoint(cp);
                }
            } else if (n == 1 && (bmp = mapBmp((char) cp)) != null) {
                b.append(bmp);
            } else {
                b.appendCodePoint(cp);
            }
            i += n;
        }
        return b.toString();
    }

    /**
     * BMP 글리프 교정. 한컴 전용폰트가 ◊(U+25CA LOZENGE)에 흰 마름모(◇) 글리프를 배정해
     * 한컴에서는 ◇ 로 보이나, 원본 코드포인트는 U+25CA 라 LibreOffice 등 표준 폰트에서
     * 좁은 마름모 ◊ 로 렌더된다. 한컴 시각과 맞추기 위해 ◇(U+25C7 WHITE DIAMOND)로 교정한다.
     */
    private static String mapBmp(char c) {
        switch (c) {
            case '◊': return "◇"; // ◊ → ◇
            default: return null;
        }
    }

    private static String map(int cp) {
        switch (cp) {
            // 화살표 (HWP PUA-A 0xF003A~0xF003D)
            case 0xF003A: return "←"; // ←
            case 0xF003B: return "↓"; // ↓
            case 0xF003C: return "↑"; // ↑
            case 0xF003D: return "→"; // →

            // 도장/인장 → ㊞ (U+329E CIRCLED IDEOGRAPH SEAL)
            case 0xF00E0: return "㊞";
            case 0xF00E1: return "㊞";

            // 원문자 ①~⑳ (HWP PUA-A 0xF02B1 base)
            case 0xF02B1: return "①"; // ①
            case 0xF02B2: return "②";
            case 0xF02B3: return "③";
            case 0xF02B4: return "④";
            case 0xF02B5: return "⑤";
            case 0xF02B6: return "⑥";
            case 0xF02B7: return "⑦";
            case 0xF02B8: return "⑧";
            case 0xF02B9: return "⑨";
            case 0xF02BA: return "⑩"; // ⑩
            case 0xF02BB: return "⑪";
            case 0xF02BC: return "⑫";
            case 0xF02BD: return "⑬";
            case 0xF02BE: return "⑭";
            case 0xF02BF: return "⑮";
            case 0xF02C0: return "⑯";
            case 0xF02C1: return "⑰";
            case 0xF02C2: return "⑱";
            case 0xF02C3: return "⑲";
            case 0xF02C4: return "⑳"; // ⑳
            default: return null;
        }
    }
}
