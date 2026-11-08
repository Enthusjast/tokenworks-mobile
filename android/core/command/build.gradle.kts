plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:protocol"))
    api(project(":core:crypto"))
    api(libs.coroutines.core)
    testImplementation(libs.junit)
}
tasks.test {
}
