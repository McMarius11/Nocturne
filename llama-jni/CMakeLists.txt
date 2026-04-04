cmake_minimum_required(VERSION 3.22.1)
project(nexus-llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../external/llama.cpp")

# Disable unnecessary llama.cpp build targets
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_LTO ON CACHE BOOL "" FORCE)

# Android threading: OpenMP MUST be OFF — causes deadlocks in llama_decode
# on Android NDK with c++_shared STL. llama.cpp's own thread pool works fine.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# ARM optimizations for Tensor G4 (Pixel 9 Pro) — ARMv9, dotprod, i8mm
# CRITICAL: GGML_NATIVE is OFF for cross-compilation, so we MUST set
# GGML_CPU_ARM_ARCH to enable ARM SIMD features. Without this, ggml
# falls back to base armv8-a (scalar only) → 10-50x slower.
set(GGML_CPU_ARM_ARCH "armv8.6-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)

# KleidiAI provides optimized matmul kernels for ARM (20-30% speedup)
set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)

# Build llama.cpp using its own CMake build system
# This is robust against internal restructuring of llama.cpp
add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

# JNI bridge shared library (LLM inference only — TTS uses sherpa-onnx)
add_library(nexus-llama SHARED llama_jni.cpp)

# Include paths for llama.cpp common headers (sampling.h, common.h, etc.)
target_include_directories(nexus-llama PRIVATE
    ${LLAMA_CPP_DIR}/common
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/ggml/include
)

# Link llama + common (for chat templates, sampling, tokenization)
target_link_libraries(nexus-llama PRIVATE llama common android log)
target_compile_options(nexus-llama PRIVATE -O3 -flto -ffast-math)
target_link_options(nexus-llama PRIVATE -flto)
