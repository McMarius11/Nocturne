cmake_minimum_required(VERSION 3.22.1)
project(nexus-llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Path to llama.cpp source (cloned by setup.sh)
set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../external/llama.cpp")

# llama.cpp core sources
file(GLOB LLAMA_SOURCES
    "${LLAMA_CPP_DIR}/src/*.cpp"
    "${LLAMA_CPP_DIR}/src/*.c"
    "${LLAMA_CPP_DIR}/ggml/src/*.c"
    "${LLAMA_CPP_DIR}/ggml/src/*.cpp"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/*.c"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/*.cpp"
)

# Build llama as a static lib
add_library(llama_static STATIC ${LLAMA_SOURCES})

target_include_directories(llama_static PUBLIC
    "${LLAMA_CPP_DIR}/include"
    "${LLAMA_CPP_DIR}/ggml/include"
    "${LLAMA_CPP_DIR}/src"
    "${LLAMA_CPP_DIR}/ggml/src"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu"
    "${LLAMA_CPP_DIR}/common"
)

target_compile_definitions(llama_static PRIVATE
    GGML_USE_CPU
    NDEBUG
)

target_compile_options(llama_static PRIVATE
    -O3
    -ffast-math
    -fno-finite-math-only
    -pthread
)

# JNI bridge shared library
add_library(nexus-llama SHARED
    "${CMAKE_CURRENT_SOURCE_DIR}/llama_jni.cpp"
)

target_include_directories(nexus-llama PRIVATE
    "${LLAMA_CPP_DIR}/include"
    "${LLAMA_CPP_DIR}/ggml/include"
    "${LLAMA_CPP_DIR}/common"
)

target_link_libraries(nexus-llama PRIVATE
    llama_static
    android
    log
)

target_compile_options(nexus-llama PRIVATE -O3)
