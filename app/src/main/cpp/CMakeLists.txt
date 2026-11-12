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

# Image tool: stable-diffusion.cpp (GGUF image models). It is built with the 8-bit matrix instructions (i8mm), which
# make quantized (Q8) models faster. The Galaxy Z Fold8 (Snapdragon 8 Elite Gen 5) has them; a phone without them
# would crash in this library. It has its own copy of ggml, which would clash with
# llama.cpp's ggml targets, so it is built as a separate CMake project (ExternalProject) and linked as
# prebuilt static libraries. Its ggml libraries get "sd_" names so they do not clash with llama's.
include(ExternalProject)
set(SD_TAG master-1086-465adf3)
set(SD_SRC ${CMAKE_BINARY_DIR}/stable-diffusion-src)
set(SD_BIN ${CMAKE_BINARY_DIR}/stable-diffusion-build)
set(SD_PREFIX ${CMAKE_BINARY_DIR}/stable-diffusion-install)
set(SD_LIBS ${SD_PREFIX}/lib)

ExternalProject_Add(stable_diffusion_ext
        GIT_REPOSITORY https://github.com/leejet/stable-diffusion.cpp
        GIT_TAG ${SD_TAG}
        GIT_SHALLOW TRUE
        GIT_SUBMODULES_RECURSE TRUE
        SOURCE_DIR ${SD_SRC}
        BINARY_DIR ${SD_BIN}
        INSTALL_DIR ${SD_PREFIX}
        CMAKE_ARGS
            -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
            -DANDROID_ABI=${ANDROID_ABI}
            -DANDROID_PLATFORM=${ANDROID_PLATFORM}
            -DCMAKE_BUILD_TYPE=Release
            -DCMAKE_C_FLAGS=-march=armv8.2-a+dotprod+fp16+i8mm
            -DCMAKE_CXX_FLAGS=-march=armv8.2-a+dotprod+fp16+i8mm
            -DCMAKE_INSTALL_PREFIX=${SD_PREFIX}
            -DSD_BUILD_EXAMPLES=OFF
            -DSD_BUILD_SHARED_LIBS=OFF
            -DGGML_OPENMP=OFF
        BUILD_BYPRODUCTS
            ${SD_LIBS}/libstable-diffusion.a
            ${SD_LIBS}/libggml.a
            ${SD_LIBS}/libggml-base.a
            ${SD_LIBS}/libggml-cpu.a)

add_library(sd_stable_diffusion STATIC IMPORTED GLOBAL)
set_target_properties(sd_stable_diffusion PROPERTIES IMPORTED_LOCATION ${SD_LIBS}/libstable-diffusion.a)
add_library(sd_ggml STATIC IMPORTED GLOBAL)
set_target_properties(sd_ggml PROPERTIES IMPORTED_LOCATION ${SD_LIBS}/libggml.a)
add_library(sd_ggml_base STATIC IMPORTED GLOBAL)
set_target_properties(sd_ggml_base PROPERTIES IMPORTED_LOCATION ${SD_LIBS}/libggml-base.a)
add_library(sd_ggml_cpu STATIC IMPORTED GLOBAL)
set_target_properties(sd_ggml_cpu PROPERTIES IMPORTED_LOCATION ${SD_LIBS}/libggml-cpu.a)
foreach(t sd_stable_diffusion sd_ggml sd_ggml_base sd_ggml_cpu)
    add_dependencies(${t} stable_diffusion_ext)
endforeach()

add_library(myapp_sd SHARED sd_jni.cpp)

target_include_directories(myapp_sd PRIVATE ${SD_SRC}/include)

target_link_libraries(myapp_sd
        sd_stable_diffusion
        sd_ggml_cpu
        sd_ggml_base
        sd_ggml
        android
        log)

# Each library keeps the copies of ggml inside it to itself, so the two copies never see each other's symbols.
target_link_options(myapp_llm PRIVATE "-Wl,--exclude-libs,ALL")
target_link_options(myapp_sd PRIVATE "-Wl,--exclude-libs,ALL")
