"""
HWPX → HWP 일괄 변환 도구

기능
- HWPX 파일 여러 개를 한 번에 선택
- 폴더 안의 HWPX 파일 자동 수집
- 한글 프로그램 COM 자동화를 이용해 HWP로 변환
- 변환된 HWP는 원본 HWPX와 같은 폴더에 같은 파일명으로 저장
- 변환 성공 시 원본 HWPX 파일 삭제
- 기존 같은 이름의 HWP가 있으면 덮어쓰기
- 변환 실행 1회당 한글 객체를 한 번만 열어 접근 허용 반복 최소화
- HWP→PDF 변환 도구와 동일하게 SecurityModule 등록 + 임시파일 복사 방식 적용

주의
- Windows + 한글 프로그램 설치 환경에서만 동작합니다.
- 실행 전 pywin32 설치가 필요합니다.
    python -m pip install pywin32
"""

import os
import shutil
import tempfile
import threading
from pathlib import Path

import tkinter as tk
from tkinter import ttk, filedialog, messagebox


APP_TITLE = "HWPX → HWP 일괄 변환 도구(v1.0)"
SUPPORTED_EXT = ".hwpx"


def normalize_path(path: str) -> str:
    return os.path.normcase(os.path.abspath(path))


def is_office_temp_file(path: str) -> bool:
    name = os.path.basename(path)
    return name.startswith("~$") or name.startswith(".~")


def is_hwpx_file(path: str) -> bool:
    if not path:
        return False
    if is_office_temp_file(path):
        return False
    name = os.path.basename(path).lower()
    return name.endswith(SUPPORTED_EXT)


def collect_hwpx_files_from_folder(folder_path: str, recursive: bool):
    result = []
    if not os.path.isdir(folder_path):
        return result

    if recursive:
        for current, _, files in os.walk(folder_path):
            for filename in files:
                path = os.path.join(current, filename)
                if is_hwpx_file(path):
                    result.append(path)
    else:
        for filename in os.listdir(folder_path):
            path = os.path.join(folder_path, filename)
            if os.path.isfile(path) and is_hwpx_file(path):
                result.append(path)

    return sorted(result, key=lambda p: normalize_path(p))


def output_hwp_path(src_path: str) -> str:
    """원본 HWPX와 같은 폴더에 같은 파일명으로 HWP 경로 생성"""
    folder = os.path.dirname(os.path.abspath(src_path))
    base_name = os.path.splitext(os.path.basename(src_path))[0] + ".hwp"
    return os.path.join(folder, base_name)


def _ensure_pywin32_available():
    try:
        import pythoncom  # noqa: F401
        import win32com.client  # noqa: F401
    except Exception as exc:
        raise RuntimeError(
            "pywin32가 설치되어 있지 않습니다.\n"
            "명령 프롬프트 또는 PowerShell에서 다음 명령을 먼저 실행하세요.\n\n"
            "python -m pip install pywin32"
        ) from exc


def _try_register_file_path_security_module(hwp):
    """
    한글 오토메이션의 파일 접근 보안승인 팝업을 줄이기 위한 보안모듈 등록 시도.
    기존 HWP→PDF 변환 도구에서 사용한 SecurityModule을 우선 사용합니다.
    """
    module_names = [
        os.environ.get("HWP_FILE_PATH_CHECKER_MODULE", "").strip(),
        "SecurityModule",
        "FilePathCheckerModule",
        "FilePathCheckerModuleExample",
    ]

    tried = []
    last_error = ""

    for module_name in module_names:
        if not module_name or module_name in tried:
            continue
        tried.append(module_name)

        try:
            result = hwp.RegisterModule("FilePathCheckDLL", module_name)
            if bool(result):
                return True, module_name, ""
            last_error = f"RegisterModule 반환값 False 또는 빈 값: {module_name}"
        except Exception as exc:
            last_error = f"{module_name}: {exc}"

    return False, "", last_error


