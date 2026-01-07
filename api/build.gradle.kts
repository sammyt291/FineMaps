plugins {
    `java-library`
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.12.2-R0.1-SNAPSHOT")
}

java {
    withSourcesJar()
}

tasks.withType<Javadoc> {
    // Disable Javadoc for now - can be enabled when needed
    enabled = false
}
