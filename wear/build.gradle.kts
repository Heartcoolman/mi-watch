plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.liji.mihome"
    compileSdk = 36

    defaultConfig {
        // 与 watch-ha 不同：这里 namespace == applicationId。
        // watch-ha 让两者分裂是为了 Data Layer 同包名要求，本 App 没有手机伴侣，不需要那个税。
        applicationId = "dev.liji.mihome"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // 只构建 debug：debug/release 混签会撞 INSTALL_FAILED_UPDATE_INCOMPATIBLE，
    // 逼你卸载重装，而卸载会清掉已存的 passToken。
    buildTypes {
        debug { isMinifyEnabled = false }
    }

    sourceSets["main"].java.srcDir("src/main/kotlin")
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")

    implementation("androidx.wear.compose:compose-material3:1.6.2")
    implementation("androidx.wear.compose:compose-foundation:1.6.2")

    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.zxing:core:3.5.3")

    // Tile。protolayout 是独立于 Compose 的 builder 式布局系统，在系统进程里渲染，
    // 所以 Tile 的存在与否跟主界面用不用 Compose 无关。
    implementation("androidx.wear.tiles:tiles:1.6.1")
    implementation("androidx.wear.protolayout:protolayout:1.4.1")
    implementation("androidx.wear.protolayout:protolayout-material:1.4.1")
}