def _create_hwp_object(visible: bool):
    import win32com.client

    try:
        hwp = win32com.client.gencache.EnsureDispatch("HWPFrame.HwpObject")
    except Exception:
        hwp = win32com.client.Dispatch("HWPFrame.HwpObject")

    security_ok, security_module_name, security_error = _try_register_file_path_security_module(hwp)

    try:
        hwp.XHwpWindows.Item(0).Visible = bool(visible)
    except Exception:
        pass

    return hwp, security_ok, security_module_name, security_error


def _close_current_hwp_document(hwp):
    """한글 프로그램은 유지하고 현재 문서만 닫기. 버전별 차이를 고려해 여러 방식 시도."""
    try:
        hwp.Clear(1)
        return
    except Exception:
        pass

    try:
        hwp.HAction.Run("FileClose")
        return
    except Exception:
        pass

    try:
        hwp.HAction.GetDefault("FileClose", hwp.HParameterSet.HFileOpenSave.HSet)
        hwp.HAction.Execute("FileClose", hwp.HParameterSet.HFileOpenSave.HSet)
    except Exception:
        return


def _opened_as_raw_text(hwp) -> bool:
    """HWPX(zip)가 문서가 아닌 일반 텍스트로 열렸는지 확인 (본문이 'PK...mimetype'으로 시작)"""
    try:
        text = hwp.GetTextFile("TEXT", "") or ""
    except Exception:
        return False
    head = text.lstrip()[:200]
    return head.startswith("PK") and "mimetype" in head


def _open_hwpx(hwp, src_path: str):
    errors = []
    # 형식을 비워 두면 일부 한글 버전에서 HWPX를 일반 텍스트로 열어버리므로 "HWPX"를 먼저 지정
    attempts = [
        ("HWPX", ""),
        ("HWPX", "forceopen:true"),
        ("", ""),
        ("", "forceopen:true"),
    ]

    for fmt, option in attempts:
        try:
            result = hwp.Open(src_path, fmt, option)
            if result is False:
                errors.append(f"Open 반환값 False / format={fmt}, option={option}")
                continue
            if _opened_as_raw_text(hwp):
                errors.append(f"문서가 아닌 텍스트로 열림 / format={fmt}, option={option}")
                _close_current_hwp_document(hwp)
                continue
            return
        except Exception as exc:
            errors.append(str(exc))

    raise RuntimeError("HWPX 파일 열기 실패: " + " | ".join(errors[-2:]))


HWP_SIGNATURE = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"  # HWP 5.0 (OLE 복합 문서) 파일 시그니처


def is_valid_hwp_file(path: str) -> bool:
    try:
        with open(path, "rb") as f:
            return f.read(len(HWP_SIGNATURE)) == HWP_SIGNATURE
    except Exception:
        return False


def _save_as_hwp(hwp, dst_path: str):
    errors = []

    # 한글 버전에 따라 저장 포맷 문자열이 다를 수 있어 여러 값 시도
    for fmt in ("HWP", "HWP File"):
        try:
            hwp.SaveAs(dst_path, fmt, "")
            if is_valid_hwp_file(dst_path):
                return
            errors.append(f"SaveAs({fmt}) 결과가 HWP 형식이 아님")
        except Exception as exc:
            errors.append(f"SaveAs({fmt}) 실패: {exc}")

    try:
        hwp.HAction.GetDefault("FileSaveAs_S", hwp.HParameterSet.HFileOpenSave.HSet)
        hwp.HParameterSet.HFileOpenSave.filename = dst_path
        hwp.HParameterSet.HFileOpenSave.Format = "HWP"
        hwp.HParameterSet.HFileOpenSave.Attributes = 0
        hwp.HAction.Execute("FileSaveAs_S", hwp.HParameterSet.HFileOpenSave.HSet)
        if is_valid_hwp_file(dst_path):
            return
    except Exception as exc:
        errors.append(f"FileSaveAs_S 실패: {exc}")

    raise RuntimeError("HWP 저장 실패: " + " | ".join(errors[-3:]))


