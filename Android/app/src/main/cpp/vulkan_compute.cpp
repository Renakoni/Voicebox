#include <jni.h>
#include <vulkan/vulkan.h>
#include <algorithm>
#include <vector>

// A driver or allocation failure must not permanently disable downloads on this system image.
static bool succeeded(VkResult result, bool& conclusive) {
    if (result == VK_SUCCESS) return true;
    if (result != VK_ERROR_INCOMPATIBLE_DRIVER && result != VK_ERROR_FEATURE_NOT_PRESENT &&
        result != VK_ERROR_EXTENSION_NOT_PRESENT) conclusive = false;
    return false;
}

static int computeLevel(VkPhysicalDevice gpu, uint32_t api, bool& conclusive) {
    VkPhysicalDeviceProperties properties;
    vkGetPhysicalDeviceProperties(gpu, &properties);
    if (properties.deviceType != VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU &&
        properties.deviceType != VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) return 0;

    VkPhysicalDevice16BitStorageFeatures storage{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES};
    if (std::min(api, properties.apiVersion) >= VK_API_VERSION_1_2) {
        VkPhysicalDeviceFeatures2 features{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, &storage};
        vkGetPhysicalDeviceFeatures2(gpu, &features);
    }
    uint32_t count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(gpu, &count, nullptr);
    std::vector<VkQueueFamilyProperties> queues(count);
    vkGetPhysicalDeviceQueueFamilyProperties(gpu, &count, queues.data());
    int level = 0;
    for (uint32_t i = 0; i < count; ++i) {
        if (!queues[i].queueCount || !(queues[i].queueFlags & VK_QUEUE_COMPUTE_BIT)) continue;
        const float priority = 1.0f;
        VkDeviceQueueCreateInfo queue{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        queue.queueFamilyIndex = i;
        queue.queueCount = 1;
        queue.pQueuePriorities = &priority;
        VkDeviceCreateInfo info{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        info.queueCreateInfoCount = 1;
        info.pQueueCreateInfos = &queue;
        VkDevice device;
        if (storage.storageBuffer16BitAccess) {
            const char* extension = VK_KHR_16BIT_STORAGE_EXTENSION_NAME;
            VkPhysicalDevice16BitStorageFeatures requested{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES};
            requested.storageBuffer16BitAccess = VK_TRUE;
            info.pNext = &requested;
            info.enabledExtensionCount = 1;
            info.ppEnabledExtensionNames = &extension;
            if (succeeded(vkCreateDevice(gpu, &info, nullptr, &device), conclusive)) {
                vkDestroyDevice(device, nullptr);
                return 2;
            }
            info.pNext = nullptr;
            info.enabledExtensionCount = 0;
            info.ppEnabledExtensionNames = nullptr;
        }
        if (!level && succeeded(vkCreateDevice(gpu, &info, nullptr, &device), conclusive)) {
            vkDestroyDevice(device, nullptr);
            level = 1;
            if (!storage.storageBuffer16BitAccess) break;
        }
    }
    return level;
}

static int queryVulkanCompute(bool& conclusive) {
    auto version = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
        vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));
    uint32_t api = VK_API_VERSION_1_0;
    if (version && !succeeded(version(&api), conclusive)) return 0;
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.apiVersion = std::min(api, VK_API_VERSION_1_2);
    VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    info.pApplicationInfo = &app;
    VkInstance instance;
    if (!succeeded(vkCreateInstance(&info, nullptr, &instance), conclusive)) return 0;
    uint32_t count = 0;
    int level = 0;
    if (succeeded(vkEnumeratePhysicalDevices(instance, &count, nullptr), conclusive) && count) {
        std::vector<VkPhysicalDevice> devices(count);
        if (succeeded(vkEnumeratePhysicalDevices(instance, &count, devices.data()), conclusive)) {
            for (uint32_t i = 0; i < count && level < 2; ++i)
                level = std::max(level, computeLevel(devices[i], app.apiVersion, conclusive));
        }
    }
    vkDestroyInstance(instance, nullptr);
    return level;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_renakoni_voicebox_core_DeviceFacts_queryVulkanCompute(JNIEnv* env, jclass, jbooleanArray cacheable) {
    bool conclusive = true;
    const int level = queryVulkanCompute(conclusive);
    const jboolean complete = conclusive || level == 2;
    env->SetBooleanArrayRegion(cacheable, 0, 1, &complete);
    return level;
}
