// 本机 dl.google.com 完全不可达（沙箱内外、走代理与直连均 http=000），
// Maven Central 经本地代理 403。两者都必须走阿里云镜像。
// 注意：这里与 watch-ha 不同——watch-ha 用 google()，但那条路现在是断的。
// google() 仍保留在最后作为兜底，万一哪天网络恢复。
//
// CI（GitHub Actions runner 在境外）反过来：官方源直连最快，阿里云镜像对境外 IP
// 时快时慢，而慢在这里不是「等久一点」——Gradle 解析超时会直接判构建失败。
// 所以按 CI 环境变量掉个个儿，两边各走各的近路。境外的人自己构建时也可以
// `CI=true ./gradlew …` 借用这条。
// （判断写在各块内部而不是提到文件顶上——pluginManagement 必须是文件里的第一个块。）

pluginManagement {
    repositories {
        if (System.getenv("CI").isNullOrBlank()) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI").isNullOrBlank()) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "mi-watch"
include(":core")
include(":wear")
