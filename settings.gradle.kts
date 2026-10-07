import java.util.Locale

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

if (!file(".git").exists()) {
    val errorText = "Build GPur from a Git checkout: https://github.com/nekoneko2872/GPur"
    error(errorText)
}

rootProject.name = "gpur"
for (name in listOf("purpur-api", "purpur-server", "purpur-checkstyle")) {
    val projName = name.lowercase(Locale.ENGLISH)
    include(projName)
    findProject(":$projName")!!.projectDir = file(name.replaceFirst("purpur-", "gpur-"))
}

gradle.lifecycle.beforeProject {
    val mcVersion = providers.gradleProperty("mcVersion").get().trim()
    val purpurChannel = providers.gradleProperty("channel").get().trim()
    val purpurBuildNumber = providers.environmentVariable("BUILD_NUMBER").orNull?.trim()?.toInt()
    val versionString = if (purpurBuildNumber == null) {
        "$mcVersion.local-SNAPSHOT"
    } else {
        "$mcVersion.build.$purpurBuildNumber-${purpurChannel.lowercase()}"
    }
    version = versionString
}
