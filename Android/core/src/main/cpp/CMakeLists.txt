cmake_minimum_required(VERSION 3.22.1)
project(voicebox_audio LANGUAGES C CXX)

# C++17 for std::vector etc.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(SOXR_SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/soxr)
set(SOXR_CONSTANT_SOURCES
    ${SOXR_SOURCE_DIR}/soxr.c
    ${SOXR_SOURCE_DIR}/data-io.c
    ${SOXR_SOURCE_DIR}/dbesi0.c
    ${SOXR_SOURCE_DIR}/filter.c
    ${SOXR_SOURCE_DIR}/fft4g64.c
    ${SOXR_SOURCE_DIR}/cr.c
    ${SOXR_SOURCE_DIR}/cr32.c
    ${SOXR_SOURCE_DIR}/fft4g32.c
)

add_library(native_resampler SHARED
    reference_resampler.cpp
    ${SOXR_CONSTANT_SOURCES}
)
target_include_directories(native_resampler PRIVATE
    ${SOXR_SOURCE_DIR}
)
target_compile_definitions(native_resampler PRIVATE
    SOXR_LIB=1
    WITH_CR32=1
    WITH_CR32S=0
    WITH_CR64=0
    WITH_CR64S=0
    WITH_VR32=0
    WITH_PFFFT=0
    WITH_AVFFT=0
    WITH_OPENMP=0
    WITH_DEV_TRACE=0
    HAVE_BIGENDIAN=0
    HAVE_LRINT=1
)
if(ANDROID)
    find_library(log-lib log)
    target_link_libraries(native_resampler ${log-lib})
endif()
