cmake_minimum_required(VERSION 3.22.1)
project(nexus-llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../external/llama.cpp")

# ===== Android ABI detection (from official llama.cpp Android example) =====
if(DEFINED ANDROID_ABI)
    message(STATUS "Android ABI detected: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
    endif()
endif()

# ===== Build configuration (matching official Android example) =====
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

# Threading: OpenMP OFF — deadlocks on Android NDK c++_shared STL
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# ===== CPU Backend: Multi-variant with runtime detection =====
# This builds 7 different CPU backends with different ARM feature sets:
#   armv8.0 (baseline), armv8.2+dotprod, armv8.2+dotprod+fp16,
#   armv8.6+dotprod+fp16+i8mm, armv9.0+SVE2, armv9.2+SME, etc.
# At runtime, llama.cpp automatically picks the best variant for the device.
# This is the CORRECT approach for Android (not hardcoded -march flags).
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)

# LLAMAFILE OFF (official example disables it with ALL_VARIANTS)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

# Build llama.cpp
add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

# JNI bridge shared library
add_library(nexus-llama SHARED llama_jni.cpp)

target_include_directories(nexus-llama PRIVATE
    ${LLAMA_CPP_DIR}/common
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/ggml/include
)

target_link_libraries(nexus-llama PRIVATE llama common android log)
target_compile_options(nexus-llama PRIVATE -O3)
