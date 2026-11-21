plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.agentboard.calc.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
