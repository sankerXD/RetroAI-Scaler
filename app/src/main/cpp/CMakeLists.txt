cmake_minimum_required(VERSION 3.22.1)

project("retro_ai_scaler" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# OpenMP support for multi-threading on ARM cores
find_package(OpenMP)
if(OpenMP_CXX_FOUND)
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${OpenMP_C_FLAGS}")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${OpenMP_CXX_FLAGS}")
endif()

# Check for NCNN prebuilt library
set(NCNN_DIR "${CMAKE_CURRENT_SOURCE_DIR}/libs/ncnn/${ANDROID_ABI}/lib/cmake/ncnn")
if(EXISTS "${NCNN_DIR}/ncnnConfig.cmake")
    set(ncnn_DIR "${NCNN_DIR}")
    find_package(ncnn REQUIRED)
    include_directories("${CMAKE_CURRENT_SOURCE_DIR}/libs/ncnn/${ANDROID_ABI}/include")
    message(STATUS "Found NCNN prebuilt at ${NCNN_DIR}")
endif()

# Source files
set(SOURCE_FILES
    native-lib.cpp
    common/cpu_affinity.cpp
    capture/hw_buffer_reader.cpp
    capture/frame_cropper.cpp
    ai/espcn_inference.cpp
    render/gl_renderer.cpp
)

add_library(
    retro_ai_scaler
    SHARED
    ${SOURCE_FILES}
)

target_include_directories(
    retro_ai_scaler
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/common
    ${CMAKE_CURRENT_SOURCE_DIR}/capture
    ${CMAKE_CURRENT_SOURCE_DIR}/ai
    ${CMAKE_CURRENT_SOURCE_DIR}/render
)

# Find Android system libraries
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)
find_library(egl-lib EGL)
find_library(gles3-lib GLESv3)
find_library(vulkan-lib vulkan)

target_link_libraries(
    retro_ai_scaler
    PRIVATE
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
    ${egl-lib}
    ${gles3-lib}
    ${vulkan-lib}
)

if(TARGET ncnn)
    target_link_libraries(retro_ai_scaler PRIVATE ncnn)
endif()

if(OpenMP_CXX_FOUND)
    target_link_libraries(retro_ai_scaler PRIVATE OpenMP::OpenMP_CXX)
endif()
