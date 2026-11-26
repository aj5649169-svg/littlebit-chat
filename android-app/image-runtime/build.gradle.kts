plugins { id("com.android.library") }
android {
    namespace = "com.littlebit.image.runtime"
    compileSdk = 35
    ndkVersion = "26.1.10909125"
    defaultConfig {
        minSdk = 28
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild.cmake {
            arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release")
            targets += "sd_jni"
        }
    }
    externalNativeBuild.cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
}
