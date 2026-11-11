cmake_minimum_required(VERSION 3.22.1)
project("myapp_llm" LANGUAGES C CXX)

# The app is built in debug mode, which turns off compiler optimisation and makes the model several
# times slower. The native code is always built optimised.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

# Use the 64-bit ARM instructions that current phones have (dot-product and half-float), which the
# model's math uses. All ARMv8.2 phones (2018 and later) have them.
add_compile_options(-march=armv8.2-a+dotprod+fp16)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp, pinned to the release used by the Android example. Built as static libraries.
include(FetchContent)
FetchContent_Declare(
        llama
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp
        GIT_TAG v0.9.0
        GIT_SHALLOW TRUE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
# llama-common (the helper library the chat bridge links) is off unless asked for.
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(llama)

add_library(myapp_llm SHARED llm_jni.cpp)

target_include_directories(myapp_llm PRIVATE
        ${llama_SOURCE_DIR}
        ${llama_SOURCE_DIR}/common
        ${llama_SOURCE_DIR}/include
        ${llama_SOURCE_DIR}/ggml/include
        ${llama_SOURCE_DIR}/ggml/src)

target_link_libraries(myapp_llm
        llama
        llama-common
        android
        log)
