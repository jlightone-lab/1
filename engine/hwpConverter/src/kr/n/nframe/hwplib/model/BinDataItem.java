package kr.n.nframe.hwplib.model;

public class BinDataItem {
    public int type; // 0=LINK, 1=EMBEDDING, 2=STORAGE
    public String absolutePath;
    public String relativePath;
    public int binDataId;
    public String extension;
    public byte[] data; // EMBEDDING용 원본 이미지 바이트
}
