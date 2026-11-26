#include <jni.h>
#include <vulkan/vulkan.h>
#include <algorithm>
#include <vector>

static int computeLevel(VkPhysicalDevice gpu, uint32_t api) {
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
            if (vkCreateDevice(gpu, &info, nullptr, &device) == VK_SUCCESS) {
                vkDestroyDevice(device, nullptr);
                return 2;
            }
            info.pNext = nullptr;
            info.enabledExtensionCount = 0;
            info.ppEnabledExtensionNames = nullptr;
        }
        if (!level && vkCreateDevice(gpu, &info, nullptr, &device) == VK_SUCCESS) {
            vkDestroyDevice(device, nullptr);
            level = 1;
            if (!storage.storageBuffer16BitAccess) break;
        }
    }
    return level;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_renakoni_voicebox_core_DeviceFacts_queryVulkanCompute(JNIEnv*, jclass) {
    auto version = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
        vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));
    uint32_t api = VK_API_VERSION_1_0;
    if (version && version(&api) != VK_SUCCESS) return 0;
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.apiVersion = std::min(api, VK_API_VERSION_1_2);
    VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    info.pApplicationInfo = &app;
    VkInstance instance;
    if (vkCreateInstance(&info, nullptr, &instance) != VK_SUCCESS) return 0;
    uint32_t count = 0;
    int level = 0;
    if (vkEnumeratePhysicalDevices(instance, &count, nullptr) == VK_SUCCESS && count) {
        std::vector<VkPhysicalDevice> devices(count);
        if (vkEnumeratePhysicalDevices(instance, &count, devices.data()) == VK_SUCCESS) {
            for (uint32_t i = 0; i < count && level < 2; ++i)
                level = std::max(level, computeLevel(devices[i], app.apiVersion));
        }
    }
    vkDestroyInstance(instance, nullptr);
    return level;
}
