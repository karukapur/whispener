cmake_minimum_required(VERSION 3.22.1)
project(listener_whisper LANGUAGES C CXX)

include(FetchContent)
find_package(Git REQUIRED)
FetchContent_Declare(
    vulkan_headers
    GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Headers.git
    GIT_TAG v1.3.296
    GIT_SHALLOW TRUE
    EXCLUDE_FROM_ALL
)
FetchContent_Declare(
    SPIRV-Headers
    GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Headers.git
    GIT_TAG vulkan-sdk-1.3.296.0
    GIT_SHALLOW TRUE
    OVERRIDE_FIND_PACKAGE
    EXCLUDE_FROM_ALL
)
FetchContent_MakeAvailable(vulkan_headers SPIRV-Headers)

set(Vulkan_INCLUDE_DIR "${vulkan_headers_SOURCE_DIR}/include" CACHE PATH "" FORCE)
set("SPIRV-Headers_DIR" "${CMAKE_CURRENT_SOURCE_DIR}/cmake" CACHE PATH "" FORCE)
if(CMAKE_HOST_APPLE)
    set(_listener_shader_host darwin-x86_64)
elseif(CMAKE_HOST_WIN32)
    set(_listener_shader_host windows-x86_64)
else()
    set(_listener_shader_host linux-x86_64)
endif()
set(Vulkan_GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/${_listener_shader_host}/glslc" CACHE FILEPATH "" FORCE)
find_program(LISTENER_HOST_C_COMPILER NAMES clang gcc NO_CMAKE_FIND_ROOT_PATH REQUIRED)
find_program(LISTENER_HOST_CXX_COMPILER NAMES clang++ g++ NO_CMAKE_FIND_ROOT_PATH REQUIRED)
configure_file(
    "${CMAKE_CURRENT_SOURCE_DIR}/cmake/host-vulkan-toolchain.cmake.in"
    "${CMAKE_BINARY_DIR}/listener-host-vulkan-toolchain.cmake"
    @ONLY
)
set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN "${CMAKE_BINARY_DIR}/listener-host-vulkan-toolchain.cmake" CACHE FILEPATH "" FORCE)

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)

FetchContent_Declare(
    whispercpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.9.2
    GIT_SHALLOW TRUE
    PATCH_COMMAND ${CMAKE_COMMAND}
        -DGIT_EXECUTABLE=${GIT_EXECUTABLE}
        -DPATCH_FILE=${CMAKE_CURRENT_SOURCE_DIR}/patches/whisper-vulkan-host-ninja.patch
        -DSOURCE_DIR=<SOURCE_DIR>
        -P ${CMAKE_CURRENT_SOURCE_DIR}/cmake/apply-whisper-patch.cmake
)
FetchContent_MakeAvailable(whispercpp)

if(TARGET ggml-vulkan)
    target_include_directories(
        ggml-vulkan
        SYSTEM PRIVATE
        "${SPIRV-Headers_SOURCE_DIR}/include"
    )
endif()

add_library(listener_whisper SHARED whisper_jni.cpp)
target_link_libraries(listener_whisper PRIVATE whisper android log)
