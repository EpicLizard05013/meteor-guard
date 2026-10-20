plugins {
    java
    `java-library`
}

group = "com.meteorsmp.antiesp"
version = "1.1-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.dmulloy2.net/repository/public/") // ProtocolLib
    maven("https://repo.codemc.io/repository/maven-releases/") // PacketEvents
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.comphenix.protocol:ProtocolLib:5.3.0")
    compileOnly("com.github.retrooper:packetevents-api:2.5.0") // Or your matching version
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}
