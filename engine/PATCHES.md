# 내장 변환 엔진 (hwpConverter) 변경 사항

- 원본: https://github.com/vsdn/hwpConverter
- 기준 커밋: `9af63ea5e24f4761351559591a1b35dbdf3c78b3`
- 라이선스: Apache License 2.0 (`hwpConverter/LICENSE`)

원본에서 `src/`, `lib/`, `LICENSE`, `README.md`(→ `README.upstream.md`)만 가져왔습니다.
아래 변경 외에는 원본과 같습니다.

## 1. 새 번호 지정(`nwno`) 컨트롤 수정

HWPX의 `<hp:newNum>`(새 번호로 시작)이 있는 문서를 변환하면 결과 HWP를
다른 HWP 리더(hwplib)가 읽지 못했습니다("This is not paragraph").

- `src/kr/n/nframe/hwplib/reader/SectionParser.java`
  - 본문 확장 문자 코드를 18(자동 번호, `atno` 전용)에서
    **21(쪽 컨트롤)** 로 수정 — HWP 5.0 규격 표 6
- `src/kr/n/nframe/hwplib/writer/SectionWriter.java`
  - 데이터 길이를 자동 번호용 12바이트에서
    **6바이트**(UINT32 속성 + UINT16 번호)로 수정 — HWP 5.0 규격 §4.3.10.6

## 2. 변환·검증 도구 추가 (`../tool/HwpxToHwpTool.java`)

엔진으로 변환한 뒤 결과를 hwplib으로 다시 읽어 본문 글자를 원본 HWPX와 비교합니다.
