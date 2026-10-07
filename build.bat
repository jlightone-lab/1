@echo off
chcp 65001 > nul
REM HWPX -> HWP 변환기 exe 빌드 (Windows 전용)
python -m pip install --upgrade pip
python -m pip install pywin32 pyinstaller
python -m PyInstaller --noconfirm --onefile --windowed ^
  --name "HWPX_to_HWP_converter_v1_0" ^
  --hidden-import pythoncom --hidden-import pywintypes --hidden-import win32com.client ^
  hwpx_to_hwp_converter_v1_0.py
echo.
echo 빌드 완료: dist\HWPX_to_HWP_converter_v1_0.exe
pause
