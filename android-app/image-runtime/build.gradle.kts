plugins { id("com.android.library") }
android {
    namespace = "com.littlebit.image.runtime"
    compileSdk = 35
    // Keep in sync with :app and .github/workflows/build-android-apk.yml.
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 28
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild.cmake {
            arguments += listOf(
                "-DANDROID_STL=c++_shared",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
            )
            targets += "sd_jni"
        }
    }
    externalNativeBuild.cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
}
