plugins {
    java
    `maven-publish`
}

group = "com.example.mapdb"
version = "1.0.0"

subprojects {
    apply(plugin = "java")
    apply(plugin = "maven-publish")

    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://jitpack.io")
        maven("https://repo.codemc.io/repository/maven-public/")
        maven("https://repo.codemc.io/repository/nms/")
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        // Use release flag for cross-compilation compatibility
        options.release.set(17)
    }

    tasks.withType<Jar> {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}

// Configure Java compatibility per module
// API module targets Java 8 for maximum compatibility
configure(subprojects.filter { it.name == "api" }) {
    tasks.withType<JavaCompile> {
        options.release.set(8)
    }
}

// Core module targets Java 17 (required by ProtocolLib 5.4.0)
configure(subprojects.filter { it.name == "core" }) {
    tasks.withType<JavaCompile> {
        options.release.set(17)
    }
}

// Plugin module can use modern Java
configure(subprojects.filter { it.name == "plugin" }) {
    tasks.withType<JavaCompile> {
        options.release.set(17)
    }
}

// NMS modules - legacy versions target Java 8
configure(subprojects.filter { it.name.contains("v1_12") || it.name.contains("v1_13") || 
                               it.name.contains("v1_16") }) {
    tasks.withType<JavaCompile> {
        options.release.set(8)
    }
}

// 1.17 requires Java 16
configure(subprojects.filter { it.name.contains("v1_17") }) {
    tasks.withType<JavaCompile> {
        options.release.set(16)
    }
}

// 1.18-1.20 require Java 17
configure(subprojects.filter { it.name.contains("v1_18") || it.name.contains("v1_19") || 
                               it.name.contains("v1_20") }) {
    tasks.withType<JavaCompile> {
        options.release.set(17)
    }
}

// 1.21 requires Java 21
configure(subprojects.filter { it.name.contains("v1_21") }) {
    tasks.withType<JavaCompile> {
        options.release.set(21)
    }
}
