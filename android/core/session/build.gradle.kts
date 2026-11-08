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
