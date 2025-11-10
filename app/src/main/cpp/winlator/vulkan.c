#include <vulkan/vulkan.h>

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <sys/stat.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include "../adrenotools/include/adrenotools/driver.h"

#define LOG_TAG "System.out"
#define printf(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

VkInstance instance;
VkPhysicalDevice physicalDevice;
PFN_vkGetPhysicalDeviceProperties getPhysicalDeviceProperties;
PFN_vkEnumerateDeviceExtensionProperties enumerateDeviceExtensionProperties;
PFN_vkEnumeratePhysicalDevices enumeratePhysicalDevices;
PFN_vkDestroyInstance destroyInstance;

static void *vulkan_handle = NULL;


static char *get_native_library_dir(JNIEnv *env, jobject context) {
    char *native_libdir;

    if (context != NULL) {
        jclass class_ = (*env)->FindClass(env,"com/winlator/cmod/core/AppUtils");
        jmethodID getNativeLibraryDir = (*env)->GetStaticMethodID(env, class_, "getNativeLibDir",
                                                               "(Landroid/content/Context;)Ljava/lang/String;");
        jstring nativeLibDir = (jstring)(*env)->CallStaticObjectMethod(env, class_,
                                                                     getNativeLibraryDir,
                                                                     context);
        if (nativeLibDir)
            native_libdir = (char *)(*env)->GetStringUTFChars(env, nativeLibDir, NULL);
    }

    return native_libdir;
}

static char *get_driver_path(JNIEnv *env, jobject context, const char *driver_name) {
    char *driver_path;
    char *absolute_path;

    jclass contextWrapperClass = (*env)->FindClass(env, "android/content/ContextWrapper");
    jmethodID  getFilesDir = (*env)->GetMethodID(env, contextWrapperClass, "getFilesDir", "()Ljava/io/File;");
    jobject  filesDirObj = (*env)->CallObjectMethod(env, context, getFilesDir);
    jclass fileClass = (*env)->GetObjectClass(env, filesDirObj);
    jmethodID getAbsolutePath = (*env)->GetMethodID(env, fileClass, "getAbsolutePath", "()Ljava/lang/String;");
    jstring absolutePath = (jstring)(*env)->CallObjectMethod(env,filesDirObj,
                                                             getAbsolutePath);

    if (absolutePath) {
        absolute_path = (char *)(*env)->GetStringUTFChars(env,absolutePath, NULL);
        asprintf(&driver_path, "%s/contents/adrenotools/%s/", absolute_path, driver_name);
        (*env)->ReleaseStringUTFChars(env,absolutePath, absolute_path);
    }

    return driver_path;
}

static char *get_library_name(JNIEnv *env, jobject context, const char *driver_name) {
    char *library_name;

    jclass adrenotoolsManager = (*env)->FindClass(env, "com/winlator/cmod/contents/AdrenotoolsManager");
    jmethodID constructor = (*env)->GetMethodID(env, adrenotoolsManager, "<init>", "(Landroid/content/Context;)V");
    jobject  adrenotoolsManagerObj = (*env)->NewObject(env, adrenotoolsManager, constructor, context);
    jmethodID getLibraryName = (*env)->GetMethodID(env, adrenotoolsManager, "getLibraryName","(Ljava/lang/String;)Ljava/lang/String;");
    jstring driverName = (*env)->NewStringUTF(env, driver_name);
    jstring libraryName = (jstring)(*env)->CallObjectMethod(env, adrenotoolsManagerObj,getLibraryName, driverName);

    if (libraryName)
        library_name = (char *)(*env)->GetStringUTFChars(env, libraryName, NULL);

    return library_name;
}

static void init_original_vulkan() {
    vulkan_handle = dlopen("/system/lib64/libvulkan.so", RTLD_LOCAL | RTLD_NOW);
}

static void init_vulkan(JNIEnv  *env, jobject context, const char *driver_name) {
    char *tmpdir;
    char *library_name;
    char *native_library_dir;

    const char *driver_path = get_driver_path(env, context, driver_name);

    if (driver_path && (access(driver_path, F_OK) == 0)) {
        library_name = get_library_name(env, context, driver_name);
        native_library_dir = get_native_library_dir(env, context);
        asprintf(&tmpdir, "%s%s", driver_path, "temp");
        mkdir(tmpdir, S_IRWXU | S_IRWXG);
    }

    vulkan_handle = adrenotools_open_libvulkan(RTLD_LOCAL | RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, tmpdir, native_library_dir, driver_path, library_name, NULL, NULL);
}

