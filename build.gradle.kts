plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("dev.langchain4j:langchain4j:0.35.0")
    implementation("org.duckdb:duckdb_jdbc:1.1.3")
    implementation("dev.langchain4j:langchain4j-anthropic:0.35.0")
}

// Configure Gradle IntelliJ Plugin
intellij {
    version.set("2024.1.7")
    type.set("IC") // Target IDE Platform
    plugins.set(emptyList()) // Lo dejamos vacío porque no dependemos de otros plugins
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("243.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}