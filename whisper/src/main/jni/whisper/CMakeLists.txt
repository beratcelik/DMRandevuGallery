cmake_minimum_required(VERSION 3.22)

project(dmrandevu-whisper)

set(CMAKE_CXX_STANDARD 17)

# The vendored whisper.cpp checkout, pinned by the git submodule at third_party/whisper.cpp.
set(WHISPER_LIB_DIR ${CMAKE_SOURCE_DIR}/../../../../../third_party/whisper.cpp)

if (NOT EXISTS ${WHISPER_LIB_DIR}/src/whisper.cpp)
    message(FATAL_ERROR
        "whisper.cpp is missing. Run: git submodule update --init --recursive")
endif ()

file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)"
       VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if (CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1})
else ()
    set(WHISPER_VERSION "unknown")
endif ()
message(STATUS "whisper.cpp version: ${WHISPER_VERSION}")

find_library(LOG_LIB log)
include(FetchContent)

add_library(whisper SHARED
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${CMAKE_SOURCE_DIR}/jni.c
)

target_compile_definitions(whisper PUBLIC GGML_USE_CPU)
target_compile_definitions(whisper PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")

# Every phone this app supports is arm64 with half-precision, which the ABI filter already
# guarantees. The upstream example builds three variants and picks at runtime; there is only
# one to pick from here.
target_compile_options(whisper PRIVATE -march=armv8.2-a+fp16)
set(GGML_COMPILE_OPTIONS -march=armv8.2-a+fp16)

# Unconditional: the Gradle side forces a Release configuration, but if that is ever lost this
# still must not fall back to an unoptimised build — at -O0 a single recognition pass took ten
# minutes on the operator's phone instead of seconds.
target_compile_options(whisper PRIVATE -O3)
target_compile_options(whisper PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
target_compile_options(whisper PRIVATE -ffunction-sections -fdata-sections)
target_link_options(whisper PRIVATE -Wl,--gc-sections)
target_link_options(whisper PRIVATE -Wl,--exclude-libs,ALL)

FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
FetchContent_MakeAvailable(ggml)
target_compile_options(ggml PRIVATE ${GGML_COMPILE_OPTIONS})
target_link_libraries(whisper ${LOG_LIB} android ggml)

target_include_directories(whisper PRIVATE
    ${WHISPER_LIB_DIR}
    ${WHISPER_LIB_DIR}/src
    ${WHISPER_LIB_DIR}/include
    ${WHISPER_LIB_DIR}/ggml/include
    ${WHISPER_LIB_DIR}/ggml/src
)
