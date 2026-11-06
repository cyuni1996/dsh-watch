import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val localSettings = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val localApplicationId = localSettings.getProperty("dshwatch.applicationId")?.takeIf { it.isNotBlank() }

layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("app"))

android {
    namespace = "org.dshwatch.app"
    compileSdk = 35
    defaultConfig {
        applicationId = localApplicationId ?: "org.dshwatch.app"
        manifestPlaceholders["appLabel"] = if (localApplicationId == null) "DSH Watch" else "DSH Watch · 测试"
        minSdk = 30
        targetSdk = 34
        versionCode = 17
        versionName = "0.5.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (localApplicationId != null && localSettings.getProperty("dshwatch.signWithDebugKey") == "true") {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", if (providers.gradleProperty("useRepositoryMirror").orNull == "true") "https://maven.aliyun.com/repository/central" else "https://repo.maven.apache.org/maven2")
        }
    }
}

tasks.register("stageInstallerApk") {
    val releaseApk = providers.gradleProperty("dshwatch.releaseApk")
    doLast {
        check(localApplicationId == null) { "Remove the local application ID override before packaging the desktop installer." }
        val apk = file(releaseApk.orNull ?: error("Pass -Pdshwatch.releaseApk with a verified signed release APK."))
        check(apk.isFile) { "Signed release APK not found: ${apk.name}" }
        val destination = rootProject.file("prebuilt/dsh-watch.apk")
        destination.parentFile.mkdirs()
        apk.copyTo(destination, overwrite = true)
    }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
