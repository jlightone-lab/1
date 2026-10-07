"""
HWPX → HWP 일괄 변환 도구

기능
- HWPX 파일 여러 개를 한 번에 선택
- 폴더 안의 HWPX 파일 자동 수집
- 한글 프로그램 없이 내장 변환 엔진(hwpConverter)으로 HWP 5.0 문서 생성
- 변환된 HWP는 원본 HWPX와 같은 폴더에 같은 파일명으로 저장
- 변환 후 결과 HWP를 다시 읽어 본문 글자가 원본과 같은지 검증
- (선택) 검증까지 통과한 경우에만 원본 HWPX 삭제

주의
- 한글 2010 등 HWPX를 열 수 없는 환경에서 쓰기 위한 도구입니다.
- 텍스트·표·이미지·글꼴·문단 서식은 최대한 보존하지만,
  최신 개체(차트, OLE 등)는 달라질 수 있으니 중요한 문서는 결과를 확인하세요.
"""

import os
import shutil
import subprocess
import sys
import tempfile
import threading
from pathlib import Path

import tkinter as tk
from tkinter import ttk, filedialog, messagebox


APP_TITLE = "HWPX → HWP 일괄 변환 도구(v2.0)"
SUPPORTED_EXT = ".hwpx"
CONVERT_TIMEOUT_SEC = 300
HWP_SIGNATURE = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"  # HWP 5.0 (OLE 복합 문서) 파일 시그니처


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


def is_valid_hwp_file(path: str) -> bool:
    try:
        with open(path, "rb") as f:
            return f.read(len(HWP_SIGNATURE)) == HWP_SIGNATURE
    except Exception:
        return False


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


# ---------------------------------------------------------------------------
# 변환 엔진
# ---------------------------------------------------------------------------

def _app_base_dir() -> Path:
    """exe로 실행하면 PyInstaller 압축 해제 폴더, 소스로 실행하면 이 파일 위치"""
    return Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))


def find_engine():
    """(java 실행 파일, classpath) 반환. 찾지 못하면 RuntimeError."""
    base = _app_base_dir()

    engine_dir = None
    for candidate in (base / "engine", base / "build" / "engine"):
        if (candidate / "hwpxtool.jar").is_file():
            engine_dir = candidate
            break
    if engine_dir is None:
        raise RuntimeError(
            "변환 엔진을 찾을 수 없습니다.\n"
            "프로그램 폴더의 파일이 모두 있는지 확인하세요."
        )

    java_name = "java.exe" if os.name == "nt" else "java"
    java = None
    for candidate in (base / "runtime" / "bin" / java_name, base / "build" / "runtime" / "bin" / java_name):
        if candidate.is_file():
            java = str(candidate)
            break
    if java is None:
        java = shutil.which("java")
    if java is None:
        raise RuntimeError(
            "내장 Java 런타임을 찾을 수 없습니다.\n"
            "프로그램 폴더의 runtime 폴더가 있는지 확인하세요."
        )

    jars = [engine_dir / "hwpxtool.jar", engine_dir / "hwpConverter.jar"]
    jars += sorted((engine_dir / "lib").glob("*.jar"))
    classpath = os.pathsep.join(str(j) for j in jars)
    return java, classpath


def _run_engine(java: str, classpath: str, src: str, dst: str):
    """엔진 실행 → (상태, 메시지). 상태는 'OK' / 'WARN' / 'FAIL'"""
    command = [
        java, "-Xmx1024m", "-Dfile.encoding=UTF-8",
        "-cp", classpath, "HwpxToHwpTool", src, dst,
    ]
    flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    try:
        proc = subprocess.run(
            command,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=CONVERT_TIMEOUT_SEC,
            creationflags=flags,
        )
    except subprocess.TimeoutExpired:
        return "FAIL", f"변환 시간이 {CONVERT_TIMEOUT_SEC}초를 넘어 중단했습니다."

    stdout = proc.stdout.decode("utf-8", errors="replace")
    for line in reversed(stdout.splitlines()):
        if line.startswith("RESULT\t"):
            parts = line.split("\t")
            status = parts[1] if len(parts) > 1 else "FAIL"
            if status == "OK":
                return "OK", f"본문 {parts[2] if len(parts) > 2 else '?'}자 일치"
            return status, parts[2] if len(parts) > 2 else ""

    stderr = proc.stderr.decode("utf-8", errors="replace").strip().splitlines()
    detail = stderr[-1] if stderr else f"종료 코드 {proc.returncode}"
    return "FAIL", f"엔진 실행 오류: {detail}"


