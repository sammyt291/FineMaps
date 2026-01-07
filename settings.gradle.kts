rootProject.name = "finemaps"

include("api")
include("core")
include("plugin")

// NMS modules
include("nms:v1_12_R1")
include("nms:v1_13_R2")
include("nms:v1_16_R3")
include("nms:v1_17_R1")
include("nms:v1_18_R2")
include("nms:v1_19_R3")
include("nms:v1_20_R3")
include("nms:v1_21_R1")

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}
