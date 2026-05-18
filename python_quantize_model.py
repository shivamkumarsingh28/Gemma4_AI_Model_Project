import subprocess
import sys
import os
import shutil
import platform

# ── Config ────────────────────────────────────────────────────────────────────
INPUT_GGUF       = "gemma4-e2b-merged_f16.gguf"
OUTPUT_GGUF      = "gemma4-e2b-merged-Q4_K_M.gguf"
QUANT_METHOD     = "Q4_K_M"
LLAMA_CPP_DIR    = "llama.cpp"
BUILD_TIMEOUT    = 600   # seconds
QUANT_TIMEOUT    = 3600  # 60 min — large models can take a while
# ─────────────────────────────────────────────────────────────────────────────

IS_WINDOWS = platform.system() == "Windows"
EXE = ".cpp" if IS_WINDOWS else ""


def find_quantize_tool() -> str | None:
    """Search all known build output locations for the quantize binary."""
    candidates = [
        # Modern llama.cpp (post-2024) — preferred name
        f"{LLAMA_CPP_DIR}/build/bin/llama-quantize{EXE}",
        f"{LLAMA_CPP_DIR}/build/bin/Release/llama-quantize{EXE}",
        # Older name still present in some builds
        f"{LLAMA_CPP_DIR}/build/bin/quantize{EXE}",
        f"{LLAMA_CPP_DIR}/build/bin/Release/quantize{EXE}",
        # CMake places binaries here on some configs
        f"{LLAMA_CPP_DIR}/build/llama-quantize{EXE}",
        f"{LLAMA_CPP_DIR}/build/quantize{EXE}",
        # Legacy Makefile build drops binaries in the repo root
        f"{LLAMA_CPP_DIR}/llama-quantize{EXE}",
        f"{LLAMA_CPP_DIR}/tools/quantize",
        f"{LLAMA_CPP_DIR}/tools/quantize/quantize{EXE}",
        # System-wide install
        shutil.which("llama-quantize") or "",
        shutil.which("quantize") or "",
    ]
    for path in candidates:
        if path and os.path.isfile(path):
            return os.path.abspath(path)
    return None


def build_llama_cpp() -> bool:
    """Clone (if needed) and build llama.cpp. Returns True on success."""
    if not os.path.isdir(LLAMA_CPP_DIR):
        print("  Cloning llama.cpp …")
        result = subprocess.run(
            ["git", "clone", "--depth=1",
             "https://github.com/ggerganov/llama.cpp", LLAMA_CPP_DIR],
            timeout=300
        )
        if result.returncode != 0:
            print("  ✗ git clone failed")
            return False

    build_dir = os.path.join(LLAMA_CPP_DIR, "build")
    os.makedirs(build_dir, exist_ok=True)

    print("  Running CMake configure …")
    cfg = subprocess.run(
        ["cmake", "..",
         "-DCMAKE_BUILD_TYPE=Release",
         "-DLLAMA_BUILD_TESTS=OFF",
         "-DLLAMA_BUILD_EXAMPLES=OFF"],
        cwd=build_dir,
        timeout=BUILD_TIMEOUT,
    )
    if cfg.returncode != 0:
        print("  ✗ CMake configure failed")
        return False
    print("  ✓ CMake configure done")

    print("  Building (this takes a few minutes) …")
    # Build only the quantize target to save time
    bld = subprocess.run(
        ["cmake", "--build", ".", "--config", "Release",
         "--target", "llama-quantize",
         "-j", str(os.cpu_count() or 4)],
        cwd=build_dir,
        timeout=BUILD_TIMEOUT,
    )
    if bld.returncode != 0:
        # Fallback: build everything (target name may differ on older versions)
        print("  Target 'llama-quantize' not found, building all …")
        bld = subprocess.run(
            ["cmake", "--build", ".", "--config", "Release",
             "-j", str(os.cpu_count() or 4)],
            cwd=build_dir,
            timeout=BUILD_TIMEOUT,
        )
    if bld.returncode != 0:
        print("  ✗ Build failed")
        return False

    print("  ✓ Build done")
    return True


def run_quantization(tool: str) -> bool:
    """Run the quantize tool and stream its output live."""
    cmd = [tool, INPUT_GGUF, OUTPUT_GGUF, QUANT_METHOD]
    print(f"  Command: {' '.join(cmd)}\n")

    # Popen so we can stream output in real time instead of buffering it all
    with subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    ) as proc:
        for line in proc.stdout:
            print(line, end="", flush=True)
        proc.wait()

    return proc.returncode == 0


def main():
    print("=" * 60)
    print(f"  GGUF Quantization  →  {QUANT_METHOD}")
    print("=" * 60)

    # ── 1. Validate input ────────────────────────────────────────────
    if not os.path.isfile(INPUT_GGUF):
        print(f"\n✗ Input file not found: {INPUT_GGUF}")
        sys.exit(1)
    size_gb = os.path.getsize(INPUT_GGUF) / (1024 ** 3)
    print(f"\n✓ Input : {INPUT_GGUF}  ({size_gb:.2f} GB)")
    print(f"  Output: {OUTPUT_GGUF}")
    print(f"  Method: {QUANT_METHOD}\n")

    # ── 2. Locate quantize tool (build if missing) ───────────────────
    print("Locating quantize tool …")
    tool = find_quantize_tool()

    if not tool:
        print("  Not found — building llama.cpp …")
        if not build_llama_cpp():
            print("\n✗ Could not build llama.cpp. Install it manually:")
            print("    git clone https://github.com/ggerganov/llama.cpp")
            print("    cd llama.cpp && mkdir build && cd build")
            print("    cmake .. && cmake --build . --config Release -j")
            sys.exit(1)
        tool = find_quantize_tool()

    if not tool:
        print("\n✗ Quantize binary still not found after build.")
        print("  Check the build output above for errors.")
        sys.exit(1)

    print(f"✓ Using: {tool}\n")

    # ── 3. Run quantization ──────────────────────────────────────────
    print("Starting quantization (may take 5–20 min for large models) …\n")
    try:
        success = run_quantization(tool)
    except subprocess.TimeoutExpired:
        print("\n✗ Timed out — increase QUANT_TIMEOUT if your model is very large.")
        sys.exit(1)
    except FileNotFoundError:
        print(f"\n✗ Binary not executable: {tool}")
        print("  On Linux/Mac run:  chmod +x " + tool)
        sys.exit(1)

    # ── 4. Report results ────────────────────────────────────────────
    if success and os.path.isfile(OUTPUT_GGUF):
        orig_gb  = os.path.getsize(INPUT_GGUF)  / (1024 ** 3)
        quant_gb = os.path.getsize(OUTPUT_GGUF) / (1024 ** 3)
        reduction = (1 - quant_gb / orig_gb) * 100

        print("\n" + "=" * 60)
        print("  ✓ Quantization complete!")
        print("=" * 60)
        print(f"  Original  (F16)    : {orig_gb:.2f} GB")
        print(f"  Quantized ({QUANT_METHOD}): {quant_gb:.2f} GB")
        print(f"  Size reduction     : {reduction:.1f}%")
        print(f"\n  Output file: {os.path.abspath(OUTPUT_GGUF)}")
    else:
        print("\n✗ Quantization failed — see output above for details.")
        sys.exit(1)


if __name__ == "__main__":
    main()