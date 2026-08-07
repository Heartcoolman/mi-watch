import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

// 不用 jvmToolchain(17)：本机只有 JDK 21，声明 17 会触发 Gradle 去下载工具链，
// 而下载要过代理，是个没必要的失败面。直接用 21 编译、产出 17 字节码。
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application {
    mainClass.set("dev.liji.mihome.core.MainKt")
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
