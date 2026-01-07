plugins {
    java
}

repositories {
    maven("https://repo.codemc.io/repository/nms/")
}

dependencies {
    compileOnly(project(":core"))
    compileOnly("org.spigotmc:spigot:1.12.2-R0.1-SNAPSHOT")
}
