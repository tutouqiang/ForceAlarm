pluginManagement {
    repositories {
        // 阿里云镜像，代替国外的 gradlePluginPortal
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
// 删掉了 foojay-resolver-convention 插件：它的作用是让 Gradle 自动下载 JDK，本机已装好 JDK，不需要它。
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "ForceAlarm"
include(":app")
