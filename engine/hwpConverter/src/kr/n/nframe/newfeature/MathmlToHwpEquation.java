package kr.n.nframe.newfeature;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * MathML(presentation) 의 한 개 {@code <math:math>} 서브트리를 한컴 수식편집기
 * 스크립트(LaTeX 유사 문법)로 변환한다. {@link OdtParser} 가 ODT content.xml 을
 * StAX 로 스트리밍 파싱하다가 {@code math:math} 시작 이벤트를 만나면 본 클래스의
 * {@link #convert(XMLStreamReader)} 를 호출해 종료 이벤트(END_ELEMENT of math:math)
 * 까지 일괄 소비하면서 결과 스크립트 문자열을 반환받는다.
 *
 * <p>지원 요소(v16.00-test15c):
 * <ul>
 *   <li>{@code mrow}    — 자식 결과를 단순 연결.</li>
 *   <li>{@code mi/mn/mtext} — 텍스트 그대로(연산자 매핑 적용).</li>
 *   <li>{@code mo}      — 연산자: {@link #OP_MAP} 으로 매핑(예: ∓ → "-+", ∞ → "inf",
 *                          × → "times", ÷ → "div", ≤ → "<=", ≥ → ">=", ± → "+-").</li>
 *   <li>{@code msup}    — base, sup 두 자식 → "base^{sup}".</li>
 *   <li>{@code msub}    — base, sub 두 자식 → "base_{sub}".</li>
 *   <li>{@code msubsup} — base, sub, sup 세 자식 → "base_{sub}^{sup}". 적분 ∫ 처리에 사용.</li>
 *   <li>{@code munderover} — msubsup 와 동일 매핑.</li>
 *   <li>{@code mfrac}   — num, den → "{num} over {den}".</li>
 *   <li>{@code msqrt}   — child → "sqrt {child}".</li>
 *   <li>{@code mroot}   — base, index → "root {index} of {base}" 의 한컴 변형
 *                          "root {index} of {base}" 사용.</li>
 *   <li>{@code mfenced} — 자식들을 {@code open}/{@code close} 속성(또는 기본 괄호)
 *                          으로 감싸 출력.</li>
 *   <li>그 외 요소는 mrow 와 동일하게 자식만 이어 붙인다.</li>
 * </ul>
 *
 * <p>한컴 수식편집기 문법 메모:
 * <ul>
 *   <li>위첨자/아래첨자: 한 글자면 {@code a^2}, 여러 글자는 반드시 중괄호 {@code a^{2x}}.
 *       안전을 위해 항상 중괄호로 감싼다.</li>
 *   <li>분수: {@code {num} over {den}}.</li>
 *   <li>제곱근: {@code sqrt {x}} (중괄호 필수).</li>
 *   <li>적분 한도: {@code int _{lo} ^{hi}}.</li>
 *   <li>그리스 문자/특수기호는 ASCII 식별자(예: {@code pi}, {@code inf}, {@code alpha}).</li>
 * </ul>
 *
 * <p>본 변환은 손실이 있을 수 있다(예: MathML 의 글꼴 변경 속성은 무시). 그러나
 * 의미가 보존되어 한컴에서 수식편집기로 열어 편집할 수 있도록 함이 목적이다.
 */
public final class MathmlToHwpEquation {

    /** MathML {@code <mo>} 텍스트 → 한컴 토큰. 일치 없으면 원문 그대로. */
    private static final Map<String, String> OP_MAP = new HashMap<>();
    /** MathML {@code <mi>} 텍스트 → 한컴 식별자. 일치 없으면 원문 그대로. */
    private static final Map<String, String> ID_MAP = new HashMap<>();

    static {
        // 산술/관계 — 유니코드 → ASCII
        OP_MAP.put("±", "+-");          // ±
        OP_MAP.put("∓", "-+");          // ∓
        OP_MAP.put("×", "times");       // ×
        OP_MAP.put("÷", "div");         // ÷
        OP_MAP.put("≤", "<=");          // ≤
        OP_MAP.put("≥", ">=");          // ≥
        OP_MAP.put("≠", "!=");          // ≠
        OP_MAP.put("−", "-");           // U+2212 minus sign → ASCII hyphen-minus
        OP_MAP.put("⋅", "cdot");        // ⋅
        OP_MAP.put("…", "...");         // …
        OP_MAP.put("→", "->");          // →
        OP_MAP.put("←", "<-");          // ←

        // 적분/합/극한 — operator 로 등장하는 경우
        OP_MAP.put("∫", "int");         // ∫
        OP_MAP.put("∬", "iint");        // ∬
        OP_MAP.put("∮", "oint");        // ∮
        OP_MAP.put("∑", "sum");         // ∑
        OP_MAP.put("∏", "prod");        // ∏

        // 식별자 — 그리스 문자 (자주 등장하는 것)
        ID_MAP.put("α", "alpha");
        ID_MAP.put("β", "beta");
        ID_MAP.put("γ", "gamma");
        ID_MAP.put("δ", "delta");
        ID_MAP.put("ε", "epsilon");
        ID_MAP.put("θ", "theta");
        ID_MAP.put("λ", "lambda");
        ID_MAP.put("μ", "mu");
        ID_MAP.put("π", "pi");          // π
        ID_MAP.put("ρ", "rho");
        ID_MAP.put("σ", "sigma");
        ID_MAP.put("φ", "phi");
        ID_MAP.put("ψ", "psi");
        ID_MAP.put("ω", "omega");
        ID_MAP.put("Γ", "GAMMA");
        ID_MAP.put("Δ", "DELTA");
        ID_MAP.put("Θ", "THETA");
        ID_MAP.put("Π", "PI");
        ID_MAP.put("Σ", "SIGMA");
        ID_MAP.put("Φ", "PHI");
        ID_MAP.put("Ω", "OMEGA");

        // 무한대/특수
        ID_MAP.put("∞", "inf");         // ∞
        ID_MAP.put("∂", "partial");     // ∂
    }

    /**
     * 현재 reader 가 {@code <math:math>} 의 START_ELEMENT 위치에 있을 때 호출.
     * 종료 이벤트까지 소비하고 한컴 수식 스크립트를 반환한다.
     */
    public static String convert(XMLStreamReader r) throws XMLStreamException {
        // 현재 위치: math:math START_ELEMENT — 자식을 모두 읽어 결과 연결.
        return readChildren(r, "math");
    }

    /**
     * 현재 reader 가 어떤 START_ELEMENT 의 자식 영역에 들어가 있는 상태에서
     * 같은 깊이의 자식 결과를 공백으로 분리하여 모두 합쳐 반환. close 시점은
     * {@code closeLocalName} 의 END_ELEMENT.
     */
    private static String readChildren(XMLStreamReader r, String closeLocalName) throws XMLStreamException {
        StringBuilder buf = new StringBuilder();
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String token = readElement(r);
                if (token != null && !token.isEmpty()) {
                    if (buf.length() > 0) buf.append(' ');
                    buf.append(token);
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if (closeLocalName.equals(r.getLocalName())) {
                    return buf.toString();
                }
            }
            // CHARACTERS in mrow 등은 무시 (MathML 의 텍스트는 mn/mi/mo 안에 있음).
        }
        return buf.toString();
    }

    /** 현재 reader 가 START_ELEMENT 위치에 있을 때 그 요소 전체를 처리해 토큰을 반환. */
    private static String readElement(XMLStreamReader r) throws XMLStreamException {
        String name = r.getLocalName();
        switch (name) {
            case "mn":
            case "mi":
            case "mtext": {
                String text = readTextContent(r, name).trim();
                return mapIdentifier(text);
            }
            case "mo": {
                String text = readTextContent(r, name).trim();
                return mapOperator(text);
            }
            case "msup": {
                String[] kids = readChildTokens(r, "msup", 2);
                return kids[0] + "^{" + kids[1] + "}";
            }
            case "msub": {
                String[] kids = readChildTokens(r, "msub", 2);
                return kids[0] + "_{" + kids[1] + "}";
            }
            case "msubsup":
            case "munderover": {
                String[] kids = readChildTokens(r, name, 3);
                return kids[0] + "_{" + kids[1] + "}^{" + kids[2] + "}";
            }
            case "mfrac": {
                String[] kids = readChildTokens(r, "mfrac", 2);
                return "{" + kids[0] + "} over {" + kids[1] + "}";
            }
            case "msqrt": {
                String inner = readChildren(r, "msqrt");
                return "sqrt {" + inner + "}";
            }
            case "mroot": {
                String[] kids = readChildTokens(r, "mroot", 2);
                return "root {" + kids[1] + "} of {" + kids[0] + "}";
            }
            case "mfenced": {
                String open = attr(r, "open");  if (open == null)  open = "(";
                String close = attr(r, "close"); if (close == null) close = ")";
                String inner = readChildren(r, "mfenced");
                return open + inner + close;
            }
            case "mrow":
            case "mstyle":
            case "math":
            case "mpadded":
            case "mphantom":
            default: {
                // 기본 동작: 자식만 그대로 연결.
                return readChildren(r, name);
            }
        }
    }

    /**
     * {@code n} 개의 자식 요소를 차례로 읽어 토큰 문자열을 배열로 반환.
     * MathML 의 schema-정의 자식 개수보다 적게 들어오면 빈 문자열로 패딩.
     */
    private static String[] readChildTokens(XMLStreamReader r, String closeLocalName, int n) throws XMLStreamException {
        String[] out = new String[n];
        for (int i = 0; i < n; i++) out[i] = "";
        int idx = 0;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String token = readElement(r);
                if (idx < n) out[idx++] = token;
                // 초과 자식은 마지막 슬롯에 공백으로 이어붙인다 (방어적 처리).
                else if (n > 0) out[n - 1] = out[n - 1] + " " + token;
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if (closeLocalName.equals(r.getLocalName())) return out;
            }
        }
        return out;
    }

    /** START_ELEMENT 상태에서 호출 — 자식 CHARACTERS 만 모아 반환. close 시 종료. */
    private static String readTextContent(XMLStreamReader r, String closeLocalName) throws XMLStreamException {
        StringBuilder buf = new StringBuilder();
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.CHARACTERS) {
                buf.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if (closeLocalName.equals(r.getLocalName())) return buf.toString();
            }
            // 중첩 요소는 무시 (mn/mi/mo/mtext 는 보통 단순 텍스트만 포함).
        }
        return buf.toString();
    }

    /** mo 토큰 매핑 — OP_MAP 에 있으면 변환, 없으면 원문(공백 포함 그대로). */
    private static String mapOperator(String s) {
        if (s == null || s.isEmpty()) return "";
        // 유니코드 단일 문자 매핑 우선
        if (s.length() == 1) {
            String mapped = OP_MAP.get(s);
            if (mapped != null) return mapped;
        }
        String mapped = OP_MAP.get(s);
        if (mapped != null) return mapped;
        return s;
    }

    /** mi/mn/mtext 토큰 매핑. */
    private static String mapIdentifier(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.length() == 1) {
            String mapped = ID_MAP.get(s);
            if (mapped != null) return mapped;
        }
        String mapped = ID_MAP.get(s);
        if (mapped != null) return mapped;
        return s;
    }

    private static String attr(XMLStreamReader r, String name) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (name.equals(r.getAttributeLocalName(i))) return r.getAttributeValue(i);
        }
        return null;
    }

    private MathmlToHwpEquation() {}
}
