cmake_minimum_required(VERSION 3.22.1)
project(nexus-llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../external/llama.cpp")

# ===== ARM SIMD: Global compiler flags for ALL targets =====
# CRITICAL: Setting GGML_CPU_ARM_ARCH alone doesn't reliably propagate through
# cmake subdirectory scopes. The ONLY guaranteed way to ensure dotprod/i8mm/sve
# are used in ALL ggml compilation units is to set global CMAKE_C/CXX_FLAGS.
# Target: Pixel 9 Pro Tensor G4 (Cortex-X4/A720, ARMv8.6+dotprod+i8mm+sve)
if(DEFINED ANDROID_ABI AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(ARM_ARCH_FLAGS "-march=armv8.6-a+dotprod+i8mm+fp16+sve")
    set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} ${ARM_ARCH_FLAGS}"   CACHE STRING "" FORCE)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${ARM_ARCH_FLAGS}" CACHE STRING "" FORCE)
    set(GGML_SYSTEM_ARCH "ARM")
    message(STATUS "Android arm64-v8a: Applied ${ARM_ARCH_FLAGS} globally")
    message(STATUS "CMAKE_C_FLAGS: ${CMAKE_C_FLAGS}")
endif()

# Disable unnecessary llama.cpp build targets
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_LTO ON CACHE BOOL "" FORCE)

# Threading: OpenMP OFF — deadlocks on Android NDK c++_shared STL
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# Also set GGML_CPU_ARM_ARCH for ggml's own ARM feature detection
set(GGML_CPU_ARM_ARCH "armv8.6-a+dotprod+i8mm+fp16+sve" CACHE STRING "" FORCE)

# KleidiAI: optimized matmul kernels for ARM (20-30% speedup)
set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)

# Runtime weight repacking (Q4_0 → optimized layout for KleidiAI)
set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE)

# LLAMAFILE: optimized sgemm kernels
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)

# Build llama.cpp using its own CMake build system
add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

# JNI bridge shared library
add_library(nexus-llama SHARED llama_jni.cpp)

target_include_directories(nexus-llama PRIVATE
    ${LLAMA_CPP_DIR}/common
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/ggml/include
)

target_link_libraries(nexus-llama PRIVATE llama common android log)
target_compile_options(nexus-llama PRIVATE -O3 -flto -ffast-math)
target_link_options(nexus-llama PRIVATE -flto)
