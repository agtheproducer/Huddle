plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

// Rehearsal stage: a local page that shows demo/calculator live (files, code, running app).
kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.agentboard.stage.MainKt")
}

tasks.named<JavaExec>("run") {
    // Serve the repo's demo/calculator, wherever Gradle is started from.
    environment("BOB_ROOT", rootDir.absolutePath)
}
