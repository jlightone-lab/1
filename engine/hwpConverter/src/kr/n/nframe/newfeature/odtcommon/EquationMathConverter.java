package kr.n.nframe.newfeature.odtcommon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HWP 수식 스크립트 → OpenDocument Formula(ODF Math) 서브문서 content.xml(Presentation MathML).
 *
 * <p>한컴 원본과 동일하게 "진짜 수식 객체"로 렌더되도록, 스크립트를 토큰 단위로 파싱해
 * 표현 MathML(&lt;mn&gt;/&lt;mi&gt;/&lt;mo&gt;/&lt;mfrac&gt;/&lt;msup&gt; 등)을 생성한다.
 * 문서별 하드코딩 없이 일반화: 숫자→mn, 식별자/한글→mi, 연산자(× · ÷ + - = 등)→mo,
 * HWP 키워드(times/divide/over/sqrt/sum 등)→대응 MathML.
 *
 * <p>알 수 없는 토큰은 그대로 mi 로 보존하므로 다른 문서의 수식도 동일 규칙으로 변환된다.
 */
public final class EquationMathConverter {
    private EquationMathConverter() {}

    private static final String XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

    /** HWP 수식 키워드 → MathML 연산자 기호. 단어 토큰일 때만 적용. */
    private static final Map<String, String> OP_WORDS = new HashMap<>();
    /** 단독 기호로 쓰이는 키워드(연산자가 아닌 식별자성). */
    private static final Map<String, String> SYM_WORDS = new HashMap<>();
    static {
        OP_WORDS.put("times", "×");   // ×
        OP_WORDS.put("cdot", "·");    // ·
        OP_WORDS.put("divide", "÷");  // ÷
        OP_WORDS.put("div", "÷");     // ÷
        OP_WORDS.put("pm", "±");      // ±
        OP_WORDS.put("mp", "∓");      // ∓
        OP_WORDS.put("leq", "≤");     // ≤
        OP_WORDS.put("le", "≤");      // ≤
        OP_WORDS.put("geq", "≥");     // ≥
        OP_WORDS.put("ge", "≥");      // ≥
        OP_WORDS.put("neq", "≠");     // ≠
        OP_WORDS.put("ne", "≠");      // ≠
        OP_WORDS.put("sum", "Σ");     // Σ
        OP_WORDS.put("prod", "Π");    // Π
        OP_WORDS.put("int", "∫");     // ∫

        SYM_WORDS.put("infty", "∞");  // ∞
        SYM_WORDS.put("inf", "∞");    // ∞
        SYM_WORDS.put("alpha", "α");
        SYM_WORDS.put("beta", "β");
        SYM_WORDS.put("gamma", "γ");
        SYM_WORDS.put("delta", "δ");
        SYM_WORDS.put("theta", "θ");
        SYM_WORDS.put("pi", "π");
        SYM_WORDS.put("sigma", "σ");
        SYM_WORDS.put("mu", "μ");
        SYM_WORDS.put("lambda", "λ");
    }

    /**
     * HWP 수식 스크립트 → 완전한 OpenDocument Formula content.xml 문자열.
     * @param script HWP EQEdit 스크립트(예: "15,000 times 240 = ?")
     * @return content.xml(math:math 포함). 스크립트가 비면 null.
     */
    public static String toFormulaContentXml(String script) {
        String mathml = toMathMlInner(script);
        if (mathml == null) return null;
        StringBuilder sb = new StringBuilder(256 + mathml.length());
        sb.append(XML_DECL);
        // math:math 는 OpenDocument 가 임베드하는 MathML 루트.
        sb.append("<math:math xmlns:math=\"http://www.w3.org/1998/Math/MathML\">");
        sb.append("<math:semantics>");
        sb.append(mathml);
        sb.append("</math:semantics>");
        sb.append("</math:math>");
        return sb.toString();
    }

