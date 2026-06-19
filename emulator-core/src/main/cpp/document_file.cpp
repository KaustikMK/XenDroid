// SPDX-License-Identifier: WTFPL

#include "document_file.h"
#include <android/log.h>
#include "emulator_xendroid.h"
#include "xenia/base/logging.h"

#define LOG_TAG "DocumentFileJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)


DocumentFile::DocumentFile(JavaVM *vm, jobject documentFileObj)
        : jvm_(vm) {
    JNIEnv *env = get_env(jvm_);
    documentFileObj_ = env->NewGlobalRef(documentFileObj);
}

DocumentFile::~DocumentFile() {
    if (documentFileObj_ != nullptr) {
        JNIEnv *env = get_env(jvm_);
        env->DeleteGlobalRef(documentFileObj_);
        documentFileObj_ = nullptr;
    }
}

//
JNIEnv* DocumentFile::get_env(JavaVM *vm){
    JNIEnv* env = nullptr;
    int status = vm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        vm->AttachCurrentThread(&env, nullptr);
        //pthread_cleanup_push([](void* arg) {
        //    jvm_->DetachCurrentThread();
        //}, nullptr);
    }
    return env;
}
static std::unique_ptr<DocumentFile> _find_file_in_tree(JNIEnv *env, std::unique_ptr<DocumentFile>& dir, jobject uri) {
    std::vector<std::unique_ptr<DocumentFile>> files = dir->listFiles();
    for (auto& df : files) {
        jobject dfUri = df->getUri();
        if (dfUri != nullptr) {
            jclass uriClass = env->GetObjectClass(dfUri);
            jmethodID equalsMethod = env->GetMethodID(uriClass, "equals", "(Ljava/lang/Object;)Z");

            if (equalsMethod != nullptr && env->CallBooleanMethod(dfUri, equalsMethod, uri)) {
                return std::move(df);
            }
        }

        if (df->isDirectory()) {
            std::unique_ptr<DocumentFile> found = _find_file_in_tree(env, df, uri);
            if (found != nullptr) {
                return found;
            }
        }

    }

    return nullptr;
}

std::unique_ptr<DocumentFile> DocumentFile::find(JavaVM *vm,jobject uri) {

    if (g_doocument_file_tree == nullptr) {
        return nullptr;
    }

    JNIEnv *env = get_env(vm);
    std::unique_ptr<DocumentFile> rootDocFile=std::make_unique<DocumentFile>(vm, g_doocument_file_tree);
    return _find_file_in_tree(env, rootDocFile, uri);
}

std::unique_ptr<DocumentFile> DocumentFile::findByUriString(JavaVM *vm, const std::string& uri) {
    if (uri.empty()) {
        return nullptr;
    }
    JNIEnv *env = get_env(vm);
    jclass uri_class = env->FindClass("android/net/Uri");
    if (uri_class == nullptr) {
        LOGE("findByUriString: cannot find android/net/Uri");
        return nullptr;
    }
    jmethodID parse_method = env->GetStaticMethodID(
            uri_class, "parse", "(Ljava/lang/String;)Landroid/net/Uri;");
    if (parse_method == nullptr) {
        LOGE("findByUriString: cannot find Uri.parse");
        return nullptr;
    }
    jstring uri_string = env->NewStringUTF(uri.c_str());
    jobject uri_obj = env->CallStaticObjectMethod(uri_class, parse_method, uri_string);
    if (env->ExceptionCheck()) {
        // Clear the pending exception so it can't trip a later CheckJNI abort
        // (the device runs with -Xcheck:jni).
        env->ExceptionClear();
        LOGE("findByUriString: Uri.parse threw for '%s'", uri.c_str());
        env->DeleteLocalRef(uri_string);
        env->DeleteLocalRef(uri_class);
        return nullptr;
    }
    std::unique_ptr<DocumentFile> result = find(vm, uri_obj);
    env->DeleteLocalRef(uri_string);
    env->DeleteLocalRef(uri_obj);
    env->DeleteLocalRef(uri_class);
    return result;
}

std::unique_ptr<DocumentFile> DocumentFile::clone(std::unique_ptr<DocumentFile>& file){
    return find(file->jvm_,file->getUri());
}

std::string DocumentFile::getUriString() const {
    JNIEnv *env_ = get_env(jvm_);
    jobject uri = getUri();
    if (uri == nullptr) {
        return "";
    }
    jclass uriClass = env_->GetObjectClass(uri);
    jmethodID toStringMethod =
            env_->GetMethodID(uriClass, "toString", "()Ljava/lang/String;");
    if (toStringMethod == nullptr) {
        LOGE("getUriString: cannot find Uri.toString");
        env_->DeleteLocalRef(uri);
        env_->DeleteLocalRef(uriClass);
        return "";
    }
    jstring uriStr = (jstring) env_->CallObjectMethod(uri, toStringMethod);
    std::string result;
    if (uriStr != nullptr) {
        const char *chars = env_->GetStringUTFChars(uriStr, nullptr);
        result = chars;
        env_->ReleaseStringUTFChars(uriStr, chars);
        env_->DeleteLocalRef(uriStr);
    }
    env_->DeleteLocalRef(uriClass);
    env_->DeleteLocalRef(uri);
    return result;
}

bool DocumentFile::exists() const {
    JNIEnv *env_ = get_env(jvm_);
    jclass documentFileClass = env_->GetObjectClass(documentFileObj_);
    jmethodID existsMethod = env_->GetMethodID(documentFileClass, "exists", "()Z");

    if (existsMethod == nullptr) {
        LOGE("Cannot find exists method");
        return false;
    }

    return env_->CallBooleanMethod(documentFileObj_, existsMethod);
}