def convert_hwpx_to_hwp(java: str, classpath: str, src_path: str, dst_path: str):
    """
    임시폴더에 변환한 뒤 검증된 결과만 최종 위치로 이동합니다.
    기존 같은 이름 HWP는 덮어씁니다. 원본 HWPX는 이 함수에서 삭제하지 않습니다.
    반환: (상태, 메시지)
    """
    temp_dir = Path(tempfile.mkdtemp(prefix="hwpx2hwp_"))
    try:
        tmp_in = temp_dir / "input.hwpx"
        tmp_out = temp_dir / "output.hwp"
        shutil.copy2(src_path, tmp_in)

        status, message = _run_engine(java, classpath, str(tmp_in), str(tmp_out))
        if status == "FAIL":
            return status, message
        if not is_valid_hwp_file(str(tmp_out)):
            return "FAIL", "올바른 HWP 파일이 생성되지 않았습니다."

        os.makedirs(os.path.dirname(os.path.abspath(dst_path)), exist_ok=True)
        try:
            os.replace(str(tmp_out), dst_path)
        except OSError:
            shutil.copy2(str(tmp_out), dst_path)
        return status, message
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)


def _delete_original_hwpx(src_path: str):
    """변환·검증 성공 후 원본 HWPX 삭제"""
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


