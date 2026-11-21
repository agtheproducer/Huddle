plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.mcp.kotlin.sdk)
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17") // keep stdout clean: it carries the MCP protocol
}

application {
    mainClass.set("com.agentboard.mcp.MainKt")
    // stdout carries the MCP protocol: no startup banner
    applicationDefaultJvmArgs = listOf("-Dkotlin-logging.logStartupMessage=false")
}
