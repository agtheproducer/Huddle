plugins {
    `java-library`
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
