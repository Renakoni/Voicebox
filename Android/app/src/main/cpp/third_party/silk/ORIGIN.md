# SILK fixed-point SDK

Copied without source changes from cinit/QAuxiliary commit
`c4fb9fa80edd99a6afd6c441a1d820f31600fcd2`,
`libs/silk/silk-v3-decoder/silk/{src,interface}`.

The enclosing silk-v3-decoder LICENSE is included here. Individual SDK files
retain Skype Limited's copyright, redistribution conditions and disclaimer;
the enclosing MIT license does not replace these notices.

Only portable C sources are built, with NO_ASM. Genie supplies its own JNI
adapter and uses the existing libsoxr resampler for 32 kHz to 24 kHz conversion.
