package kr.n.nframe.test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/**
 * Debug helper: extract the long r3c0 cell text from test1.md and trace
 * computeLineStarts char-by-char to find why N chars per paragraph differs
 * from the (charVisualWidth × N ≤ innerW) bound.
 *
 *   java kr.n.nframe.test.WrapDebug <md-path>
 */
public class WrapDebug {

    public static void main(String[] args) throws Exception {
        String md = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(args[0])), StandardCharsets.UTF_8);

        // crude extract: find the long sentence (보조사업자)
        int idx = md.indexOf("(보조사업자) 회원");
        if (idx < 0) { System.out.println("not found"); return; }
        // back to start of cell text by finding nearest "<td"
        int tdStart = md.lastIndexOf(">", idx) + 1;
        int tdEnd   = md.indexOf("</td>", idx);
        String cellInner = md.substring(tdStart, tdEnd);

        // strip HTML tags inside cell
        cellInner = cellInner.replaceAll("<[^>]+>", "");
        // unescape common entities
        cellInner = cellInner.replace("&nbsp;", " ").replace("&amp;", "&")
                             .replace("&lt;", "<").replace("&gt;", ">")
                             .replace("&quot;", "\"");
        cellInner = cellInner.trim();

        System.out.println("=== Cell text (" + cellInner.length() + " chars) ===");
        System.out.println(cellInner);
        System.out.println("---");

        int innerW = 41672;
        int CJK = 580, ASC = 290;
        int acc = 0;
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < cellInner.length(); i++) {
            char ch = cellInner.charAt(i);
            int w = charW(ch, CJK, ASC);
            if (acc + w > innerW && acc > 0) {
                System.out.printf("WRAP at i=%d (acc=%d, ch='%c' U+%04X w=%d)%n",
                        i, acc, ch, (int)ch, w);
                starts.add(i);
                acc = w;
            } else {
                acc += w;
            }
        }
        System.out.println("Final acc=" + acc + " (last line)");
        System.out.println("lineStarts (charW CJK="+CJK+", ASC="+ASC+", innerW="+innerW+"): " + starts);
        System.out.println("paragraphs would have chars: ");
        for (int i = 0; i < starts.size(); i++) {
            int s = starts.get(i);
            int e = (i+1 < starts.size()) ? starts.get(i+1) : cellInner.length();
            int sumW = 0;
            for (int k = s; k < e; k++) sumW += charW(cellInner.charAt(k), CJK, ASC);
            System.out.printf("  para[%d]: %d chars, sum width=%d (innerW=%d, %.1f%%)%n",
                    i, e - s, sumW, innerW, 100.0 * sumW / innerW);
        }
    }

    static int charW(char ch, int CJK, int ASC) {
        if (ch >= 0xAC00 && ch <= 0xD7A3) return CJK;
        if (ch >= 0x4E00 && ch <= 0x9FFF) return CJK;
        if (ch >= 0x3000 && ch <= 0x303F) return CJK;
        if (ch >= 0xFF00 && ch <= 0xFFEF) return CJK;
        if (ch >= 0x2000 && ch <= 0x27BF) return CJK;
        if (ch >= 0x3200 && ch <= 0x33FF) return CJK;
        return ASC;
    }
}
