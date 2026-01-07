plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

repositories {
    maven("https://repo.dmulloy2.net/repository/public/") {
        content {
            includeGroup("com.comphenix.protocol")
        }
    }
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    
    compileOnly("org.spigotmc:spigot-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("com.github.Redempt:RedLib:6.5.7")
    compileOnly("com.comphenix.protocol:ProtocolLib:5.1.0")
}

tasks {
    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
    
    shadowJar {
        archiveBaseName.set("MapDB")
        archiveClassifier.set("")
        
        // Relocate shaded dependencies to avoid conflicts
        relocate("com.zaxxer.hikari", "com.example.mapdb.libs.hikari")
        relocate("org.sqlite", "com.example.mapdb.libs.sqlite")
        
        // Include dependencies from core
        dependencies {
            include(project(":api"))
            include(project(":core"))
            include(dependency("com.zaxxer:HikariCP"))
            include(dependency("org.xerial:sqlite-jdbc"))
            // MySQL connector is usually provided by the server or added separately
        }
        
        // Minimize JAR size
        minimize {
            exclude(project(":api"))
            exclude(project(":core"))
        }
    }
    
    build {
        dependsOn(shadowJar)
    }
    
    jar {
        // Disable default jar, use shadowJar instead
        enabled = false
    }
}
