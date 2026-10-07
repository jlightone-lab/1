package kr.n.nframe.newfeature;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import kr.n.nframe.newfeature.OdtDocumentModel.Block;
import kr.n.nframe.newfeature.OdtDocumentModel.EquationBlock;
import kr.n.nframe.newfeature.OdtDocumentModel.HeadingBlock;
import kr.n.nframe.newfeature.OdtDocumentModel.ImageBlock;
import kr.n.nframe.newfeature.OdtDocumentModel.OdtStyle;
import kr.n.nframe.newfeature.OdtDocumentModel.ParagraphBlock;
import kr.n.nframe.newfeature.OdtDocumentModel.Run;
import kr.n.nframe.newfeature.OdtDocumentModel.TableBlock;

/**
 * ODT (ZIP) → {@link OdtDocumentModel} 파서.
 *
 * <p>OdtToMdConverter 의 ZIP/StAX 패턴을 참고했지만 MD 가 아닌 직접 모델을 채운다.
 *   임시 MD 파일을 생성하지 않는다.
 *
 * <h2>처리 범위 (v16.00 초기 버전)</h2>
 * <ul>
 *   <li>styles.xml + content.xml 의 명시/자동 스타일 파싱 (text/paragraph/table-cell properties)</li>
 *   <li>본문 paragraph / heading / span / line-break / tab / s (공백)</li>
 *   <li>표 (table:table → row → cell) — 셀 안에는 paragraph 만 처리</li>
 *   <li>draw:image — href + svg:width/height 만 보관 (실제 비트맵은 ZIP 에서 추출)</li>
 *   <li>master-page header / footer 텍스트 추출</li>
 * </ul>
 *
 * <p>list, math, change-tracking 등은 placeholder 처리 (이후 단계에서 확장).
 */
public final class OdtParser {

