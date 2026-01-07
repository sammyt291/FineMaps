plugins {
    `java-library`
}

dependencies {
    api(project(":api"))
    
    compileOnly("org.spigotmc:spigot-api:1.12.2-R0.1-SNAPSHOT")
    
    // RedLib - optional, used for config management
    // Install locally or add your own repository if needed:
    // compileOnly("com.github.Redempt:RedLib:6.5.7")
    
    // ProtocolLib - required for packet interception
    // Install locally or use: https://repo.dmulloy2.net/repository/public/
    // compileOnly("com.comphenix.protocol:ProtocolLib:5.1.0")
    
    // Database dependencies - will be shaded
    implementation("com.zaxxer:HikariCP:4.0.3")
    implementation("org.xerial:sqlite-jdbc:3.42.0.0")
    implementation("com.mysql:mysql-connector-j:8.0.33")
}

java {
    withSourcesJar()
}
