# OpenJTalk frontend

Source: https://github.com/r9y9/open_jtalk, commit `462fc38e7520aa89e4d32b2611749208528c901e`.
The C/C++ headers and sources for MeCab, NJD, JPCommon and text normalization are copied unchanged from `src/`.
`mecab/src/config.h` is the Android/bionic build configuration. CMake and the JNI adapter live outside this directory.
No HTS waveform synthesis code or voice model is used.

Frontend stages follow pyopenjtalk 0.4.1. Kotlin prosody extraction follows Genie-TTS `JapaneseG2P.py`.
The module's [language asset tool](../../../../../tools/prepare_language_assets.py) downloads and verifies the OpenJTalk UTF-8 1.11 dictionary.
Source licenses remain here and are also bundled in the GPT bridge under `assets/language-licenses/`.