    /** ODT 파일을 메모리 모델로 변환한다. */
    public OdtDocumentModel parse(File odtFile) throws IOException {
        OdtDocumentModel model = new OdtDocumentModel();
        String contentXml = null;
        String stylesXml = null;
        long[] totalBytes = { 0L };
        int entryCount = 0;
        try (ZipFile zf = new ZipFile(odtFile)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                SafeZip.checkEntryCount(++entryCount);
                SafeZip.validateEntryName(e.getName());
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name.equals("content.xml")) {
                    contentXml = readEntryAsString(zf, e, totalBytes);
                } else if (name.equals("styles.xml")) {
                    stylesXml = readEntryAsString(zf, e, totalBytes);
                } else if (isImageEntry(name)) {
                    model.images.put(stripPicturesPrefix(name), readEntryAsBytes(zf, e, totalBytes));
                } else if (name.matches("Object\\d+/content\\.xml")) {
                    // v16t45 FIX A-2: 수식 임베드 객체(MathML) — draw:object xlink:href 해석용.
                    model.mathObjects.put(name.substring(0, name.indexOf('/')), readEntryAsString(zf, e, totalBytes));
                }
            }
        }
        if (contentXml == null) {
            throw new IOException("ODT 에 content.xml 이 없습니다: " + odtFile);
        }
        if (stylesXml == null) {
            stylesXml = "<root/>"; // styles.xml 부재 시 안전 처리
        }
        // 명시 스타일 (styles.xml) → 자동 스타일 (content.xml) 순으로 채움.
        parseStylesXml(stylesXml, model);
        parseAutomaticStyles(contentXml, model);
        // v16.00-test15: task2 — text:list-style (글머리 vs 번호) 양쪽 xml 모두에서 수집.
        parseListStyles(stylesXml, model);
        parseListStyles(contentXml, model);
        parseHeaderFooter(stylesXml, model);
        parseBody(contentXml, model);
        return model;
    }

    /**
     * v16.00-test15: text:list-style 정의를 훑어 ordered(번호) / unordered(글머리) 결정.
     *   text:list-level-style-number 가 1개 이상 있으면 ordered=true,
     *   text:list-level-style-bullet 만 있으면 false.
     */
    private void parseListStyles(String xml, OdtDocumentModel model) {
        try {
            XMLStreamReader r = newReader(xml);
            String curListStyle = null;
            Boolean curOrdered = null;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String ln = r.getLocalName();
                    if ("list-style".equals(ln)) {
                        curListStyle = attr(r, "name");
                        curOrdered   = null;
                    } else if (curListStyle != null) {
                        if ("list-level-style-number".equals(ln)) {
                            curOrdered = Boolean.TRUE;
                        } else if ("list-level-style-bullet".equals(ln) && curOrdered == null) {
                            curOrdered = Boolean.FALSE;
                        }
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    if ("list-style".equals(r.getLocalName()) && curListStyle != null) {
                        model.listStyleOrdered.put(curListStyle,
                                curOrdered == null ? Boolean.FALSE : curOrdered);
                        curListStyle = null;
                    }
                }
            }
            r.close();
        } catch (XMLStreamException ignore) { /* 부재 시 빈 맵 */ }
    }

    // ========================================================================
    //  스타일 파싱
    // ========================================================================

    private void parseStylesXml(String xml, OdtDocumentModel model) {
        parseAnyStyleBlock(xml, model);
    }

    private void parseAutomaticStyles(String xml, OdtDocumentModel model) {
        parseAnyStyleBlock(xml, model);
    }

    /**
     * styles.xml / content.xml 어느 쪽이든 {@code <style:style>} 정의를
     *   훑어 OdtStyle 로 채운다 (중복 키는 나중 것이 우선).
     */
    private void parseAnyStyleBlock(String xml, OdtDocumentModel model) {
        try {
            XMLStreamReader r = newReader(xml);
            OdtStyle cur = null;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String ln = r.getLocalName();
                    if ("style".equals(ln)) {
                        String name = attr(r, "name");
                        if (name == null || name.isEmpty()) { cur = null; continue; }
                        cur = new OdtStyle(name);
                        cur.displayName = attr(r, "display-name");
                        cur.family      = attr(r, "family");
                        cur.parentName  = attr(r, "parent-style-name");
                        model.styles.put(name, cur);
                    } else if (cur != null && "text-properties".equals(ln)) {
                        cur.textColor             = nullIfEmpty(attr(r, "color"));
                        cur.textBackgroundColor   = nullIfEmpty(attr(r, "background-color"));
                        String fw = attr(r, "font-weight");
                        if (fw != null) cur.bold = "bold".equalsIgnoreCase(fw) || hasIntWeight(fw);
                        String fs = attr(r, "font-style");
                        if (fs != null) cur.italic = "italic".equalsIgnoreCase(fs) || "oblique".equalsIgnoreCase(fs);
                        String ul = attr(r, "text-underline-style");
                        if (ul != null) cur.underline = !"none".equalsIgnoreCase(ul);
                        String stk = attr(r, "text-line-through-style");
                        if (stk != null) cur.strike = !"none".equalsIgnoreCase(stk);
                        // v16t52 T1-폰트: ODT 폰트 참조는 fo:font-family 가 아닌 style:font-name 표준
                        //   (font-face 별도 정의·name 으로 참조). font-family 우선, 없으면 font-name 캡처.
                        cur.fontFamily      = nullIfEmpty(attr(r, "font-family"));
                        if (cur.fontFamily == null) cur.fontFamily = nullIfEmpty(attr(r, "font-name"));
                        cur.fontFamilyAsian = nullIfEmpty(attr(r, "font-family-asian"));
                        if (cur.fontFamilyAsian == null) cur.fontFamilyAsian = nullIfEmpty(attr(r, "font-name-asian"));
                        cur.fontSize        = nullIfEmpty(attr(r, "font-size"));
                        // v16t45 FIX E: 자간 (fo:letter-spacing 절대길이) — CharShape spacing 역환산용.
                        cur.letterSpacing   = nullIfEmpty(attr(r, "letter-spacing"));
                    } else if (cur != null && "paragraph-properties".equals(ln)) {
                        cur.textAlign    = nullIfEmpty(attr(r, "text-align"));
                        cur.borderBottom = nullIfEmpty(attr(r, "border-bottom"));
                        cur.borderTop    = nullIfEmpty(attr(r, "border-top"));
                        cur.borderLeft   = nullIfEmpty(attr(r, "border-left"));
                        cur.borderRight  = nullIfEmpty(attr(r, "border-right"));
                        cur.marginTop    = nullIfEmpty(attr(r, "margin-top"));
                        cur.marginBottom = nullIfEmpty(attr(r, "margin-bottom"));
                        cur.paragraphBackgroundColor = nullIfEmpty(attr(r, "background-color"));
                        // v16t52 P2 T6-b: fo:break-before="page" 추출 — golden 페이지 나눔 시그널 보존
                        String bb = nullIfEmpty(attr(r, "break-before"));
                        if (bb != null && "page".equals(bb)) cur.breakBeforePage = Boolean.TRUE;
                        cur.marginLeft   = nullIfEmpty(attr(r, "margin-left"));
                        cur.marginRight  = nullIfEmpty(attr(r, "margin-right"));
                        cur.textIndent   = nullIfEmpty(attr(r, "text-indent"));
                        String border    = nullIfEmpty(attr(r, "border"));
                        if (border != null) {
                            // shorthand : 4면 모두 동일 — bottom 만 비어있으면 채워둠
                            if (cur.borderBottom == null) cur.borderBottom = border;
                            if (cur.borderTop == null) cur.borderTop = border;
                            if (cur.borderLeft == null) cur.borderLeft = border;
                            if (cur.borderRight == null) cur.borderRight = border;
                        }
                    } else if (cur != null && "table-cell-properties".equals(ln)) {
                        cur.cellBackgroundColor = nullIfEmpty(attr(r, "background-color"));
                        cur.cellPadding         = nullIfEmpty(attr(r, "padding"));
                        cur.cellBorder          = nullIfEmpty(attr(r, "border"));
                        cur.cellVerticalAlign   = nullIfEmpty(attr(r, "vertical-align"));
                        // v16.66: ODT 셀 per-side 테두리(fo:border-top/-bottom/-left/-right) 캡처.
                        //   table-cell 자동스타일은 독립 OdtStyle 이라 borderTop/Bottom/Left/Right 재사용 안전.
                        //   shorthand(fo:border) 있고 특정 변 미명시면 그 변을 shorthand 로 채움
                        //   (paragraph-properties 패턴 동일). 4면 모두 미명시면 필드는 null 로 남아
                        //   빌더가 종전 폴백(4면 SOLID)을 그대로 사용 → byte 불변.
                        cur.borderTop    = nullIfEmpty(attr(r, "border-top"));
                        cur.borderBottom = nullIfEmpty(attr(r, "border-bottom"));
                        cur.borderLeft   = nullIfEmpty(attr(r, "border-left"));
                        cur.borderRight  = nullIfEmpty(attr(r, "border-right"));
                        if (cur.cellBorder != null) {
                            if (cur.borderTop == null)    cur.borderTop = cur.cellBorder;
                            if (cur.borderBottom == null) cur.borderBottom = cur.cellBorder;
                            if (cur.borderLeft == null)   cur.borderLeft = cur.cellBorder;
                            if (cur.borderRight == null)  cur.borderRight = cur.cellBorder;
                        }
                    } else if (cur != null && "background-image".equals(ln)) {
                        // v16t51 S3: <style:background-image xlink:href="Pictures/imageN.{ext}" style:repeat="stretch"/>
                        //   는 table-cell-properties 의 자식 요소로 나타난다. 직전 cur 에 cellBackgroundImageHref 부착.
                        cur.cellBackgroundImageHref   = nullIfEmpty(attr(r, "href"));
                        cur.cellBackgroundImageRepeat = nullIfEmpty(attr(r, "repeat"));
                    } else if (cur != null && "table-properties".equals(ln)) {
                        cur.tableWidth = nullIfEmpty(attr(r, "width"));
                    } else if (cur != null && "table-column-properties".equals(ln)) {
                        cur.columnWidth = nullIfEmpty(attr(r, "column-width"));
                    } else if (cur != null && "table-row-properties".equals(ln)) {
                        // v16t45 F-2: 행높이 — style:row-height 우선, 없으면 style:min-row-height.
                        String rh = nullIfEmpty(attr(r, "row-height"));
                        if (rh == null) rh = nullIfEmpty(attr(r, "min-row-height"));
                        cur.rowHeight = rh;
                    } else if (cur != null && "tab-stops".equals(ln)) {
                        // v16t46 FIX D: <style:tab-stops> — 빈 요소도 '명시적 탭 제거'라 빈 리스트로 시작.
                        cur.tabStops = new java.util.ArrayList<>();
                    } else if (cur != null && "tab-stop".equals(ln)) {
                        if (cur.tabStops == null) cur.tabStops = new java.util.ArrayList<>();
                        OdtDocumentModel.OdtStyle.TabStop ts = new OdtDocumentModel.OdtStyle.TabStop();
                        ts.positionSpec = nullIfEmpty(attr(r, "position"));
                        ts.type         = nullIfEmpty(attr(r, "type"));
                        ts.leaderStyle  = nullIfEmpty(attr(r, "leader-style"));
                        ts.leaderText   = nullIfEmpty(attr(r, "leader-text"));
                        cur.tabStops.add(ts);
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    if ("style".equals(r.getLocalName())) cur = null;
                }
            }
            r.close();
        } catch (XMLStreamException e) {
            throw new RuntimeException("style XML 파싱 실패", e);
        }
    }

    // ========================================================================
    //  머리/꼬리말 (master-page)
    // ========================================================================

    private void parseHeaderFooter(String xml, OdtDocumentModel model) {
        try {
            XMLStreamReader r = newReader(xml);
            int mode = 0; // 0=none, 1=header, 2=footer
            int ignoreChars = 0; // v16t52 T4: page-number/page-count 안 placeholder 텍스트 무시 깊이
            StringBuilder buf = new StringBuilder();
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String ln = r.getLocalName();
                    if ("header".equals(ln)) { mode = 1; buf.setLength(0); }
                    else if ("footer".equals(ln)) { mode = 2; buf.setLength(0); }
                    // v16t51 S5: 머리글/바닥글의 첫 text:p style-name 캡처 (border-bottom 등 paraStyle 매핑용).
                    else if (mode == 1 && "p".equals(ln) && model.headerParaStyleName == null) {
                        model.headerParaStyleName = nullIfEmpty(attr(r, "style-name"));
                    }
                    else if (mode == 2 && "p".equals(ln) && model.footerParaStyleName == null) {
                        model.footerParaStyleName = nullIfEmpty(attr(r, "style-name"));
                    }
                    // PUA marker for builder — atno 컨트롤로 치환 (U+E000=page-number, U+E001=page-count).
                    else if (mode != 0 && "page-number".equals(ln)) { buf.append(''); ignoreChars++; }
                    else if (mode != 0 && "page-count".equals(ln))  { buf.append(''); ignoreChars++; }
                } else if (ev == XMLStreamConstants.CHARACTERS && mode != 0 && ignoreChars == 0) {
                    buf.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String ln = r.getLocalName();
                    if (ignoreChars > 0 && ("page-number".equals(ln) || "page-count".equals(ln))) {
                        ignoreChars--;
                    }
                    if ("header".equals(ln) && mode == 1) {
                        model.headerText = nullIfEmpty(buf.toString().trim());
                        mode = 0;
                    } else if ("footer".equals(ln) && mode == 2) {
                        model.footerText = nullIfEmpty(buf.toString().trim());
                        if (false) System.err.println("[S6-DBG] footerText length=" + (model.footerText==null?-1:model.footerText.length())
                            + " hasE000=" + (model.footerText!=null && model.footerText.indexOf('')>=0)
                            + " hasE001=" + (model.footerText!=null && model.footerText.indexOf('')>=0));
                        mode = 0;
                    }
                }
            }
            r.close();
        } catch (XMLStreamException ignore) {
            // 머리/꼬리말 부재는 정상 케이스
        }
    }

    // ========================================================================
    //  본문 파싱
    // ========================================================================

    private void parseBody(String xml, OdtDocumentModel model) {
        try {
            XMLStreamReader r = newReader(xml);
            // <office:text> 안으로 들어갈 때까지 스킵
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT && "text".equals(r.getLocalName())
                        && "office".equals(prefix(r))) {
                    readOfficeText(r, model);
                    break;
                }
            }
            r.close();
        } catch (XMLStreamException e) {
            throw new RuntimeException("content.xml 본문 파싱 실패", e);
        }
    }

    private void readOfficeText(XMLStreamReader r, OdtDocumentModel model) throws XMLStreamException {
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String ln = r.getLocalName();
                if ("h".equals(ln))            { model.blocks.add(readHeading(r)); }
                else if ("p".equals(ln))       { readParagraphOrImage(r, model); }
                else if ("table".equals(ln))   { model.blocks.add(readTable(r, model)); }
                // v16.00-test15: task2 — text:list 의 style-name 으로 ordered 여부 판단.
                else if ("list".equals(ln))    { model.blocks.add(readListAsParagraphs(r, model)); }
                else if ("sequence-decls".equals(ln) || "user-field-decls".equals(ln)
                        || "variable-decls".equals(ln) || "tracked-changes".equals(ln)) {
                    skipElement(r);
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT && "text".equals(r.getLocalName())) {
                return;
            }
        }
    }

    /**
     * v16.00-test15: task4/task5 — paragraph 를 읽을 때 자식으로 draw:frame > draw:image 가
     *   있으면 ImageBlock 으로 분리 emit. 동일 paragraph 안의 텍스트 run 은 별도
     *   ParagraphBlock 으로 emit.
     */
    private void readParagraphOrImage(XMLStreamReader r, OdtDocumentModel model) throws XMLStreamException {
        readParagraphOrImageInto(r, model, model.blocks);
    }

    /**
     * v16t45 FIX A/A-2 — readParagraphOrImage 의 sink 일반화 버전.
     *   top-level 은 model.blocks, 표 셀 경로는 cell.content 를 sink 로 전달해
     *   frame 수집 로직(readRunsOrImages+pendingFrames)을 단일화한다.
     *   pendingFrames 의 ImageBlock/EquationBlock 에는 모문단 style-name 을
     *   부여해 빌더가 정렬(textAlign)을 복원할 수 있게 한다.
     */
    private void readParagraphOrImageInto(XMLStreamReader r, OdtDocumentModel model,
                                          java.util.List<Block> blockSink) throws XMLStreamException {
        String styleName = attr(r, "style-name");
        ParagraphBlock p = new ParagraphBlock(styleName);
        // v16.00-test15c — task3: pendingImages 를 generic pendingFrames (ImageBlock 또는
        //   EquationBlock) 로 일반화하여 draw:frame > draw:object > math:math 도 함께 수집.
        java.util.List<Block> pendingFrames = new java.util.ArrayList<>();
        readRunsOrImages(r, "p", p.runs, pendingFrames, model);
        // emit order : 이미지/수식 먼저 — caption 이 같은 paragraph 안에 들어있는 케이스에서
        //   그림(또는 수식) → caption 순으로 본문에 보이도록, 그 다음 텍스트가 있으면
        //   paragraph 를 뒤에 둔다. (단순화 설계: 인라인 placement 는 잃어버리지만 콘텐츠
        //   는 보존되며 캡션 + 수식이 인접한 두 줄로 표시.)
        for (Block fb : pendingFrames) {
            if (fb instanceof ImageBlock)         ((ImageBlock) fb).paragraphStyleName = styleName;
            else if (fb instanceof EquationBlock) ((EquationBlock) fb).paragraphStyleName = styleName;
            blockSink.add(fb);
        }
        if (!p.runs.isEmpty()) {
            blockSink.add(p);
        } else if (pendingFrames.isEmpty()) {
            // 빈 paragraph 도 본문 흐름 유지 위해 추가
            blockSink.add(p);
        }
    }

    /**
     * paragraph 안의 run / draw:frame 을 함께 수집. draw:frame 안에 draw:image 가 있으면
     *   ImageBlock 으로 pendingImages 에 추가하고 텍스트는 sink 에 추가.
     */
    private void readRunsOrImages(XMLStreamReader r, String closeLocalName,
                                   java.util.List<Run> sink,
                                   java.util.List<Block> pendingFrames,
                                   OdtDocumentModel model) throws XMLStreamException {
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.CHARACTERS) {
                String t = r.getText();
                if (t != null && !t.isEmpty()) sink.add(Run.plain(t));
            } else if (ev == XMLStreamConstants.START_ELEMENT) {
                String ln = r.getLocalName();
                if ("frame".equals(ln)) {
                    // v16t45 F-4: text:anchor-type="as-char" 이미지는 텍스트 흐름 내
                    //   인라인 Run 으로 보존 (종전: 문단 앞 별도 블록 분리 → 줄 이탈).
                    //   수식(EquationBlock)·기타 앵커는 종전 pendingFrames 경로 유지.
                    String anchor = attr(r, "anchor-type");
                    if ("as-char".equals(anchor)) {
                        java.util.List<Block> tmp = new java.util.ArrayList<>();
                        readDrawFrame(r, tmp, model);
                        for (Block fb : tmp) {
                            if (fb instanceof ImageBlock) {
                                Run ir = Run.plain("");
                                ir.inlineImage = (ImageBlock) fb;
                                sink.add(ir);
                            } else {
                                pendingFrames.add(fb);
                            }
                        }
                    } else {
                        readDrawFrame(r, pendingFrames, model);
                    }
                } else if ("span".equals(ln)) {
                    String spanStyle = attr(r, "style-name");
                    StringBuilder buf = new StringBuilder();
                    collectSpanText(r, buf);
                    if (buf.length() > 0) sink.add(new Run(buf.toString(), spanStyle));
                } else if ("line-break".equals(ln)) {
                    sink.add(Run.plain("\n"));
                    skipElement(r);
                } else if ("tab".equals(ln)) {
                    sink.add(Run.plain("\t"));
                    skipElement(r);
                } else if ("s".equals(ln)) {
                    int count = parseIntSafe(attr(r, "c"), 1);
                    StringBuilder sp = new StringBuilder();
                    for (int i = 0; i < count; i++) sp.append(' ');
                    sink.add(Run.plain(sp.toString()));
                    skipElement(r);
                } else if ("a".equals(ln)) {
                    // v16t45 FIX B: 하이퍼링크 — 내부 run 들에 xlink:href 부착
                    String aHref = attr(r, "href");
                    int from = sink.size();
                    readRunsOrImages(r, "a", sink, pendingFrames, model);
                    attachLinkHref(sink, from, aHref);
                } else if ("bookmark".equals(ln) || "bookmark-start".equals(ln)) {
                    // v16t45 FIX B: 책갈피 — 위치 마커 run (bookmark-end 는 무시)
                    addBookmarkMarker(sink, attr(r, "name"));
                    skipElement(r);
                } else {
                    skipElement(r);
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT
                    && closeLocalName.equals(r.getLocalName())) {
                return;
            }
        }
    }

    /**
     * draw:frame 한 개를 처리. svg:width/height 를 읽고 자식 종류에 따라 분기:
     * <ul>
     *   <li>draw:image (href 있음) → ImageBlock 한 개를 pendingFrames 에 추가.</li>
     *   <li>draw:object > math:math → MathML 서브트리를 한컴 수식 스크립트로 변환,
     *       EquationBlock 추가.</li>
     *   <li>그 외 자식은 무시.</li>
     * </ul>
     *
     * <p>v16.00-test15c: MathML 처리 추가. {@link MathmlToHwpEquation#convert} 가
     *   math:math 의 END_ELEMENT 까지 소비하므로 그 시점에 depth 도 감소된 상태로
     *   복귀하도록 frame depth 카운팅을 그에 맞게 보정한다.
     */
    private void readDrawFrame(XMLStreamReader r, java.util.List<Block> pendingFrames,
                                OdtDocumentModel model) throws XMLStreamException {
        String widthSpec  = attr(r, "width");
        String heightSpec = attr(r, "height");
        double widthMm  = lengthToMm(widthSpec);
        double heightMm = lengthToMm(heightSpec);
        String href = null;
        String mathScript = null;
        // 자식 탐색
        int depth = 1;
        while (r.hasNext() && depth > 0) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                depth++;
                String ln = r.getLocalName();
                if ("image".equals(ln) && href == null) {
                    href = attr(r, "href");
                } else if ("object".equals(ln) && mathScript == null) {
                    // v16t45 FIX A-2: draw:object xlink:href="./ObjectN" — 수식이 별도 ZIP
                    //   엔트리(ObjectN/content.xml, MathML)로 임베드된 형식(LibreOffice·자체
                    //   정방향 출력 공통). 인라인 math:math 가 아니므로 href 로 풀어 변환.
                    String objHref = attr(r, "href");
                    if (objHref != null) {
                        String key = objHref.startsWith("./") ? objHref.substring(2) : objHref;
                        int sl = key.indexOf('/');
                        if (sl > 0) key = key.substring(0, sl);
                        String objXml = model.mathObjects.get(key);
                        if (objXml != null) mathScript = convertMathObjectXml(objXml);
                    }
                } else if ("math".equals(ln) && mathScript == null) {
                    // MathmlToHwpEquation.convert 는 math 의 END_ELEMENT 까지 소비.
                    //   StAX 깊이는 START 만 ++ 하므로 END 가 발생한 시점에 depth--
                    //   를 직접 해 주어 frame 의 종료 검출이 정확해지도록 한다.
                    try {
                        mathScript = MathmlToHwpEquation.convert(r);
                    } catch (Exception e) {
                        // 변환 실패 — 수식은 건너뛰되 frame 자체 종료 처리는 계속.
                        mathScript = null;
                    }
                    depth--;
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        if (mathScript != null && !mathScript.isEmpty()) {
            pendingFrames.add(new EquationBlock(mathScript, widthMm, heightMm));
            return;
        }
        if (href != null && !href.isEmpty()) {
            // href 키 정규화 — model.images 의 key 는 ZIP 엔트리 이름 그대로 (예: "doc1_pretty1.png").
            //   ODT 의 href 가 "Pictures/foo.png" 면 "Pictures/foo.png", "foo.png" 면 그대로.
            String key = href;
            if (!model.images.containsKey(key)) {
                // 시도 : "./" prefix 제거
                if (key.startsWith("./")) key = key.substring(2);
                if (!model.images.containsKey(key)) {
                    // 마지막 경로 컴포넌트만으로 매칭 시도
                    int slash = key.lastIndexOf('/');
                    String tail = (slash >= 0) ? key.substring(slash + 1) : key;
                    if (model.images.containsKey(tail)) key = tail;
                }
            }
            pendingFrames.add(new ImageBlock(key, widthMm, heightMm));
        }
    }

    /** v16t45 FIX A-2: ObjectN/content.xml(MathML) → 한컴 수식 스크립트. 실패 시 null. */
    private static String convertMathObjectXml(String objXml) {
        try {
            XMLStreamReader mr = newReader(objXml);
            while (mr.hasNext()) {
                if (mr.next() == XMLStreamConstants.START_ELEMENT
                        && "math".equals(mr.getLocalName())) {
                    return MathmlToHwpEquation.convert(mr);
                }
            }
        } catch (Exception ignore) { /* 수식 변환 실패 — frame 자체는 무시 */ }
        return null;
    }

    /** "5.5in" / "150mm" → mm. 실패/빈값 → 0. */
    private static double lengthToMm(String spec) {
        if (spec == null) return 0.0;
        String s = spec.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return 0.0;
        try {
            if (s.endsWith("in")) return Double.parseDouble(s.substring(0, s.length() - 2)) * 25.4;
            if (s.endsWith("mm")) return Double.parseDouble(s.substring(0, s.length() - 2));
            if (s.endsWith("cm")) return Double.parseDouble(s.substring(0, s.length() - 2)) * 10.0;
            if (s.endsWith("pt")) return Double.parseDouble(s.substring(0, s.length() - 2)) / 72.0 * 25.4;
        } catch (NumberFormatException ignored) { /* fall through */ }
        return 0.0;
    }

    private HeadingBlock readHeading(XMLStreamReader r) throws XMLStreamException {
        String styleName = attr(r, "style-name");
        int level = parseIntSafe(attr(r, "outline-level"), 1);
        HeadingBlock h = new HeadingBlock(level, styleName);
        readRunsUntilEnd(r, "h", h.runs);
        return h;
    }

    private ParagraphBlock readParagraph(XMLStreamReader r) throws XMLStreamException {
        String styleName = attr(r, "style-name");
        ParagraphBlock p = new ParagraphBlock(styleName);
        readRunsUntilEnd(r, "p", p.runs);
        return p;
    }

    /**
     * paragraph / heading / cell 안의 run 들을 수집.
     *   {@code closeLocalName} 의 END_ELEMENT 가 오면 반환.
     */
    private void readRunsUntilEnd(XMLStreamReader r, String closeLocalName, java.util.List<Run> sink)
            throws XMLStreamException {
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.CHARACTERS) {
                String t = r.getText();
                if (t != null && !t.isEmpty()) sink.add(Run.plain(t));
            } else if (ev == XMLStreamConstants.START_ELEMENT) {
                String ln = r.getLocalName();
                if ("span".equals(ln)) {
                    String spanStyle = attr(r, "style-name");
                    StringBuilder buf = new StringBuilder();
                    collectSpanText(r, buf);
                    if (buf.length() > 0) sink.add(new Run(buf.toString(), spanStyle));
                } else if ("line-break".equals(ln)) {
                    sink.add(Run.plain("\n"));
                    skipElement(r);
                } else if ("tab".equals(ln)) {
                    sink.add(Run.plain("\t"));
                    skipElement(r);
                } else if ("s".equals(ln)) {
                    int count = parseIntSafe(attr(r, "c"), 1);
                    StringBuilder sp = new StringBuilder();
                    for (int i = 0; i < count; i++) sp.append(' ');
                    sink.add(Run.plain(sp.toString()));
                    skipElement(r);
                } else if ("a".equals(ln)) {
                    // v16t45 FIX B: 하이퍼링크 — 내부 run 들에 xlink:href 부착
                    String aHref = attr(r, "href");
                    int from = sink.size();
                    readRunsUntilEnd(r, "a", sink);
                    attachLinkHref(sink, from, aHref);
                } else if ("bookmark".equals(ln) || "bookmark-start".equals(ln)) {
                    // v16t45 FIX B: 책갈피 — 위치 마커 run (bookmark-end 는 무시)
                    addBookmarkMarker(sink, attr(r, "name"));
                    skipElement(r);
                } else {
                    skipElement(r);
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT
                    && closeLocalName.equals(r.getLocalName())) {
                return;
            }
        }
    }

    private void collectSpanText(XMLStreamReader r, StringBuilder buf) throws XMLStreamException {
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.CHARACTERS) {
                buf.append(r.getText());
            } else if (ev == XMLStreamConstants.START_ELEMENT) {
                String ln = r.getLocalName();
                if ("s".equals(ln)) {
                    int count = parseIntSafe(attr(r, "c"), 1);
                    for (int i = 0; i < count; i++) buf.append(' ');
                    skipElement(r);
                } else if ("tab".equals(ln))        { buf.append('\t'); skipElement(r); }
                else if ("line-break".equals(ln))   { buf.append('\n'); skipElement(r); }
                else                                { skipElement(r); }
            } else if (ev == XMLStreamConstants.END_ELEMENT && "span".equals(r.getLocalName())) {
                return;
            }
        }
    }

    private TableBlock readTable(XMLStreamReader r, OdtDocumentModel model) throws XMLStreamException {
        String tableStyle = attr(r, "style-name");
        TableBlock t = new TableBlock(tableStyle, 0);
        int cols = 0;
        boolean inHeaderRows = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String ln = r.getLocalName();
                if ("table-column".equals(ln)) {
                    int rep = parseIntSafe(attr(r, "number-columns-repeated"), 1);
                    String colStyleName = attr(r, "style-name");
                    String cw = null;
                    if (colStyleName != null) {
                        OdtStyle cs = model.styles.get(colStyleName);
                        if (cs != null) cw = cs.columnWidth;
                    }
                    for (int i = 0; i < rep; i++) t.columnWidthSpecs.add(cw);
                    cols += rep;
                    skipElement(r);
                } else if ("table-header-rows".equals(ln)) {
                    inHeaderRows = true;
                } else if ("table-row".equals(ln)) {
                    t.rows.add(readTableRow(r, inHeaderRows, model));
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                String ln = r.getLocalName();
                if ("table-header-rows".equals(ln)) inHeaderRows = false;
                else if ("table".equals(ln)) break;
            }
        }
        // columnCount 갱신 (final 이라 못함 — 새 인스턴스로 교체)
        TableBlock fixed = new TableBlock(t.tableStyleName, Math.max(cols, t.rows.isEmpty() ? 0 : t.rows.get(0).cells.size()));
        fixed.rows.addAll(t.rows);
        fixed.columnWidthSpecs.addAll(t.columnWidthSpecs);
        // table style 로부터 widthSpec lookup
        if (tableStyle != null) {
            OdtStyle ts = model.styles.get(tableStyle);
            if (ts != null) fixed.widthSpec = ts.tableWidth;
        }
        return fixed;
    }

    private TableBlock.Row readTableRow(XMLStreamReader r, boolean header, OdtDocumentModel model) throws XMLStreamException {
        TableBlock.Row row = new TableBlock.Row(header);
        // v16t45 F-2: table-row style-name → row-height 스펙 수집 (미지정 시 null 유지).
        String rowStyleName = attr(r, "style-name");
        if (rowStyleName != null) {
            OdtStyle rs = model.styles.get(rowStyleName);
            if (rs != null) row.heightSpec = rs.rowHeight;
        }
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String ln = r.getLocalName();
                if ("table-cell".equals(ln)) {
                    row.cells.add(readTableCell(r, model));
                } else if ("covered-table-cell".equals(ln)) {
                    skipElement(r); // colSpan/rowSpan 으로 처리된 잔여 셀
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT && "table-row".equals(r.getLocalName())) {
                return row;
            }
        }
        return row;
    }

    private TableBlock.Cell readTableCell(XMLStreamReader r, OdtDocumentModel model) throws XMLStreamException {
        String styleName = attr(r, "style-name");
        int colSpan = parseIntSafe(attr(r, "number-columns-spanned"), 1);
        int rowSpan = parseIntSafe(attr(r, "number-rows-spanned"), 1);
        TableBlock.Cell cell = new TableBlock.Cell(styleName, colSpan, rowSpan);
        // v16t45 FIX C: table:formula / office:value 캡처 (FIELD_FORMULA 역복원용).
        cell.formulaSpec = attr(r, "formula");
        cell.officeValue = attr(r, "value");
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String ln = r.getLocalName();
                // v16t45 FIX A-2 — 셀 안의 draw:frame(이미지/수식)도 보존: top-level 과
                //   동일한 readParagraphOrImageInto 경로로 cell.content 에 수집.
                if ("p".equals(ln))      { readParagraphOrImageInto(r, model, cell.content); }
                else if ("h".equals(ln)) { cell.content.add(readHeading(r)); }
                // v16.00-test15: task2 — 표 셀 안의 리스트도 ordered 판단 가능하게 model 전달.
                else if ("list".equals(ln)) { cell.content.add(readListAsParagraphs(r, model)); }
                else if ("table".equals(ln)) { cell.content.add(readTable(r, model)); }
                else                     { skipElement(r); }
            } else if (ev == XMLStreamConstants.END_ELEMENT && "table-cell".equals(r.getLocalName())) {
                return cell;
            }
        }
        return cell;
    }

    /**
     * 리스트를 단순화하여 paragraph 시퀀스로 환원.
     *
     * <p>ODT 구조: {@code <text:list><text:list-item><text:p>텍스트</text:p></text:list-item>...}.
     *   각 list-item 의 자식 {@code <text:p>}/{@code <text:h>} 내부 텍스트를 모아 한 paragraph
     *   안에 줄바꿈으로 구분해 담는다. 항목 앞에 bullet 마커("• ") 부착.
     *
     * <p>중첩 리스트도 동일 paragraph 에 평탄화 (depth 추적으로 종료 위치 정확히 잡음).
     */
    private ParagraphBlock readListAsParagraphs(XMLStreamReader r, OdtDocumentModel model) throws XMLStreamException {
        // v16.00-test15: task2 — text:list 의 text:style-name 이 ordered(번호) 인지 확인.
        //   listStyleOrdered 에 등록된 경우 "1. ", "2. " ... 접두사 사용.
        String listStyle = attr(r, "style-name");
        boolean ordered = false;
        if (listStyle != null && model != null) {
            Boolean ord = model.listStyleOrdered.get(listStyle);
            if (Boolean.TRUE.equals(ord)) ordered = true;
        }
        ParagraphBlock p = new ParagraphBlock(null);
        int depth = 1; // 현재 list 진입한 상태
        int itemIdx = 0; // ordered 일 때 번호 카운터 (depth=1 의 list-item 만 카운트)
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String ln = r.getLocalName();
                if ("list".equals(ln)) {
                    depth++;
                } else if ("list-item".equals(ln) || "list-header".equals(ln)) {
                    if (depth == 1 && "list-item".equals(ln)) itemIdx++;
                } else if ("p".equals(ln) || "h".equals(ln)) {
                    if (ordered) {
                        p.runs.add(Run.plain(itemIdx + ". "));
                    } else {
                        p.runs.add(Run.plain("• "));
                    }
                    readRunsUntilEnd(r, ln, p.runs);
                    p.runs.add(Run.plain("\n"));
                } else {
                    skipElement(r);
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                String ln = r.getLocalName();
                if ("list".equals(ln)) {
                    depth--;
                    if (depth == 0) return p;
                }
            }
        }
        return p;
    }

    // ========================================================================
    //  유틸
    // ========================================================================

    private static XMLStreamReader newReader(String xml) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.TRUE);
        f.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        return f.createXMLStreamReader(new StringReader(xml));
    }

    /** v16t45 FIX B: {@code text:a} 가 감싸던(=from 이후 수집된) run 들에 href 부착. */
    private static void attachLinkHref(java.util.List<Run> sink, int from, String href) {
        if (href == null || href.isEmpty()) return;
        for (int i = from; i < sink.size(); i++) sink.get(i).linkHref = href;
    }

    /** v16t45 FIX B: {@code text:bookmark} 위치 마커 run 추가 (이름 없으면 무시). */
    private static void addBookmarkMarker(java.util.List<Run> sink, String name) {
        if (name == null || name.isEmpty()) return;
        Run m = Run.plain("");
        m.bookmarkName = name;
        sink.add(m);
    }

    private static String attr(XMLStreamReader r, String localName) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (localName.equals(r.getAttributeLocalName(i))) return r.getAttributeValue(i);
        }
        return null;
    }

    private static String prefix(XMLStreamReader r) {
        String p = r.getPrefix();
        return p == null ? "" : p;
    }

    private static void skipElement(XMLStreamReader r) throws XMLStreamException {
        int depth = 1;
        while (r.hasNext() && depth > 0) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) depth++;
            else if (ev == XMLStreamConstants.END_ELEMENT) depth--;
        }
    }

    private static String readEntryAsString(ZipFile zf, ZipEntry e, long[] totalBytes) throws IOException {
        return new String(readEntryAsBytes(zf, e, totalBytes), "UTF-8");
    }

    private static byte[] readEntryAsBytes(ZipFile zf, ZipEntry e, long[] totalBytes) throws IOException {
        try (InputStream in = zf.getInputStream(e)) {
            return SafeZip.readEntryBounded(in, e.getName(), e.getSize(), totalBytes);
        }
    }

    private static boolean isImageEntry(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".gif") || n.endsWith(".bmp");
    }

    private static String stripPicturesPrefix(String name) {
        // ODT 는 보통 Pictures/xxx.png 형태. 본문 href 는 "Pictures/..." 또는 짧은 이름 모두 가능.
        return name;
    }

    private static String nullIfEmpty(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static boolean hasIntWeight(String fw) {
        try { return Integer.parseInt(fw) >= 700; } catch (NumberFormatException ignored) { return false; }
    }

    private static int parseIntSafe(String s, int defVal) {
        if (s == null || s.isEmpty()) return defVal;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { return defVal; }
    }

    // 미사용 import 회피용 placeholder — 향후 ImageBlock 확장에서 호출 예정
    @SuppressWarnings("unused")
    private static ImageBlock imageRef(String href, double w, double h) {
        return new ImageBlock(href, w, h);
    }
}