def _safe_temp_name(ext: str) -> str:
    ext = (ext or "").lower()
    if not ext.startswith("."):
        ext = "." + ext
    return "input" + ext


def _replace_file(src_file: Path, dst_file: Path):
    """tmp 결과물을 최종 경로로 이동. 기존 같은 이름 HWP는 덮어씀."""
    dst_file.parent.mkdir(parents=True, exist_ok=True)

    try:
        os.replace(str(src_file), str(dst_file))
        return
    except Exception:
        pass

    if dst_file.exists():
        try:
            dst_file.unlink()
        except Exception:
            pass
    shutil.copy2(str(src_file), str(dst_file))


def _convert_hwpx_to_hwp_via_temp(hwp, src_path: str, dst_path: str):
    """
    PDF 변환 도구와 같은 방식:
    원본을 임시폴더 input.hwpx로 복사해 열고, output.hwp를 만든 뒤 최종 위치로 이동.
    원본 HWPX는 이 함수에서 삭제하지 않고, 호출부에서 변환 성공 확인 후 삭제합니다.
    """
    src = Path(src_path).resolve()
    dst = Path(dst_path).resolve()

    temp_dir = Path(tempfile.mkdtemp(prefix="hwpx2hwp_"))
    opened = False

    try:
        tmp_in = temp_dir / _safe_temp_name(src.suffix.lower() or ".hwpx")
        tmp_out = temp_dir / "output.hwp"

        shutil.copy2(str(src), str(tmp_in))

        _open_hwpx(hwp, str(tmp_in))
        opened = True
        _save_as_hwp(hwp, str(tmp_out))

        if not is_valid_hwp_file(str(tmp_out)):
            raise RuntimeError("올바른 HWP 파일이 생성되지 않았습니다.")

        _replace_file(tmp_out, dst)
    finally:
        if opened:
            try:
                _close_current_hwp_document(hwp)
            except Exception:
                pass
        try:
            shutil.rmtree(temp_dir, ignore_errors=True)
        except Exception:
            pass


def _delete_original_hwpx(src_path: str):
    """변환 성공 후 원본 HWPX 삭제"""
    try:
        os.remove(src_path)
        return True, ""
    except PermissionError as exc:
        try:
            os.chmod(src_path, 0o666)
            os.remove(src_path)
            return True, ""
        except Exception as exc2:
            return False, f"원본 삭제 실패: {exc2 or exc}"
    except Exception as exc:
        return False, f"원본 삭제 실패: {exc}"


def convert_one_hwpx_to_hwp_replace(src_path: str, visible: bool = False):
    """한 파일을 같은 폴더의 HWP로 변환하고, 성공 시 원본 HWPX를 삭제합니다."""
    _ensure_pywin32_available()
    import pythoncom

    dst_path = output_hwp_path(src_path)
    pythoncom.CoInitialize()
    hwp = None
    try:
        hwp, _, _, _ = _create_hwp_object(visible=visible)
        _convert_hwpx_to_hwp_via_temp(hwp, src_path, dst_path)
        if not is_valid_hwp_file(dst_path):
            raise RuntimeError("변환된 HWP 파일을 확인할 수 없습니다.")
        ok, msg = _delete_original_hwpx(src_path)
        if not ok:
            raise RuntimeError(msg)
        return dst_path
    finally:
        if hwp is not None:
            try:
                hwp.Quit()
            except Exception:
                pass
        try:
            pythoncom.CoUninitialize()
        except Exception:
            pass


