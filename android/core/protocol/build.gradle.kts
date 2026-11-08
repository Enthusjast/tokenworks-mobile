plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.jackson.core)
    implementation(libs.jcs)
    testImplementation(libs.junit)
}

tasks.test {
}