std::unique_ptr<DocumentFile> DocumentFile::getParentFile() const{
    JNIEnv *env_ = get_env(jvm_);
    jclass documentFileClass = env_->GetObjectClass(documentFileObj_);
    jmethodID getParentFileMethod = env_->GetMethodID(documentFileClass, "getParentFile",
                                                      "()Landroidx/documentfile/provider/DocumentFile;");
    if (getParentFileMethod == nullptr) {
        LOGE("Cannot find getParentFile method");
        return nullptr;
    }
    jobject parentFileObj = env_->CallObjectMethod(documentFileObj_, getParentFileMethod);
    std::unique_ptr<DocumentFile> parentFile = std::make_unique<DocumentFile>(jvm_, parentFileObj);
    env_->DeleteLocalRef(parentFileObj);
    return parentFile;
}
std::string DocumentFile::getName() const {
    JNIEnv *env_ = get_env(jvm_);
    jclass documentFileClass = env_->GetObjectClass(documentFileObj_);
    jmethodID getNameMethod = env_->GetMethodID(documentFileClass, "getName",
                                                "()Ljava/lang/String;");

    if (getNameMethod == nullptr) {
        LOGE("Cannot find getName method");
        return "";
    }

    jstring nameStr = (jstring) env_->CallObjectMethod(documentFileObj_, getNameMethod);
    if (nameStr == nullptr) {
        return "";
    }

    const char *nameChars = env_->GetStringUTFChars(nameStr, nullptr);
    std::string name(nameChars);
    env_->ReleaseStringUTFChars(nameStr, nameChars);
    env_->DeleteLocalRef(nameStr);

    return name;
}

bool DocumentFile::isDirectory() const {
    JNIEnv *env_ = get_env(jvm_);
    jclass documentFileClass = env_->GetObjectClass(documentFileObj_);
    jmethodID isDirectoryMethod = env_->GetMethodID(documentFileClass, "isDirectory", "()Z");

    if (isDirectoryMethod == nullptr) {
        LOGE("Cannot find isDirectory method");
        return false;
    }

    return env_->CallBooleanMethod(documentFileObj_, isDirectoryMethod);
}

bool DocumentFile::isFile() const {
    JNIEnv *env_ = get_env(jvm_);
    jclass documentFileClass = env_->GetObjectClass(documentFileObj_);
    jmethodID isFileMethod = env_->GetMethodID(documentFileClass, "isFile", "()Z");

    if (isFileMethod == nullptr) {
        LOGE("Cannot find isFile method");
        return false;
    }

    return env_->CallBooleanMethod(documentFileObj_, isFileMethod);
}

jobject DocumentFile::getUri() const {
    JNIEnv *env_ = get_env(jvm_);
    jclass documentFileClass = env_->GetObjectClass(documentFileObj_);
    jmethodID getUriMethod = env_->GetMethodID(documentFileClass, "getUri",
                                               "()Landroid/net/Uri;");

    if (getUriMethod == nullptr) {
        LOGE("Cannot find getUri method");
        return nullptr;
    }

    return env_->CallObjectMethod(documentFileObj_, getUriMethod);
}

std::vector<std::unique_ptr<DocumentFile>> DocumentFile::listFiles() const{
    JNIEnv *env_ = get_env(jvm_);
    jclass documentFileClass = env_->GetObjectClass(documentFileObj_);
    jmethodID listFilesMethod = env_->GetMethodID(
            documentFileClass,
            "listFiles",
            "()[Landroidx/documentfile/provider/DocumentFile;"
    );

    if (listFilesMethod == nullptr) {
        LOGE("Cannot find listFiles method");
        return std::vector<std::unique_ptr<DocumentFile>>();
    }

    jobjectArray filesArray = (jobjectArray) env_->CallObjectMethod(documentFileObj_,
                                                                    listFilesMethod);
    if (filesArray == nullptr) {
        return std::vector<std::unique_ptr<DocumentFile>>();
    }

    jsize length = env_->GetArrayLength(filesArray);
    std::vector<std::unique_ptr<DocumentFile>> result;

    for (jsize i = 0; i < length; i++) {
        jobject fileObj = env_->GetObjectArrayElement(filesArray, i);
        if (fileObj != nullptr) {
            std::unique_ptr<DocumentFile> docFile = std::make_unique<DocumentFile>(jvm_, fileObj);
            result.push_back(std::move(docFile));
            env_->DeleteLocalRef(fileObj);
        }
    }

    env_->DeleteLocalRef(filesArray);
    return result;
}


int64_t DocumentFile::length(){
    JNIEnv *env_ = get_env(jvm_);
    jclass documentFileClass = env_->GetObjectClass(documentFileObj_);
    jmethodID lengthMethod = env_->GetMethodID(documentFileClass, "length", "()J");
    return env_->CallLongMethod(documentFileObj_, lengthMethod);
}


int64_t DocumentFile::lastModified(){
    JNIEnv *env_ = get_env(jvm_);
    jclass documentFileClass = env_->GetObjectClass(documentFileObj_);
    jmethodID lastModifiedMethod = env_->GetMethodID(documentFileClass, "lastModified", "()J");
    return env_->CallLongMethod(documentFileObj_, lastModifiedMethod);
}

int DocumentFile::open_fd(const std::unique_ptr<DocumentFile>& file){
    JNIEnv *env_ = get_env(file->jvm_);
    return env_->CallStaticIntMethod(g_class_Emulator,mid_open_uri_fd,g_context,file->getUri());
}