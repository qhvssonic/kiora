# MNN native runtime

`src/main/jniLibs/arm64-v8a/` contains the Android arm64 libraries from the
[official MNN 3.6.1 release](https://github.com/alibaba/MNN/releases/tag/3.6.1),
archive `mnn_3.6.1_android_armv7_armv8_cpu_opencl_vulkan.zip` (SHA-256
`46dc7e86d45b8d4e957db81d2603e0b7f6c9ce9b84092ffdcee1b843cbfc9d71`).
The `MNN/` and `llm/` C++ headers in `src/main/cpp/include/` come from the
[official 3.6.1 source archive](https://github.com/alibaba/MNN/archive/refs/tags/3.6.1.zip)
(SHA-256 `93d60848879e68be554c247afe376657630a30652a7d8ec713e9e7d1bbf69260`).
The `3rd_party/half` header was retained from the local Android reference
project and retains its own license file. MNN is distributed under Apache-2.0;
Kiora's root `LICENSE` contains that license text.

The binary archive and headers must be updated together. Only `arm64-v8a` is
currently packaged. Model weights are not part of the SDK or APK.
