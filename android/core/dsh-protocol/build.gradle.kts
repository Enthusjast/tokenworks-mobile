plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:protocol"))
    implementation(libs.jackson.core)
    implementation(libs.jcs)
    testImplementation(libs.junit)
}

tasks.test {
    inputs.dir(rootProject.file("../contracts/typert/golden"))
    inputs.file(rootProject.file("../contracts/ws/v1/golden/session-models.get.json"))
    inputs.file(rootProject.file("../contracts/ws/v1/golden/session-models.result.json"))
    systemProperty("contracts.root", rootProject.file("../contracts").absolutePath)
}
