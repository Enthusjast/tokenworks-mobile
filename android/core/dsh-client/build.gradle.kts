plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:dsh-protocol"))
    api(project(":core:session"))
    api(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    inputs.file(rootProject.file("../contracts/golden/wire-fragment.json"))
    inputs.file(rootProject.file("../contracts/typert/golden/native-captured.json"))
    systemProperty("contracts.root", rootProject.file("../contracts").absolutePath)
}
