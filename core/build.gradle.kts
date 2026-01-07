plugins {
    `java-library`
}

repositories {
    maven("https://redempt.dev")
}

dependencies {
    api(project(":api"))
    
    compileOnly("org.spigotmc:spigot-api:1.20.4-R0.1-SNAPSHOT")
    
    // RedLib for config management
    compileOnly("com.github.Redempt:RedLib:6.5.8")
    
    // ProtocolLib for packet interception
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
    
    // Database dependencies - will be shaded
    implementation("com.zaxxer:HikariCP:4.0.3")
    implementation("org.xerial:sqlite-jdbc:3.42.0.0")
    implementation("com.mysql:mysql-connector-j:8.0.33")
}

java {
    withSourcesJar()
}
