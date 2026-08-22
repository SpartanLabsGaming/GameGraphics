plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    application
    `java-library`
    `kotlin-dsl`
    id("com.vanniktech.maven.publish") version "0.36.0"
}


repositories {
    mavenCentral()
}

val lwjglVersion = "3.3.4"

// Detect the current OS to pick the correct native artifacts
val osName: String = System.getProperty("os.name").lowercase()
val lwjglNatives = when {
    osName.contains("win") -> "natives-windows"
    osName.contains("mac") -> if (System.getProperty("os.arch").contains("aarch64"))
        "natives-macos-arm64" else "natives-macos"
    osName.contains("nux") || osName.contains("nix") -> "natives-linux"
    else -> throw GradleException("Unsupported OS: $osName")
}

dependencies {
    api("io.github.spartanlaboratories:GameTools:1.0.6")

    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))

    implementation("org.lwjgl", "lwjgl")
    implementation("org.lwjgl", "lwjgl-glfw")
    implementation("org.lwjgl", "lwjgl-opengl")
    implementation("org.lwjgl", "lwjgl-stb")

    runtimeOnly("org.lwjgl", "lwjgl", classifier = lwjglNatives)
    runtimeOnly("org.lwjgl", "lwjgl-glfw", classifier = lwjglNatives)
    runtimeOnly("org.lwjgl", "lwjgl-opengl", classifier = lwjglNatives)
    runtimeOnly("org.lwjgl", "lwjgl-stb", classifier = lwjglNatives)

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.github.spartanlaboratories", "GameGraphics", "1.0.0")

    pom {
        name.set("Game Graphics")
        description.set("A prototype for a game client.")
        inceptionYear.set("2026")
        url.set("https://github.com/SpartanLaboratories/GameGraphics")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("SpaSinghOut")
                name.set("Spartak Singh")
                url.set("https://github.com/SpaSinghOut")
            }
        }
        scm {
            url.set("https://github.com/SpartanLaboratories/GameGraphics/")
            connection.set("scm:git:git://github.com/SpartanLaboratories/MyGameTools.git")
            developerConnection.set("scm:git:ssh://git@github.com/SpartanLaboratories/MyGameTools.git")
        }
    }
}

application {
    mainClass.set("MainKt")
}

// Required on macOS: GLFW/OpenGL must run on the main thread
tasks.named<JavaExec>("run") {
    if (osName.contains("mac")) {
        jvmArgs("-XstartOnFirstThread")
    }
}