    /** &lt;math:mrow&gt; 래핑된 본문(루트 제외). 비면 null. */
    static String toMathMlInner(String script) {
        if (script == null) return null;
        String s = script.trim();
        if (s.isEmpty()) return null;
        List<String> toks = tokenize(s);
        if (toks.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(128);
        sb.append("<math:mrow>");
        emit(toks, sb);
        sb.append("</math:mrow>");
        return sb.toString();
    }

    /**
     * 토큰화: 공백, 숫자(콤마·소수점 포함), 영문 키워드, 한글 식별자, 단일 기호로 분리.
     * 중괄호 { } 는 그룹 토큰으로 그대로 흘려보낸다(over/sqrt 인자 처리용).
     */
    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (isNumberStart(c)) {
                int j = i + 1;
                while (j < n && isNumberPart(s.charAt(j))) j++;
                out.add(s.substring(i, j));
                i = j;
                continue;
            }
            if (isAsciiLetter(c)) {
                int j = i + 1;
                while (j < n && isAsciiLetter(s.charAt(j))) j++;
                out.add(s.substring(i, j));
                i = j;
                continue;
            }
            if (isHangul(c)) {
                int j = i + 1;
                while (j < n && (isHangul(s.charAt(j)) || isAsciiLetter(s.charAt(j)))) j++;
                out.add(s.substring(i, j));
                i = j;
                continue;
            }
            // 단일 기호 토큰(괄호/연산자/중괄호/캐럿/언더스코어 등)
            out.add(String.valueOf(c));
            i++;
        }
        return out;
    }

    private static boolean isNumberStart(char c) { return c >= '0' && c <= '9'; }
    private static boolean isNumberPart(char c) {
        return (c >= '0' && c <= '9') || c == ',' || c == '.';
    }
    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
    private static boolean isHangul(char c) {
        return (c >= '가' && c <= '힣')      // 한글 음절
            || (c >= 'ᄀ' && c <= 'ᇿ')      // 한글 자모
            || (c >= '㄰' && c <= '㆏');      // 호환 자모
    }

    /** 토큰열 → MathML 자식 요소들을 sb 에 발화. over/sqrt/^/_ 의 단순 처리 포함. */
    private static void emit(List<String> toks, StringBuilder sb) {
        int n = toks.size();
        for (int i = 0; i < n; i++) {
            String t = toks.get(i);
            if (t.isEmpty()) continue;

            // 분수: A over B  → <mfrac>
            if (i + 2 < n && "over".equalsIgnoreCase(toks.get(i + 1))) {
                sb.append("<math:mfrac>");
                emitOne(toks.get(i), sb);
                emitOne(toks.get(i + 2), sb);
                sb.append("</math:mfrac>");
                i += 2;
                continue;
            }
            // 위첨자: A ^ B  → <msup>
            if (i + 2 < n && "^".equals(toks.get(i + 1))) {
                sb.append("<math:msup>");
                emitOne(toks.get(i), sb);
                emitOne(toks.get(i + 2), sb);
                sb.append("</math:msup>");
                i += 2;
                continue;
            }
            // 아래첨자: A _ B  → <msub>
            if (i + 2 < n && "_".equals(toks.get(i + 1))) {
                sb.append("<math:msub>");
                emitOne(toks.get(i), sb);
                emitOne(toks.get(i + 2), sb);
                sb.append("</math:msub>");
                i += 2;
                continue;
            }
            // 제곱근: sqrt X  → <msqrt>
            if (i + 1 < n && "sqrt".equalsIgnoreCase(t)) {
                sb.append("<math:msqrt>");
                emitOne(toks.get(i + 1), sb);
                sb.append("</math:msqrt>");
                i += 1;
                continue;
            }
            emitOne(t, sb);
        }
    }

    /** 단일 토큰 → 적절한 MathML 리프(mn/mi/mo). 그룹 토큰이면 그대로 통과. */
    private static void emitOne(String t, StringBuilder sb) {
        if (t == null || t.isEmpty()) return;
        char c0 = t.charAt(0);

        // 숫자(콤마·소수점 포함)
        if (c0 >= '0' && c0 <= '9') {
            sb.append("<math:mn>").append(esc(t)).append("</math:mn>");
            return;
        }
        // 영문 단어 키워드
        if (isAsciiLetter(c0) && t.length() >= 1) {
            String low = t.toLowerCase(Locale.ROOT);
            String op = OP_WORDS.get(low);
            if (op != null) {
                sb.append("<math:mo>").append(esc(op)).append("</math:mo>");
                return;
            }
            String sym = SYM_WORDS.get(low);
            if (sym != null) {
                sb.append("<math:mi>").append(esc(sym)).append("</math:mi>");
                return;
            }
            // over/sqrt 등 구조 키워드가 단독으로 남으면 출력하지 않음(emit 에서 소비)
            if ("over".equals(low) || "sqrt".equals(low)) return;
            // SUM/COUNT 등 함수명·일반 식별자
            sb.append("<math:mi>").append(esc(t)).append("</math:mi>");
            return;
        }
        // 한글 식별자(예: 단가)
        if (isHangul(c0)) {
            sb.append("<math:mi>").append(esc(t)).append("</math:mi>");
            return;
        }
        // 단일 기호
        switch (c0) {
            case '+': case '-': case '=': case '<': case '>':
            case '*': case '/': case ':':
                sb.append("<math:mo>").append(esc(t)).append("</math:mo>");
                return;
            case '(': case ')': case '[': case ']':
                sb.append("<math:mo fence=\"true\">").append(esc(t)).append("</math:mo>");
                return;
            case '{': case '}':
                // 그룹 경계는 렌더에서 보이지 않게: 출력 생략
                return;
            case ',':
                sb.append("<math:mo separator=\"true\">,</math:mo>");
                return;
            case '?':
                sb.append("<math:mi>?</math:mi>");
                return;
            case '×': case '÷': case '·': case '±':
            case '≤': case '≥': case '≠':
            case '∑': case 'Σ': case '∏': case '∫':
                sb.append("<math:mo>").append(esc(t)).append("</math:mo>");
                return;
            default:
                // 기타 기호는 연산자로 보존
                sb.append("<math:mo>").append(esc(t)).append("</math:mo>");
        }
    }

    private static String esc(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':  b.append("&amp;");  break;
                case '<':  b.append("&lt;");   break;
                case '>':  b.append("&gt;");   break;
                case '"':  b.append("&quot;"); break;
                case '\'': b.append("&apos;"); break;
                default:
                    if (c == '\t' || c == '\n' || c == '\r' || c >= 0x20) b.append(c);
            }
        }
        return b.toString();
    }
}
