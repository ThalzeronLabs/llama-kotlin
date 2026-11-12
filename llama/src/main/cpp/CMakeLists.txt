cmake_minimum_required(VERSION 3.22)

project("llama-kotlin" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Pick the right CPU code path per ABI.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM" CACHE STRING "" FORCE)
    # One CPU backend per feature level (armv8.0, +dotprod, +fp16, +i8mm, +sve2), each a small shared library; at
    # start-up the JNI side loads the best one the phone supports (see load_cpu_backend()). A single build for
    # armv8.2+dotprod crashed with SIGILL on CPUs without dot-product (Cortex-A53/A73 phones, the ARM translation of
    # x86 emulators). The variants need ggml as shared libraries.
    set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(GGML_SYSTEM_ARCH "x86" CACHE STRING "" FORCE)
endif()

# Cap parallel compile jobs: llama.cpp has hundreds of heavy C++ files, and an unbounded ninja can exhaust RAM.
set_property(GLOBAL APPEND PROPERTY JOB_POOLS compile_pool=4)
set(CMAKE_JOB_POOL_COMPILE compile_pool)

set(LLAMA_CPP_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
add_subdirectory(${LLAMA_CPP_DIR} build-llama)

add_library(llama_kotlin SHARED llama_jni.cpp)

if(ANDROID)
    target_link_libraries(llama_kotlin PRIVATE llama android log)
else()
    # Desktop build of the same JNI library, used only by the host integration tests (see CONTRIBUTING.md).
    find_package(JNI REQUIRED)
    target_include_directories(llama_kotlin PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(llama_kotlin PRIVATE llama)
endif()

# Keep only the JNI entry points visible; everything else stays internal to the .so.
target_compile_options(llama_kotlin PRIVATE -fvisibility=hidden)
if(NOT APPLE)
    target_link_options(llama_kotlin PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
endif()
