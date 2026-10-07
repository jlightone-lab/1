package kr.n.nframe.test;

import org.apache.poi.poifs.filesystem.*;
import java.io.*;
import java.util.zip.Inflater;

public class Section0Records {
    static String[] TAG = new String[1024];
    static {
        TAG[0x42] = "PARA_HEADER";
        TAG[0x43] = "PARA_TEXT";
        TAG[0x44] = "PARA_CHAR_SHAPE";
        TAG[0x45] = "PARA_LINE_SEG";
        TAG[0x46] = "PARA_RANGE_TAG";
        TAG[0x47] = "CTRL_HEADER";
        TAG[0x48] = "LIST_HEADER";
        TAG[0x49] = "PAGE_DEF";
        TAG[0x4A] = "FOOTNOTE_SHAPE";
        TAG[0x4B] = "PAGE_BORDER_FILL";
        TAG[0x4C] = "SHAPE_COMPONENT";
        TAG[0x4D] = "TABLE";
        TAG[0x4E] = "SHAPE_COMPONENT_LINE";
        TAG[0x4F] = "SHAPE_COMPONENT_RECTANGLE";
        TAG[0x52] = "SHAPE_COMPONENT_PICTURE";
        TAG[0x53] = "SHAPE_COMPONENT_CONTAINER";
        TAG[0x54] = "CTRL_DATA";
        TAG[0x55] = "EQEDIT";
        TAG[0x57] = "SHAPE_COMPONENT_TEXTART";
        TAG[0x58] = "FORM_OBJECT";
        TAG[0x59] = "MEMO_SHAPE";
        TAG[0x5A] = "MEMO_LIST";
        TAG[0x5B] = "CHART_DATA";
    }

    public static void main(String[] args) throws Exception {
        String src = args[0];
        int max = args.length > 1 ? Integer.parseInt(args[1]) : 50;
        try (POIFSFileSystem poi = new POIFSFileSystem(new FileInputStream(src))) {
            DirectoryEntry root = poi.getRoot();
            DirectoryEntry bt = (DirectoryEntry) root.getEntry("BodyText");
            DocumentEntry s0 = (DocumentEntry) bt.getEntry("Section0");
            byte[] data = new byte[s0.getSize()];
            try (DocumentInputStream is = new DocumentInputStream(s0)) {
                int t = 0; while (t < data.length) { int r = is.read(data, t, data.length-t); if(r<0) break; t+=r; }
            }
            // raw deflate inflate
            Inflater inf = new Inflater(true);
            inf.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024 * 64];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.finished()) break;
                    else throw new RuntimeException("stuck");
                }
                out.write(buf, 0, n);
            }
            inf.end();
            byte[] raw = out.toByteArray();
            System.out.println("inflated size: " + raw.length);

            int pos = 0, recIdx = 0;
            while (pos + 4 <= raw.length && recIdx < max) {
                long h = readU32(raw, pos); pos += 4;
                int tag = (int)(h & 0x3FF);
                int level = (int)((h >> 10) & 0x3FF);
                int size = (int)((h >> 20) & 0xFFF);
                if (size == 0xFFF) {
                    if (pos + 4 > raw.length) break;
                    size = (int) readU32(raw, pos); pos += 4;
                }
                String tname = (tag < TAG.length && TAG[tag] != null) ? TAG[tag] : ("?0x" + Integer.toHexString(tag));
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < Math.min(16, size); i++) hex.append(String.format("%02X ", raw[pos + i]));
                System.out.printf("rec[%d] @0x%05X tag=0x%X (%s) lvl=%d size=%d  %s%n",
                        recIdx, pos-4, tag, tname, level, size, hex.toString().trim());
                pos += size;
                recIdx++;
            }
            System.out.println("...total examined " + recIdx);
        }
    }
    static long readU32(byte[] d, int o) {
        return ((long)(d[o]&0xFF)) | ((long)(d[o+1]&0xFF)<<8) | ((long)(d[o+2]&0xFF)<<16) | ((long)(d[o+3]&0xFF)<<24);
    }
}
