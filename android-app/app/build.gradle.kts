import com.android.build.api.artifact.SingleArtifact
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.littlebit.chat"
    compileSdk = 35

    val releaseStore = System.getenv("ANDROID_RELEASE_STORE_FILE")
    if (!releaseStore.isNullOrBlank()) {
        signingConfigs.create("distribution") {
            storeFile = file(releaseStore)
            storePassword = System.getenv("ANDROID_RELEASE_STORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_RELEASE_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("distribution")
        }
    }

    flavorDimensions += "network"
    productFlavors {
        create("offline") { dimension = "network"; buildConfigField("boolean", "WEB_SEARCH", "false") }
        create("standard") { dimension = "network"; applicationIdSuffix = ".standard"; buildConfigField("boolean", "WEB_SEARCH", "true") }
    }

    defaultConfig {
        applicationId = "com.littlebit.chat"
        minSdk = 28          // matches -DANDROID_PLATFORM=android-28 in scripts/build-android.sh
        targetSdk = 35
        versionCode = 10
        versionName = "1.2.1"

        ndk {
            // Native engine is arm64-only: KleidiAI kernels + LittleBit path are built for arm64-v8a.
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-O3", "-fvisibility=hidden")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DGGML_VULKAN=ON",
                )
                val vulkanSdk = System.getenv("VULKAN_SDK")
                val vulkanInclude = System.getenv("VULKAN_INCLUDE_DIR")
                    ?: vulkanSdk?.let { "$it/include" }
                val glslc = System.getenv("VULKAN_GLSLC_EXECUTABLE")
                    ?: vulkanSdk?.let { "$it/bin/glslc" }
                val ndkRoot = System.getenv("ANDROID_NDK_HOME")
                    ?: System.getenv("ANDROID_NDK_ROOT")
                val ndkHost = when {
                    System.getProperty("os.name").lowercase().contains("win") -> "windows-x86_64"
                    System.getProperty("os.name").lowercase().contains("mac") -> "darwin-x86_64"
                    else -> "linux-x86_64"
                }
                val ndkSysroot = ndkRoot?.let {
                    "$it/toolchains/llvm/prebuilt/$ndkHost/sysroot"
                }
                if (vulkanInclude != null) {
                    arguments += "-DVulkan_INCLUDE_DIR=$vulkanInclude"
                }
                if (glslc != null) {
                    arguments += "-DVulkan_GLSLC_EXECUTABLE=$glslc"
                }
                if (ndkSysroot != null) {
                    arguments += "-DVulkan_LIBRARY=$ndkSysroot/usr/lib/aarch64-linux-android/28/libvulkan.so"
                }
            }
        }
    }

    // Keep in sync with .github/workflows/build-android-apk.yml (sdkmanager ndk;26.1.10909125).
    ndkVersion = "26.1.10909125"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // Extract native libs on install: faster System.loadLibrary for the multi-MB engine.
            useLegacyPackaging = true
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

val verifyDistributionSigning by tasks.registering {
    doLast {
        val required = listOf("ANDROID_RELEASE_STORE_FILE", "ANDROID_RELEASE_STORE_PASSWORD",
            "ANDROID_RELEASE_KEY_ALIAS", "ANDROID_RELEASE_KEY_PASSWORD")
        val missing = required.filter { System.getenv(it).isNullOrBlank() }
        check(missing.isEmpty()) { "Release signing requires: ${missing.joinToString()}" }
        check(file(System.getenv("ANDROID_RELEASE_STORE_FILE")).isFile) { "Distribution keystore file is missing" }
    }
}
tasks.matching { it.name in listOf("preOfflineReleaseBuild", "preStandardReleaseBuild") }.configureEach { dependsOn(verifyDistributionSigning) }

// The signed APK workflow invokes assembleRelease. Run the JVM regression suite
// on that entry point so a failed test cannot produce a distribution artifact.
tasks.matching { it.name.endsWith("Release") && it.name.startsWith("assemble") }.configureEach { dependsOn("testOfflineDebugUnitTest", "testStandardDebugUnitTest") }

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.14.2")
    implementation(project(":image-runtime"))
    add("standardImplementation", "org.jsoup:jsoup:1.18.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
}

androidComponents.onVariants(androidComponents.selector().withBuildType("release").withFlavor("network" to "offline")) { variant ->
    val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
    val offlineCheck = tasks.register("verifyOfflineReleaseManifest") {
        inputs.file(manifest)
        doLast {
            val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(manifest.get().asFile)
            val nodes = document.getElementsByTagName("*")
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                if (node.nodeName.startsWith("uses-permission")) {
                    val name = node.attributes.getNamedItemNS("http://schemas.android.com/apk/res/android", "name")?.nodeValue
                    check(name !in setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")) {
                        "Forbidden network permission in merged release manifest: $name"
                    }
                }
            }
        }
    }
    tasks.matching { it.name.endsWith("Release") && it.name.startsWith("assemble") }.configureEach { dependsOn(offlineCheck) }
}
