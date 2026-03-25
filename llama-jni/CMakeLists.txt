cmake_minimum_required(VERSION 3.22.1)
project(nexus-llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../external/llama.cpp")

# Disable unnecessary llama.cpp build targets
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# ARM optimizations for Tensor G4 / Snapdragon
set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
set(GGML_OPENMP ON CACHE BOOL "" FORCE)

# Build llama.cpp using its own CMake build system
# This is robust against internal restructuring of llama.cpp
add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

# JNI bridge shared library (LLM + TTS)
add_library(nexus-llama SHARED llama_jni.cpp tts_jni.cpp)

# Include paths for llama.cpp common headers (sampling.h, common.h, etc.)
target_include_directories(nexus-llama PRIVATE
    ${LLAMA_CPP_DIR}/common
    ${LLAMA_CPP_DIR}/include
)

# Link llama + common (for chat templates, sampling, tokenization)
target_link_libraries(nexus-llama PRIVATE llama common android log)
target_compile_options(nexus-llama PRIVATE -O3)
