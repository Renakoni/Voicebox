#include <jni.h>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

int ming_cli_main(int argc, char** argv);
extern char** environ;

static std::string utf8(JNIEnv* env, jstring value) {
    const char* bytes = env->GetStringUTFChars(value, nullptr);
    std::string result(bytes);
    env->ReleaseStringUTFChars(value, bytes);
    return result;
}

// Runs only in Voicebox's disposable :ming process, never in the UI/ORT process.
extern "C" JNIEXPORT jint JNICALL BRIDGE_METHOD(
    JNIEnv* env, jclass, jobjectArray arguments, jobjectArray variables, jstring log_path) {
    std::vector<std::string> keys;
    for (char** item = environ; *item; ++item) {
        std::string key(*item, std::string(*item).find('='));
        if (key.rfind("MING_", 0) == 0 || key == "ADSP_LIBRARY_PATH") keys.push_back(key);
    }
    for (const auto& key : keys) unsetenv(key.c_str());
    for (jsize i = 0; i < env->GetArrayLength(variables); ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(variables, i));
        auto variable = utf8(env, value);
        env->DeleteLocalRef(value);
        auto equal = variable.find('=');
        if (equal != std::string::npos) setenv(variable.substr(0, equal).c_str(), variable.substr(equal + 1).c_str(), 1);
    }
    std::vector<std::string> values;
    for (jsize i = 0; i < env->GetArrayLength(arguments); ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
        values.push_back(utf8(env, value));
        env->DeleteLocalRef(value);
    }
    std::vector<char*> argv;
    for (auto& value : values) argv.push_back(value.data());
    argv.push_back(nullptr);
    std::ofstream log(utf8(env, log_path));
    if (!log) return -1;
    auto* out = std::cout.rdbuf(log.rdbuf());
    auto* err = std::cerr.rdbuf(log.rdbuf());
    int code;
    try { code = ming_cli_main(static_cast<int>(values.size()), argv.data()); }
    catch (const std::exception& error) { log << error.what(); code = -1; }
    std::cout.rdbuf(out);
    std::cerr.rdbuf(err);
    return code;
}
