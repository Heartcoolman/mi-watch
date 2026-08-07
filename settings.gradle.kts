// 本机 dl.google.com 完全不可达（沙箱内外、走代理与直连均 http=000），
// Maven Central 经本地代理 403。两者都必须走阿里云镜像。
// 注意：这里与 watch-ha 不同——watch-ha 用 google()，但那条路现在是断的。
// google() 仍保留在最后作为兜底，万一哪天网络恢复。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        google()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
    }
}

rootProject.name = "mi-watch"
include(":core")
include(":wear")
