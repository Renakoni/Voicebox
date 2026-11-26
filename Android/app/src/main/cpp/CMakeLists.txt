cmake_minimum_required(VERSION 3.22.1)
project(voicebox_silk LANGUAGES C CXX)

# C++17 for std::vector etc.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

file(GLOB SILK_SOURCES CONFIGURE_DEPENDS "third_party/silk/src/*.c")
add_library(voicebox_silk SHARED qq_silk.cpp ${SILK_SOURCES})
target_include_directories(voicebox_silk PRIVATE third_party/silk/interface third_party/silk/src)
target_compile_definitions(voicebox_silk PRIVATE NO_ASM)

add_library(voicebox_device_npu SHARED device_npu.cpp vulkan_compute.cpp)
target_link_libraries(voicebox_device_npu PRIVATE neuralnetworks dl vulkan)
