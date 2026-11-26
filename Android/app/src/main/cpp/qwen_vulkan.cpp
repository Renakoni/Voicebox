#include <jni.h>
#include <vulkan/vulkan.h>
#include <vector>

// Match the GGML Vulkan backend before the downloadable Qwen library is available.
static bool canOpenComputeDevice(VkPhysicalDevice gpu) {
    VkPhysicalDeviceProperties properties;
    vkGetPhysicalDeviceProperties(gpu, &properties);
    if (properties.apiVersion < VK_API_VERSION_1_2 ||
        (properties.deviceType != VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU &&
         properties.deviceType != VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU)) return false;

    VkPhysicalDevice16BitStorageFeatures storage{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES};
    VkPhysicalDeviceFeatures2 features{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, &storage};
    vkGetPhysicalDeviceFeatures2(gpu, &features);
    if (!storage.storageBuffer16BitAccess) return false;

    uint32_t count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(gpu, &count, nullptr);
    std::vector<VkQueueFamilyProperties> queues(count);
    vkGetPhysicalDeviceQueueFamilyProperties(gpu, &count, queues.data());
    for (uint32_t i = 0; i < count; ++i) {
        if (!queues[i].queueCount || !(queues[i].queueFlags & VK_QUEUE_COMPUTE_BIT)) continue;
        const float priority = 1.0f;
        VkDeviceQueueCreateInfo queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        queueInfo.queueFamilyIndex = i;
        queueInfo.queueCount = 1;
        queueInfo.pQueuePriorities = &priority;
        const char* extension = VK_KHR_16BIT_STORAGE_EXTENSION_NAME;
        VkDeviceCreateInfo info{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, &storage};
        info.queueCreateInfoCount = 1;
        info.pQueueCreateInfos = &queueInfo;
        info.enabledExtensionCount = 1;
        info.ppEnabledExtensionNames = &extension;
        VkDevice device;
        if (vkCreateDevice(gpu, &info, nullptr, &device) == VK_SUCCESS) {
            vkDestroyDevice(device, nullptr);
            return true;
        }
    }
    return false;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_renakoni_voicebox_core_DeviceFacts_queryQwenGpu(JNIEnv*, jclass) {
    auto version = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
        vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));
    uint32_t api = 0;
    if (!version || version(&api) != VK_SUCCESS || api < VK_API_VERSION_1_2) return false;
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.apiVersion = VK_API_VERSION_1_2;
    VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    info.pApplicationInfo = &app;
    VkInstance instance;
    if (vkCreateInstance(&info, nullptr, &instance) != VK_SUCCESS) return false;
    uint32_t count = 0;
    bool supported = false;
    if (vkEnumeratePhysicalDevices(instance, &count, nullptr) == VK_SUCCESS && count) {
        std::vector<VkPhysicalDevice> devices(count);
        if (vkEnumeratePhysicalDevices(instance, &count, devices.data()) == VK_SUCCESS) {
            for (uint32_t i = 0; !supported && i < count; ++i) supported = canOpenComputeDevice(devices[i]);
        }
    }
    vkDestroyInstance(instance, nullptr);
    return supported;
}
