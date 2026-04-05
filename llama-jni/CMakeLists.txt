cmake_minimum_required(VERSION 3.22.1)
project(nexus-llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../external/llama.cpp")

# ===== ARM SIMD optimization via global compiler flags =====
# Sets -march globally so ALL targets (ggml, llama, common, KleidiAI)
# are compiled with dotprod + i8mm + fp16 support.
# Target: Pixel 9 Pro Tensor G4 (Cortex-X4/A720, ARMv8.6)
if(DEFINED ANDROID_ABI AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM")
    set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} -march=armv8.6-a+dotprod+i8mm+fp16"   CACHE STRING "" FORCE)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.6-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)
    message(STATUS "Android arm64: CMAKE_C_FLAGS = ${CMAKE_C_FLAGS}")
endif()

# Disable unnecessary build targets
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

# Static linking — single binary, no dynamic backend loading needed
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Threading: OpenMP OFF (deadlocks on Android NDK c++_shared)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# ===== Vulkan GPU acceleration =====
# Pixel 9 Pro: Mali-G715 with Vulkan 1.3
# Offloads matrix ops to GPU — 3-5x faster prompt decode than CPU-only
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(GGML_VULKAN_SHADER_COMP_THREADS 4 CACHE STRING "" FORCE)

# Also set GGML_CPU_ARM_ARCH as belt-and-suspenders
set(GGML_CPU_ARM_ARCH "armv8.6-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)

# KleidiAI: optimized matmul kernels for ARM
set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)

# Runtime weight repacking
set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE)

# Build llama.cpp
add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

# JNI bridge shared library
add_library(nexus-llama SHARED llama_jni.cpp)

target_include_directories(nexus-llama PRIVATE
    ${LLAMA_CPP_DIR}/common
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/ggml/include
)

# Link Vulkan loader (available on Android 28+)
find_library(VULKAN_LIB vulkan)
if(VULKAN_LIB)
    message(STATUS "Vulkan library found: ${VULKAN_LIB}")
else()
    message(WARNING "Vulkan library NOT found — GPU acceleration disabled")
endif()

target_link_libraries(nexus-llama PRIVATE llama common android log)
target_compile_options(nexus-llama PRIVATE -O3)
