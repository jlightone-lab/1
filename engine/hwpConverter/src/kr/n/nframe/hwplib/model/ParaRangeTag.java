package kr.n.nframe.hwplib.model;

/**
 * HWPTAG_PARA_RANGE_TAG 엔트리 (엔트리당 12 byte).
 * 규격서 표 63 (HWP 5.0 revision 1.3):
 *   UINT32 start       영역 시작 (문단 텍스트 내 WCHAR 위치)
 *   UINT32 end         영역 끝
 *   UINT32 tag         상위 8비트 = 종류 (sort), 하위 24비트 = 종류별 데이터 (data)
 *
 * 한컴 산출 HWP에서 관찰된 알려진 종류:
 *   sort = 0, data = 0         : no-op / 예약된 마커 (한컴이 HWPX → HWP 저장 시
 *                                 하이퍼링크당 3개씩 출력)
 *   sort = 1, data = charShapeId: 해당 영역에 CharShape를 적용
 *                                 (하이퍼링크를 파란색 + 밑줄로 표시할 때 사용)
 */
public class ParaRangeTag {
    public long start;
    public long end;
    public int sort;     // tag 상위 8비트 (0~255)
    public int data;     // tag 하위 24비트 (0~0xFFFFFF)

    public ParaRangeTag() {}

    public ParaRangeTag(long start, long end, int sort, int data) {
        this.start = start;
        this.end = end;
        this.sort = sort;
        this.data = data;
    }
}