# ---------------------------------------------------------------------------
# 화면
# ---------------------------------------------------------------------------

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
        self.delete_original_var = tk.BooleanVar(value=False)

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
                "HWPX 파일을 HWP 파일로 일괄 변환하는 도구입니다. 한글 프로그램이 없어도 동작합니다.\n"
                "한글 2010 등 HWPX를 열 수 없는 환경에서 사용할 HWP 파일을 같은 폴더에 만듭니다."
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
            text="변환 성공 시 원본 HWPX 삭제",
            variable=self.delete_original_var,
            font=small_font,
            bg="white",
        ).pack(side="left")

        tk.Label(
            main,
            text=(
                "※ 각 HWPX 파일과 같은 폴더에 같은 이름의 HWP를 만듭니다. 기존 같은 이름의 HWP가 있으면 덮어씁니다. "
                "변환 후 본문 글자를 원본과 비교해 다르면 '확인 필요'로 표시하며, 이 경우 원본은 삭제하지 않습니다."
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

        try:
            java, classpath = find_engine()
        except Exception as exc:
            messagebox.showerror("실행 준비 오류", str(exc))
            return

        delete_original = self.delete_original_var.get()
        existing_hwp_count = sum(1 for _, src_path in items if os.path.exists(output_hwp_path(src_path)))

        confirm_message = (
            f"HWPX 파일 {len(items)}개를 HWP로 변환합니다.\n\n"
            "처리 방식\n"
            "- 각 HWPX 파일과 같은 폴더에 같은 이름의 HWP를 생성합니다.\n"
        )
        if delete_original:
            confirm_message += "- 변환과 검증이 모두 성공한 경우에만 원본 HWPX를 삭제합니다.\n"
        else:
            confirm_message += "- 원본 HWPX는 그대로 둡니다.\n"
        if existing_hwp_count:
            confirm_message += f"- 기존 같은 이름의 HWP {existing_hwp_count}개는 덮어씁니다.\n"
        confirm_message += "\n계속할까요?"

        if not messagebox.askyesno("확인", confirm_message):
            return

        open_folder = self.open_folder_var.get()
        first_folder = os.path.dirname(items[0][1]) if items else None

        self.set_running(True)
        self.clear_log()

        def worker():
            success_count = 0
            warn_count = 0
            fail_count = 0
            delete_fail_count = 0
            overwrite_count = 0

            self.safe_ui(self.log, "===== HWPX → HWP 변환 시작 =====")
            self.safe_ui(self.log, f"처리 대상 파일 수: {len(items)}개")
            self.safe_ui(self.log, "저장 방식: 원본과 같은 폴더에 같은 이름의 HWP 생성")
            self.safe_ui(self.log, f"원본 처리: {'검증 성공 시 HWPX 삭제' if delete_original else '원본 유지'}")
            self.safe_ui(self.log, "")

            for idx, (item_id, src_path) in enumerate(items, start=1):
                filename = os.path.basename(src_path)
                dst_path = output_hwp_path(src_path)
                already_exists = os.path.exists(dst_path)

                self.safe_ui(self.update_item_status, item_id, "변환 중")
                self.safe_ui(self.log, f"[{idx}/{len(items)}] {filename}")
                self.safe_ui(self.log, f"  - 변환 HWP: {dst_path}")
                if already_exists:
                    self.safe_ui(self.log, "  - 안내: 같은 이름의 HWP가 있어 덮어씁니다.")

                try:
                    if not os.path.exists(src_path):
                        raise FileNotFoundError("원본 파일을 찾을 수 없습니다.")
                    status, message = convert_hwpx_to_hwp(java, classpath, src_path, dst_path)
                except Exception as exc:
                    status, message = "FAIL", str(exc)

                if status == "FAIL":
                    fail_count += 1
                    self.safe_ui(self.update_item_status, item_id, "실패")
                    self.safe_ui(self.log, f"  ! 변환 실패: {message}")
                    self.safe_ui(self.log, "  - 원본 HWPX는 그대로 있습니다.")
                    self.safe_ui(self.log, "")
                    continue

                if already_exists:
                    overwrite_count += 1

                if status == "WARN":
                    warn_count += 1
                    self.safe_ui(self.update_item_status, item_id, "확인 필요")
                    self.safe_ui(self.log, f"  - 결과: HWP 생성됨, 확인 필요 ({message})")
                    self.safe_ui(self.log, "  - 원본 HWPX는 삭제하지 않았습니다. 결과 파일을 직접 열어 확인하세요.")
                    self.safe_ui(self.log, "")
                    continue

                if delete_original:
                    delete_ok, delete_msg = _delete_original_hwpx(src_path)
                    if delete_ok:
                        success_count += 1
                        self.safe_ui(self.update_item_status, item_id, "완료")
                        self.safe_ui(self.log, f"  - 결과: 변환 완료 ({message}), 원본 HWPX 삭제 완료")
                    else:
                        delete_fail_count += 1
                        self.safe_ui(self.update_item_status, item_id, "삭제 실패")
                        self.safe_ui(self.log, f"  - 결과: 변환 완료 ({message}), {delete_msg}")
                else:
                    success_count += 1
                    self.safe_ui(self.update_item_status, item_id, "완료")
                    self.safe_ui(self.log, f"  - 결과: 변환 완료 ({message})")

                self.safe_ui(self.log, "")

            self.safe_ui(self.log, "===== 완료 =====")
            self.safe_ui(self.log, f"성공: {success_count}개")
            self.safe_ui(self.log, f"확인 필요: {warn_count}개")
            self.safe_ui(self.log, f"변환 실패: {fail_count}개")
            if delete_original:
                self.safe_ui(self.log, f"원본 삭제 실패: {delete_fail_count}개")
            self.safe_ui(self.log, f"기존 HWP 덮어쓰기: {overwrite_count}개")

            summary = (
                "HWPX → HWP 변환이 완료되었습니다.\n\n"
                f"성공: {success_count}개\n"
                f"확인 필요: {warn_count}개\n"
                f"변환 실패: {fail_count}개\n"
            )
            if delete_original:
                summary += f"원본 삭제 실패: {delete_fail_count}개\n"
            summary += f"기존 HWP 덮어쓰기: {overwrite_count}개"
            self.safe_ui(messagebox.showinfo, "완료", summary)

            if open_folder and first_folder:
                try:
                    os.startfile(first_folder)
                except Exception:
                    pass

            self.safe_ui(self.set_running, False)

        threading.Thread(target=worker, daemon=True).start()


def run_cli(args) -> int:
    """
    화면 없이 변환 (점검·자동화용)
      HWPX_to_HWP.exe --convert <input.hwpx> [output.hwp]
    """
    if not args:
        print("사용법: --convert <input.hwpx> [output.hwp]")
        return 2
    src = os.path.abspath(args[0])
    dst = os.path.abspath(args[1]) if len(args) > 1 else output_hwp_path(src)
    try:
        java, classpath = find_engine()
        status, message = convert_hwpx_to_hwp(java, classpath, src, dst)
    except Exception as exc:
        status, message = "FAIL", str(exc).replace("\n", " ")
    print(f"{status}\t{message}\t{dst}")
    return {"OK": 0, "WARN": 1}.get(status, 2)


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--convert":
        sys.exit(run_cli(sys.argv[2:]))

    root = tk.Tk()
    HwpxToHwpApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
