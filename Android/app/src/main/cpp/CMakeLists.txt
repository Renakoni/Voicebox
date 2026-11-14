cmake_minimum_required(VERSION 3.22.1)
project(genie_tts_native LANGUAGES C CXX)

# C++17 for std::vector etc.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(GENIE_ENABLE_NATIVE_DECODER "Build the native ORT decoder loop" OFF)

if(ANDROID)
    set(JTALK_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/open_jtalk)
    set(JTALK_PARTS jpcommon mecab/src mecab2njd njd njd2jpcommon
        njd_set_accent_phrase njd_set_accent_type njd_set_digit njd_set_long_vowel
        njd_set_pronunciation njd_set_unvoiced_vowel text2mecab)
    foreach(PART ${JTALK_PARTS})
        file(GLOB PART_SOURCES CONFIGURE_DEPENDS "${JTALK_ROOT}/${PART}/*.c" "${JTALK_ROOT}/${PART}/*.cpp")
        list(APPEND JTALK_SOURCES ${PART_SOURCES})
        list(APPEND JTALK_INCLUDES "${JTALK_ROOT}/${PART}")
    endforeach()
    add_library(genie_japanese SHARED japanese_frontend.cpp ${JTALK_SOURCES})
    target_include_directories(genie_japanese PRIVATE ${JTALK_INCLUDES})
    target_compile_definitions(genie_japanese PRIVATE HAVE_CONFIG_H DIC_VERSION=102
        MECAB_DEFAULT_RC="dummy" PACKAGE="open_jtalk" VERSION="1.11"
        CHARSET_UTF_8 MECAB_UTF8_USE_ONLY)

    file(GLOB SILK_SOURCES CONFIGURE_DEPENDS "third_party/silk/src/*.c")
    add_library(genie_silk SHARED qq_silk.cpp ${SILK_SOURCES})
    target_include_directories(genie_silk PRIVATE third_party/silk/interface third_party/silk/src)
    target_compile_definitions(genie_silk PRIVATE NO_ASM)
endif()

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

if(ANDROID AND GENIE_ENABLE_NATIVE_DECODER)
    set(DECODER_NATIVE_SOURCE_DIR
        ${CMAKE_CURRENT_SOURCE_DIR}
    )
    set(DECODER_ORT_INCLUDE_DIR
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party/onnxruntime/1.27.0/include
    )
    add_library(native_decoder_loop SHARED
        ${DECODER_NATIVE_SOURCE_DIR}/native_decoder_loop.cpp
        ${DECODER_NATIVE_SOURCE_DIR}/native_semantic_slicing.cpp
    )
    target_include_directories(native_decoder_loop PRIVATE
        ${DECODER_NATIVE_SOURCE_DIR}
        ${DECODER_ORT_INCLUDE_DIR}
    )
    target_link_libraries(native_decoder_loop ${log-lib})
endif()

include(CTest)

if(BUILD_TESTING AND NOT ANDROID)
    add_executable(reference_resampler_test
        reference_resampler.cpp
        reference_resampler_test.cpp
        ${SOXR_CONSTANT_SOURCES}
    )
    target_include_directories(reference_resampler_test PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${SOXR_SOURCE_DIR}
    )
    target_compile_definitions(reference_resampler_test PRIVATE
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
    add_test(NAME reference_resampler_test COMMAND reference_resampler_test)
endif()
