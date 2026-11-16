plugins {
    java
    id("com.gradleup.shadow") version "9.0.0-beta12"
}

group = "com.meteorsmp"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.grim.ac/snapshots")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation("org.bstats:bstats-bukkit:3.1.0")
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
    // Package Claude's root data/ yml templates into the compiled jar
    from("data") {
        into("data")
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.bstats", "com.meteorsmp.antiesp.libs.bstats")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
