plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

repositories {
    maven("https://redempt.dev")
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    
    compileOnly("org.spigotmc:spigot-api:1.21.1-R0.1-SNAPSHOT")
    
    // RedLib for config management - shade it
    implementation("com.github.Redempt:RedLib:6.5.8")
    
    // ProtocolLib - provided at runtime
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")

    // Vault API (economy) - provided at runtime (JitPack)
    compileOnly("com.github.MilkBowl:VaultAPI:1.7")

    // ImageIO plugins for additional formats (shaded into the plugin)
    // - WEBP (including animated WebP when supported by reader)
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.11.0")
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
        archiveBaseName.set("FineMaps")
        archiveClassifier.set("")

        // Needed for ImageIO SPI providers (e.g., WEBP reader) in shaded JARs
        mergeServiceFiles()
        
        // Relocate shaded dependencies to avoid conflicts
        relocate("com.zaxxer.hikari", "com.example.finemaps.libs.hikari")
        // Note: sqlite-jdbc cannot be relocated because it uses JNI native libraries
        // that are bound to the original class names. Relocating breaks native method binding.
        relocate("redempt.redlib", "com.example.finemaps.libs.redlib")
    }
    
    build {
        dependsOn(shadowJar)
    }
}
