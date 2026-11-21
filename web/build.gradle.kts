plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
}

application {
    mainClass.set("com.agentboard.web.MainKt")
}
