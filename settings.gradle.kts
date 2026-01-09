rootProject.name = "finemaps"

include("api")
include("core")
include("plugin")

// NMS modules (1.21+ only)
include("nms:v1_21_R1")

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}
