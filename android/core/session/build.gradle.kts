plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:protocol"))
    implementation(libs.jackson.core)
    testImplementation(libs.junit)
}
tasks.test {
}

tasks.test {
    inputs.dir(rootProject.file("../contracts").resolve("ws/v1"))
    inputs.dir(rootProject.file("../contracts").resolve("security"))
    inputs.dir(rootProject.file("../contracts").resolve("files/v1"))
    inputs.dir(rootProject.file("../contracts").resolve("push/v1"))
    inputs.file(rootProject.file("../contracts").resolve("golden/app-release-selection.json"))
    systemProperty("contracts.root", rootProject.file("../contracts").absolutePath)
}