static VkResult create_instance(jstring driverName, JNIEnv *env, jobject context) {
    VkResult result;
    VkInstanceCreateInfo create_info = {};
    char *driver_name = NULL;

    if (driverName != NULL)
        driver_name = (char *)(*env)->GetStringUTFChars(env, driverName, NULL);

    if (driver_name && strcmp(driver_name, "System"))
        init_vulkan(env, context, driver_name);
    else
        init_original_vulkan();

    if (!vulkan_handle)
        return VK_ERROR_INITIALIZATION_FAILED;

    PFN_vkGetInstanceProcAddr gip = (PFN_vkGetInstanceProcAddr)dlsym(vulkan_handle, "vkGetInstanceProcAddr");
    PFN_vkCreateInstance createInstance = (PFN_vkCreateInstance)dlsym(vulkan_handle, "vkCreateInstance");

    create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create_info.pNext = NULL;
    create_info.flags = 0;
    create_info.pApplicationInfo = NULL;
    create_info.enabledLayerCount = 0;
    create_info.enabledExtensionCount = 0;

    result = createInstance(&create_info, NULL, &instance);

    if (result == VK_SUCCESS) {
        getPhysicalDeviceProperties = (PFN_vkGetPhysicalDeviceProperties)gip(instance, "vkGetPhysicalDeviceProperties");
        destroyInstance = (PFN_vkDestroyInstance)gip(instance, "vkDestroyInstance");
        enumerateDeviceExtensionProperties = (PFN_vkEnumerateDeviceExtensionProperties)gip(instance, "vkEnumerateDeviceExtensionProperties");
        enumeratePhysicalDevices = (PFN_vkEnumeratePhysicalDevices)gip(instance, "vkEnumeratePhysicalDevices");

        if (!getPhysicalDeviceProperties || !destroyInstance || !enumerateDeviceExtensionProperties || !enumeratePhysicalDevices)
            return VK_ERROR_INITIALIZATION_FAILED;
    }

    return result;
}

static VkResult enumerate_physical_devices() {
    VkResult result;
    uint32_t deviceCount;

    result = enumeratePhysicalDevices(instance, &deviceCount, NULL);

    VkPhysicalDevice pdevices[deviceCount];
    if (result == VK_SUCCESS && deviceCount > 0) {
        result = enumeratePhysicalDevices(instance, &deviceCount, pdevices);
        physicalDevice = pdevices[0];
    }

    return result;
}

JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_core_GPUInformation_getVersion(JNIEnv *env, jclass obj, jstring driverName, jobject context) {
    VkPhysicalDeviceProperties props = {};
    char *driverVersion;

    if  (create_instance(driverName, env, context) != VK_SUCCESS) {
        printf("Failed to create instance");
        return (*env)->NewStringUTF(env, "Unknown");
    }

    if (enumerate_physical_devices() != VK_SUCCESS) {
        printf("Failed to query physical devices");
        return (*env)->NewStringUTF(env, "Unknown");
    }

    getPhysicalDeviceProperties(physicalDevice, &props);
    uint32_t vk_driver_major = VK_VERSION_MAJOR(props.driverVersion);
    uint32_t vk_driver_minor = VK_VERSION_MINOR(props.driverVersion);
    uint32_t vk_driver_patch = VK_VERSION_PATCH(props.driverVersion);
    asprintf(&driverVersion, "%d.%d.%d", vk_driver_major, vk_driver_minor, vk_driver_patch);

    destroyInstance(instance, NULL);

    if (vulkan_handle)
        dlclose(vulkan_handle);

    return (*env)->NewStringUTF(env, driverVersion);
}

JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_core_GPUInformation_getRenderer(JNIEnv *env, jclass obj, jstring driverName, jobject context) {
    VkPhysicalDeviceProperties props = {};
    char *renderer;


    if  (create_instance(driverName, env, context) != VK_SUCCESS) {
        printf("Failed to create instance");
        return (*env)->NewStringUTF(env, "Unknown");
    }

    if (enumerate_physical_devices() != VK_SUCCESS) {
        printf("Failed to query physical devices");
        return (*env)->NewStringUTF(env, "Unknown");
    }

    getPhysicalDeviceProperties(physicalDevice, &props);
    asprintf(&renderer, "%s", props.deviceName);

    destroyInstance(instance, NULL);

    if (vulkan_handle)
        dlclose(vulkan_handle);

    return (*env)->NewStringUTF(env, renderer);
}

JNIEXPORT jobjectArray JNICALL
Java_com_winlator_cmod_core_GPUInformation_enumerateExtensions(JNIEnv *env, jclass obj, jstring driverName, jobject context) {
    jobjectArray extensions;
    VkResult result;
    uint32_t extensionCount;

    if  (create_instance(driverName, env, context) != VK_SUCCESS) {
        printf("Failed to create instance");
        return (*env)->NewObjectArray(env, 0, (*env)->FindClass(env, "java/lang/String"), (*env)->NewStringUTF(env, ""));
    }

    if (enumerate_physical_devices() != VK_SUCCESS) {
        printf("Failed to query physical devices");
        return (*env)->NewObjectArray(env, 0, (*env)->FindClass(env, "java/lang/String"), (*env)->NewStringUTF(env, ""));
    }

    result = enumerateDeviceExtensionProperties(physicalDevice, NULL, &extensionCount, NULL);

    if (result == VK_SUCCESS && extensionCount > 0) {
        VkExtensionProperties extensionProperties[extensionCount];
        enumerateDeviceExtensionProperties(physicalDevice, NULL, &extensionCount,
                                           extensionProperties);
        extensions = (jobjectArray) (*env)->NewObjectArray(env, extensionCount,
                                                           (*env)->FindClass(env, "java/lang/String"),
                                                           (*env)->NewStringUTF(env, ""));

        for (int i = 0; i < extensionCount; i++) {
            (*env)->SetObjectArrayElement(env, extensions, i,
                                          (*env)->NewStringUTF(env, extensionProperties[i].extensionName));
        }
    }
    else {
        printf("Failed to enumerate device extensions");
        return (*env)->NewObjectArray(env, 0, (*env)->FindClass(env, "java/lang/String"), (*env)->NewStringUTF(env, ""));

    }

    destroyInstance(instance, NULL);

    if (vulkan_handle)
        dlclose(vulkan_handle);

    return extensions;
}