cmake_minimum_required(VERSION 3.22)
project(assist_whisper C CXX)

# whisper.cpp v1.9.4 (git submodule), CPU only, tuned for the S23 Ultra's cores (ARMv8.2 + dot product + fp16 + int8 matrix multiply).
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)

add_subdirectory(${CMAKE_SOURCE_DIR}/../../../../third_party/whisper.cpp whisper-build)

add_library(assistwhisper SHARED jni.cpp)
target_link_libraries(assistwhisper whisper log)
target_compile_options(assistwhisper PRIVATE -O3)
