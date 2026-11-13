plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:protocol"))
    implementation(libs.nimbus.jose)
    testImplementation(libs.junit)
}

tasks.test {
}

tasks.register<JavaExec>("generateInteropVectors") {
    group = "verification"
    description = "Regenerate public-only JCA-signed decisions for Go/Node interoperability."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("cn.edu.ustc.tokenworks.crypto.GenerateInteropVectors")
}

tasks.test {
    inputs.dir(rootProject.file("../contracts").resolve("ws/v1"))
    inputs.dir(rootProject.file("../contracts").resolve("security"))
    inputs.dir(rootProject.file("../contracts").resolve("files/v1"))
    inputs.dir(rootProject.file("../contracts").resolve("push/v1"))
    inputs.file(rootProject.file("../contracts").resolve("golden/app-release-selection.json"))
    systemProperty("contracts.root", rootProject.file("../contracts").absolutePath)
}
