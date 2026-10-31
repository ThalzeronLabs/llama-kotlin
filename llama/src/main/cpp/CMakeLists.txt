cmake_minimum_required(VERSION 3.22)

project("llama-kotlin" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Pick the right CPU code path per ABI: ARM gets the NEON/dotprod kernels, x86_64 (emulators) the generic one.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM" CACHE STRING "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(GGML_SYSTEM_ARCH "x86" CACHE STRING "" FORCE)
endif()

# Cap parallel compile jobs: llama.cpp has hundreds of heavy C++ files, and an unbounded ninja can exhaust RAM.
set_property(GLOBAL APPEND PROPERTY JOB_POOLS compile_pool=4)
set(CMAKE_JOB_POOL_COMPILE compile_pool)

set(LLAMA_CPP_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
add_subdirectory(${LLAMA_CPP_DIR} build-llama)

add_library(llama_kotlin SHARED llama_jni.cpp)

target_link_libraries(llama_kotlin PRIVATE llama android log)

# Keep only the JNI entry points visible; everything else stays internal to the .so.
target_compile_options(llama_kotlin PRIVATE -fvisibility=hidden)
target_link_options(llama_kotlin PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