class HwpxToHwpApp:
    def __init__(self, root):
        self.root = root
        self.root.title(APP_TITLE)
        self.root.geometry("1040x740")
        self.root.minsize(960, 680)
        self.root.configure(bg="white")

        self.running = False
        self.file_items = {}
        self.file_set = set()

        self.recursive_var = tk.BooleanVar(value=True)
        self.open_folder_var = tk.BooleanVar(value=True)
        self.show_hwp_var = tk.BooleanVar(value=False)

        title_font = ("맑은 고딕", 16, "bold")
        label_font = ("맑은 고딕", 11)
        small_font = ("맑은 고딕", 10)

        footer = tk.Frame(root, bg="white")
        footer.pack(side="bottom", fill="x")

        tk.Label(
            footer,
            text="관련문의 : hidori.kr",
            font=small_font,
            bg="white",
        ).pack(pady=(4, 8))

        main = tk.Frame(root, bg="white")
        main.pack(side="top", fill="both", expand=True)

        tk.Label(main, text=APP_TITLE, font=title_font, bg="white").pack(pady=(14, 4))

        tk.Label(
            main,
            text=(
                "HWPX 원본 파일을 HWP 파일로 일괄 변환하는 도구입니다.\n"
                "변환 성공 시 원본 HWPX는 삭제되고, 같은 폴더에 같은 이름의 HWP 파일만 남습니다."
            ),
            font=label_font,
            bg="white",
            justify="center",
        ).pack(pady=(0, 8))

        frame_buttons = tk.Frame(main, bg="white")
        frame_buttons.pack(fill="x", padx=22, pady=(3, 6))

        tk.Button(
            frame_buttons,
            text="파일 선택",
            font=label_font,
            command=self.add_files,
            width=12,
        ).pack(side="left", padx=(0, 6))

        tk.Button(
            frame_buttons,
            text="폴더 선택",
            font=label_font,
            command=self.add_folder,
            width=12,
        ).pack(side="left", padx=(0, 6))

        tk.Button(
            frame_buttons,
            text="선택 항목 제거",
            font=label_font,
            command=self.remove_selected_items,
            width=14,
        ).pack(side="left", padx=(0, 6))

        tk.Button(
            frame_buttons,
            text="초기화",
            font=label_font,
            command=self.reset_items,
            width=10,
        ).pack(side="left", padx=(0, 6))

        self.btn_run = tk.Button(
            frame_buttons,
            text="변환 실행",
            font=label_font,
            command=self.start_convert,
            width=12,
        )
        self.btn_run.pack(side="right")

        frame_options = tk.Frame(main, bg="white")
        frame_options.pack(fill="x", padx=22, pady=(0, 3))

        tk.Checkbutton(
            frame_options,
            text="폴더 선택 시 하위 폴더 포함",
            variable=self.recursive_var,
            font=small_font,
            bg="white",
        ).pack(side="left", padx=(0, 18))

        tk.Checkbutton(
            frame_options,
            text="완료 후 첫 번째 원본 폴더 열기",
            variable=self.open_folder_var,
            font=small_font,
            bg="white",
        ).pack(side="left", padx=(0, 18))

        tk.Checkbutton(
            frame_options,
            text="변환 중 한글 창 보이기",
            variable=self.show_hwp_var,
            font=small_font,
            bg="white",
        ).pack(side="left")

        tk.Label(
            main,
            text=(
                "※ 각 HWPX 파일과 같은 폴더에 같은 이름의 HWP를 만들고, "
                "성공한 경우에만 원본 HWPX를 삭제합니다. 기존 같은 이름의 HWP가 있으면 덮어씁니다."
            ),
            font=small_font,
            bg="white",
            fg="#666666",
            wraplength=980,
            justify="left",
        ).pack(anchor="w", padx=22, pady=(4, 5))

        frame_list = tk.Frame(main, bg="white")
        frame_list.pack(fill="both", expand=True, padx=22, pady=(3, 4))

        tk.Label(frame_list, text="변환할 HWPX 파일 목록", font=label_font, bg="white").pack(anchor="w")

        tree_frame = tk.Frame(frame_list, bg="white")
        tree_frame.pack(fill="both", expand=True, pady=(3, 0))

        columns = ("filename", "folder", "target", "status", "path")
        self.tree = ttk.Treeview(
            tree_frame,
            columns=columns,
            show="headings",
            selectmode="extended",
            height=10,
        )

        self.tree.heading("filename", text="원본 파일명")
        self.tree.heading("folder", text="원본 폴더")
        self.tree.heading("target", text="변환 후 파일명")
        self.tree.heading("status", text="상태")
        self.tree.heading("path", text="원본 경로")

        self.tree.column("filename", width=230, anchor="w")
        self.tree.column("folder", width=260, anchor="w")
        self.tree.column("target", width=230, anchor="w")
        self.tree.column("status", width=100, anchor="center")
        self.tree.column("path", width=430, anchor="w")

        self.tree.pack(side="left", fill="both", expand=True)

        scroll_y = ttk.Scrollbar(tree_frame, orient="vertical", command=self.tree.yview)
        scroll_y.pack(side="right", fill="y")
        self.tree.configure(yscrollcommand=scroll_y.set)

        frame_log = tk.Frame(main, bg="white")
        frame_log.pack(fill="x", padx=22, pady=(2, 4))

        tk.Label(frame_log, text="진행 상황", font=label_font, bg="white").pack(anchor="w")

        log_wrap = tk.Frame(frame_log, bg="white")
        log_wrap.pack(fill="x", pady=(3, 0))

        self.text_log = tk.Text(
            log_wrap,
            height=7,
            font=small_font,
            wrap="word",
            bg="#fafafa",
            state="disabled",
            relief="solid",
            bd=1,
        )
        self.text_log.pack(side="left", fill="x", expand=True)

        log_scroll = ttk.Scrollbar(log_wrap, orient="vertical", command=self.text_log.yview)
        log_scroll.pack(side="right", fill="y")
        self.text_log.configure(yscrollcommand=log_scroll.set)

    def safe_ui(self, func, *args, **kwargs):
        self.root.after(0, lambda: func(*args, **kwargs))

    def log(self, message: str):
        self.text_log.config(state="normal")
        self.text_log.insert("end", message + "\n")
        self.text_log.see("end")
        self.text_log.config(state="disabled")
        self.root.update_idletasks()

    def clear_log(self):
        self.text_log.config(state="normal")
        self.text_log.delete("1.0", "end")
        self.text_log.config(state="disabled")

    def set_running(self, value: bool):
        self.running = value
        self.btn_run.config(state="disabled" if value else "normal")

    def add_files(self):
        if self.running:
            return

        paths = filedialog.askopenfilenames(
            title="변환할 HWPX 파일을 선택하세요",
            filetypes=[("한글 HWPX 파일", "*.hwpx"), ("모든 파일", "*.*")],
        )
        if not paths:
            return

        added = 0
        skipped = 0
        for path in paths:
            if self.add_file_to_list(path):
                added += 1
            else:
                skipped += 1

        self.log(f"파일 추가: {added}개")
        if skipped:
            self.log(f"건너뜀: {skipped}개 / HWPX 파일이 아니거나 이미 추가된 파일")

    def add_folder(self):
        if self.running:
            return

        folder = filedialog.askdirectory(title="HWPX 파일이 들어 있는 폴더를 선택하세요")
        if not folder:
            return

        paths = collect_hwpx_files_from_folder(folder, recursive=self.recursive_var.get())
        added = 0
        skipped = 0
        for path in paths:
            if self.add_file_to_list(path):
                added += 1
            else:
                skipped += 1

        self.log(f"폴더에서 HWPX 파일 수집: {added}개")
        if skipped:
            self.log(f"건너뜀: {skipped}개 / 이미 추가된 파일")
        if not paths:
            messagebox.showinfo("안내", "선택한 폴더에서 HWPX 파일을 찾지 못했습니다.")

    def add_file_to_list(self, path: str) -> bool:
        if not path or not os.path.isfile(path):
            return False
        if not is_hwpx_file(path):
            return False

        path = os.path.abspath(path)
        norm = normalize_path(path)
        if norm in self.file_set:
            return False

        self.file_set.add(norm)
        folder = os.path.dirname(path)
        filename = os.path.basename(path)
        target_name = os.path.basename(output_hwp_path(path))
        item_id = self.tree.insert("", "end", values=(filename, folder, target_name, "대기", path))
        self.file_items[item_id] = path
        return True

    def remove_selected_items(self):
        if self.running:
            return

        selected = self.tree.selection()
        if not selected:
            messagebox.showinfo("안내", "목록에서 제거할 항목을 선택해주세요.")
            return

        for item_id in selected:
            path = self.file_items.get(item_id)
            if path:
                self.file_set.discard(normalize_path(path))
            self.file_items.pop(item_id, None)
            self.tree.delete(item_id)

    def reset_items(self):
        if self.running:
            return
        self.tree.delete(*self.tree.get_children())
        self.file_items.clear()
        self.file_set.clear()
        self.clear_log()

    def update_item_status(self, item_id: str, status: str):
        values = list(self.tree.item(item_id, "values"))
        if len(values) >= 4:
            values[3] = status
            self.tree.item(item_id, values=values)

    def get_all_items(self):
        result = []
        for item_id in self.tree.get_children():
            path = self.file_items.get(item_id)
            if path and os.path.isfile(path):
                result.append((item_id, path))
        return result

    def start_convert(self):
        if self.running:
            return

        items = self.get_all_items()
        if not items:
            messagebox.showwarning("확인", "변환할 HWPX 파일을 먼저 선택해주세요.")
            return

        existing_hwp_count = sum(1 for _, src_path in items if os.path.exists(output_hwp_path(src_path)))

        confirm_message = (
            f"HWPX 파일 {len(items)}개를 HWP로 변환합니다.\n\n"
            "처리 방식\n"
            "- 각 HWPX 파일과 같은 폴더에 같은 이름의 HWP를 생성합니다.\n"
            "- 변환 성공 시 원본 HWPX 파일을 삭제합니다.\n"
            "- 변환 실패 파일은 원본 HWPX를 삭제하지 않습니다.\n"
        )
        if existing_hwp_count:
            confirm_message += f"- 기존 같은 이름의 HWP {existing_hwp_count}개는 덮어씁니다.\n"
        confirm_message += "\n계속할까요?"

        confirm = messagebox.askyesno("확인", confirm_message)
        if not confirm:
            return

        visible = self.show_hwp_var.get()
        open_folder = self.open_folder_var.get()
        first_folder = os.path.dirname(items[0][1]) if items else None

        self.set_running(True)
        self.clear_log()

        def worker():
            success_count = 0
            delete_fail_count = 0
            fail_count = 0
            overwrite_count = 0

            self.safe_ui(self.log, "===== HWPX → HWP 원본 변환 시작 =====")
            self.safe_ui(self.log, f"처리 대상 파일 수: {len(items)}개")
            self.safe_ui(self.log, "저장 방식: 원본과 같은 폴더에 같은 이름의 HWP 생성")
            self.safe_ui(self.log, "원본 처리: 변환 성공 시 HWPX 삭제")
            self.safe_ui(self.log, "기존 HWP 처리: 같은 이름이 있으면 덮어쓰기")
            self.safe_ui(self.log, f"한글 창 보이기: {'예' if visible else '아니오'}")
            self.safe_ui(self.log, "")

            try:
                _ensure_pywin32_available()
            except Exception as exc:
                self.safe_ui(messagebox.showerror, "실행 준비 오류", str(exc))
                self.safe_ui(self.log, str(exc))
                self.safe_ui(self.set_running, False)
                return

            import pythoncom
            pythoncom.CoInitialize()
            hwp = None

            try:
                hwp, security_ok, security_module_name, security_error = _create_hwp_object(visible=visible)

                if security_ok:
                    self.safe_ui(self.log, f"보안승인 모듈 등록: 성공({security_module_name})")
                    self.safe_ui(self.log, "HWP→PDF 변환 도구와 같은 SecurityModule + 임시파일 복사 방식으로 변환합니다.")
                else:
                    self.safe_ui(self.log, "보안승인 모듈 등록: 실패 또는 미등록")
                    if security_error:
                        self.safe_ui(self.log, f"  - 사유: {security_error}")
                    self.safe_ui(self.log, "  - 한글의 접근 허용 창이 뜨면 '모두 허용'을 선택하세요.")
                    self.safe_ui(self.log, "  - 이번 실행에서는 한글 객체를 한 번만 사용하므로 파일마다 새로 묻는 현상을 줄였습니다.")
                self.safe_ui(self.log, "")

                for idx, (item_id, src_path) in enumerate(items, start=1):
                    filename = os.path.basename(src_path)
                    dst_path = output_hwp_path(src_path)
                    already_exists = os.path.exists(dst_path)

                    self.safe_ui(self.update_item_status, item_id, "변환 중")
                    self.safe_ui(self.log, f"[{idx}/{len(items)}] {filename}")
                    self.safe_ui(self.log, f"  - 원본 HWPX: {src_path}")
                    self.safe_ui(self.log, f"  - 변환 HWP: {dst_path}")
                    if already_exists:
                        self.safe_ui(self.log, "  - 안내: 같은 이름의 HWP가 있어 덮어씁니다.")

                    try:
                        if not os.path.exists(src_path):
                            raise FileNotFoundError("원본 파일을 찾을 수 없습니다.")

                        _convert_hwpx_to_hwp_via_temp(hwp, src_path, dst_path)

                        if not is_valid_hwp_file(dst_path):
                            raise RuntimeError("변환된 HWP 파일을 확인할 수 없습니다.")

                        if already_exists:
                            overwrite_count += 1

                        delete_ok, delete_msg = _delete_original_hwpx(src_path)
                        if delete_ok:
                            success_count += 1
                            self.safe_ui(self.update_item_status, item_id, "완료")
                            self.safe_ui(self.log, "  - 결과: 변환 완료, 원본 HWPX 삭제 완료")
                        else:
                            delete_fail_count += 1
                            self.safe_ui(self.update_item_status, item_id, "삭제 실패")
                            self.safe_ui(self.log, f"  - 결과: 변환 완료, {delete_msg}")

                    except Exception as exc:
                        fail_count += 1
                        self.safe_ui(self.update_item_status, item_id, "실패")
                        self.safe_ui(self.log, f"  ! 변환 실패: {exc}")
                        self.safe_ui(self.log, "  - 원본 HWPX는 삭제하지 않았습니다.")

                    self.safe_ui(self.log, "")

            except Exception as exc:
                self.safe_ui(messagebox.showerror, "한글 실행 오류", f"한글 프로그램을 실행하거나 제어할 수 없습니다.\n\n{exc}")
                self.safe_ui(self.log, f"한글 실행 오류: {exc}")
                fail_count = len(items)
                for item_id, _ in items:
                    self.safe_ui(self.update_item_status, item_id, "실패")

            finally:
                if hwp is not None:
                    try:
                        hwp.Quit()
                    except Exception:
                        pass
                try:
                    pythoncom.CoUninitialize()
                except Exception:
                    pass

            self.safe_ui(self.log, "===== 완료 =====")
            self.safe_ui(self.log, f"성공: {success_count}개")
            self.safe_ui(self.log, f"원본 삭제 실패: {delete_fail_count}개")
            self.safe_ui(self.log, f"변환 실패: {fail_count}개")
            self.safe_ui(self.log, f"기존 HWP 덮어쓰기: {overwrite_count}개")

            self.safe_ui(
                messagebox.showinfo,
                "완료",
                "HWPX → HWP 원본 변환이 완료되었습니다.\n\n"
                f"성공: {success_count}개\n"
                f"원본 삭제 실패: {delete_fail_count}개\n"
                f"변환 실패: {fail_count}개\n"
                f"기존 HWP 덮어쓰기: {overwrite_count}개",
            )

            if open_folder and first_folder:
                try:
                    os.startfile(first_folder)
                except Exception:
                    pass

            self.safe_ui(self.set_running, False)

        threading.Thread(target=worker, daemon=True).start()


def main():
    root = tk.Tk()
    HwpxToHwpApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
