cmake_minimum_required(VERSION 3.22.1)
project(voicebox_gptsovits LANGUAGES C CXX)

# C++17 for std::vector etc.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(JTALK_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/open_jtalk)
set(JTALK_PARTS jpcommon mecab/src mecab2njd njd njd2jpcommon
    njd_set_accent_phrase njd_set_accent_type njd_set_digit njd_set_long_vowel
    njd_set_pronunciation njd_set_unvoiced_vowel text2mecab)
foreach(PART ${JTALK_PARTS})
    file(GLOB PART_SOURCES CONFIGURE_DEPENDS "${JTALK_ROOT}/${PART}/*.c" "${JTALK_ROOT}/${PART}/*.cpp")
    list(APPEND JTALK_SOURCES ${PART_SOURCES})
    list(APPEND JTALK_INCLUDES "${JTALK_ROOT}/${PART}")
endforeach()
add_library(voicebox_japanese SHARED japanese_frontend.cpp ${JTALK_SOURCES})
target_include_directories(voicebox_japanese PRIVATE ${JTALK_INCLUDES})
target_compile_definitions(voicebox_japanese PRIVATE HAVE_CONFIG_H DIC_VERSION=102
    MECAB_DEFAULT_RC="dummy" PACKAGE="open_jtalk" VERSION="1.11"
    CHARSET_UTF_8 MECAB_UTF8_USE_ONLY)

find_library(log-lib log)
add_library(native_decoder_loop SHARED native_decoder_loop.cpp native_semantic_slicing.cpp qnn_session.cpp)
target_include_directories(native_decoder_loop PRIVATE third_party/onnxruntime/1.27.0/include)
target_link_libraries(native_decoder_loop ${log-lib})
