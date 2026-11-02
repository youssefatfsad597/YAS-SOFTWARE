cmake_minimum_required(VERSION 3.22.1)
project("yas_core")

add_library(yas_core SHARED
    yas_native.c
    sha256.c
    aes256ctr.c
)

# -fvisibility=hidden: only the JNIEXPORT-marked functions (explicitly
# JNICALL/JNIEXPORT) stay exported; everything else disappears from the
# dynamic symbol table. Without this, `nm -D libyas_core.so` leaks internal
# function names (e.g. "reconstruct_fw_key_material") as a roadmap for
# whoever opens the .so in Ghidra. Release-only: keep default visibility in
# debug builds so you can still debug/symbolicate crashes locally.
target_compile_options(yas_core PRIVATE
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
    $<$<CONFIG:Release>:-O2>
)
target_link_options(yas_core PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
)

find_library(log-lib log)
target_link_libraries(yas_core ${log-lib})
