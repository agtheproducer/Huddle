plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("com.agentboard.cli.MainKt")
}
