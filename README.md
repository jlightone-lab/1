# HWPX → HWP 일괄 변환 도구 (v1.0)

기존 `HWP → HWPX 변환기 v1.0 (hidori)`와 같은 UI·동작 방식으로, 변환 방향만 **HWPX → HWP**로 바꾼 버전입니다.

## 기능
- HWPX 파일 여러 개 선택 / 폴더(하위 폴더 포함)에서 자동 수집
- 한글 프로그램 COM 자동화(`HWPFrame.HwpObject`)로 HWP 형식 저장
- 원본과 같은 폴더에 같은 이름의 `.hwp` 생성, 같은 이름 HWP가 있으면 덮어쓰기
- **변환 성공 시 원본 HWPX 삭제** (실패한 파일은 원본 유지)
- SecurityModule 등록 + 임시폴더 복사 방식 (접근 허용 팝업 최소화)

## 요구 사항
- Windows + 한글(한컴오피스) 설치
- 소스로 실행 시: Python 3.10+ 및 `python -m pip install pywin32`

## 실행
```
python hwpx_to_hwp_converter_v1_0.py
```

## exe 빌드
- Windows에서 `build.bat` 실행 → `dist\HWPX_to_HWP_converter_v1_0.exe`
- 또는 GitHub Actions의 **Build Windows EXE** 워크플로 실행 결과(Artifacts)에서 다운로드
