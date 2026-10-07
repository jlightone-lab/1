package kr.n.nframe.test;

import org.apache.poi.poifs.filesystem.*;
import java.io.*;

public class Ole2Streams {
    public static void main(String[] args) throws Exception {
        for (String p : args) {
            System.out.println("===== " + p + " (" + new File(p).length() + " bytes) =====");
            try (POIFSFileSystem poi = new POIFSFileSystem(new FileInputStream(p))) {
                walk(poi.getRoot(), "");
            }
        }
    }

    static void walk(DirectoryEntry dir, String prefix) {
        for (Entry e : dir) {
            if (e instanceof DirectoryEntry) {
                System.out.println(prefix + "[DIR ] " + e.getName());
                walk((DirectoryEntry) e, prefix + "  ");
            } else if (e instanceof DocumentEntry) {
                DocumentEntry d = (DocumentEntry) e;
                System.out.println(prefix + "[FILE] " + e.getName() + "  (" + d.getSize() + " bytes)");
            }
        }
    }
}
