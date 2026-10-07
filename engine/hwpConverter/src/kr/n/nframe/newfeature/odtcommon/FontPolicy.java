package kr.n.nframe.newfeature.odtcommon;

import java.util.Locale;

/**
 * 폰트 누락 대응 정책(v16t33 task1/2).
 *
 * <p>증상: 원본 한컴 전용폰트(예: 휴먼명조)가 LibreOffice/대상 환경에 미설치 →
 * 시스템 기본폰트(더 큰 메트릭)로 대체 렌더되어 글자가 과대·과굵게 보임.
 *
 * <p>두 단계 대응(서연 리서치-0605):
 * <ol>
 *   <li><b>이름 매핑</b> {@link #mapName}: 알려진 미설치 한컴폰트 소수를 범용 표준폰트로 치환
 *       (휴먼명조→바탕 — PANOSE 동일·Windows 표준). 파일별 하드코딩이 아닌 폰트명 기준 일반 규칙.</li>
 *   <li><b>generic-family 안전망</b>: 매핑되지 않은 폰트도 명조계열→roman / 고딕계열→swiss 로
 *       분류해 style:font-face 에 fo:font-family-generic·style:font-pitch·svg:panose-1·
 *       svg:font-family 대체 체인을 부여. 이게 없어서 미설치 폰트가 큰 대체폰트로 떨어졌다.</li>
 * </ol>
 * pt/charPr 값은 일절 건드리지 않는다(폰트 패밀리 차원의 교정).
 */
public final class FontPolicy {

    private FontPolicy() {}

    /** 알려진 미설치 한컴 전용폰트 → 범용 표준폰트. 그 외는 이름 보존(안전망이 처리). */
    public static String mapName(String name) {
        if (name == null) return null;
        switch (name.trim()) {
            case "휴먼명조":
            case "휴먼세명조":
            case "한양신명조":
            case "한양견명조":
            case "HY신명조":
                return "바탕";
            case "한양중고딕":
            case "HY견고딕":
            case "HY헤드라인M":
                return "돋움";
            default:
                return name;
        }
    }

    /** 고딕/돋움/굴림 계열(sans). */
    public static boolean isSans(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return name.contains("고딕") || name.contains("돋움") || name.contains("돋음")
            || name.contains("굴림") || name.contains("헤드라인")
            || n.contains("gothic") || n.contains("dotum") || n.contains("gulim")
            || n.contains("sans");
    }

    /** 명조/바탕/궁서 계열(serif). */
    public static boolean isSerif(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return name.contains("명조") || name.contains("바탕") || name.contains("궁서")
            || n.contains("batang") || n.contains("myeongjo") || n.contains("gungsuh")
            || n.contains("serif");
    }

    /** ODF fo:font-family-generic. 미상은 한글 본문 기본인 roman. */
    public static String genericFamily(String name) {
        if (isSans(name)) return "swiss";
        return "roman";
    }

    /** ODF style:font-pitch — 한글 폰트는 비례폭(variable). */
    public static String pitch(String name) {
        return "variable";
    }

    /** svg:panose-1 — 계열 대표값(LibreOffice 지능형 대체 유도). */
    public static String panose(String name) {
        return isSans(name) ? "2 11 6 0 0 0 0 0 0 0"  // sans/gothic
                            : "2 2 6 0 0 0 0 0 0 0";   // serif/mincho
    }

    /** svg:font-family 대체 체인(설치 폰트 우선 선택 유도). 작은따옴표 포함, 중복 제거. */
    public static String fontFamilyChain(String name) {
        String[] tail = isSans(name)
                ? new String[]{"돋움", "Dotum", "맑은 고딕", "함초롬돋움"}
                : new String[]{"바탕", "Batang", "함초롬바탕"};
        StringBuilder sb = new StringBuilder("'").append(name).append("'");
        for (String t : tail) {
            if (!t.equals(name)) sb.append(",'").append(t).append("'");
        }
        return sb.toString();
    }
}
