plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    application
    `java-library`
}

// Pins both compileJava and compileKotlin to the same JDK, so they always
// agree on JVM target (fixes "Inconsistent JVM-target compatibility").
// 23, not the 21 LTS default, because GameTools (1.3.0) is compiled
// targeting JVM 23 and requires a runtime at least that new.
// Falls back to auto-provisioning via the Gradle toolchain resolver if this
// exact version isn't already installed locally.
kotlin {
    jvmToolchain(23)
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
    api("io.github.spartanlaboratories:GameTools:1.6.0")

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

    // JLayer: a small pure-Java MP3 decoder/player, used for UI sound effects
    // (see com.spartanlabs.audio.SoundPlayer). Its POM drags in a compile-scope
    // JUnit 3.8.2 we don't want on the runtime classpath - excluded here.
    implementation("com.googlecode.soundlibs:jlayer:1.0.1.4") {
        exclude(group = "junit", module = "junit")
    }

    // GameTools logs via slf4j; our own code (NetworkClient, Main) now does
    // too, for consistency. logback-classic is the actual runtime binding -
    // without it, slf4j calls are silently dropped (a "no binding found"
    // warning, but no log output at all).
    implementation("org.slf4j:slf4j-api:2.0.16")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.12")

    // junit-jupiter is JUnit's current major version (6.x) but keeps the
    // same org.junit.jupiter package/API the "JUnit 5" style tests use.
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.mockk:mockk:1.14.7")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("com.spartanlabs.MainKt")
}

// Required on macOS: GLFW/OpenGL must run on the main thread
tasks.named<JavaExec>("run") {
    if (osName.contains("mac")) {
        jvmArgs("-XstartOnFirstThread")
    }
}
