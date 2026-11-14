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

val buildNoiseJvm by tasks.registering(Exec::class) {
    val repository = rootProject.projectDir.parentFile
    workingDir(repository)
    commandLine("python3", "tools/native/build-noise.py", "jvm")
    inputs.dir(repository.resolve("native/noise-carrier/src"))
    inputs.files(repository.resolve("native/noise-carrier/Cargo.toml"), repository.resolve("native/noise-carrier/Cargo.lock"),
        repository.resolve("native/noise-carrier/build.rs"), repository.resolve("native/noise-carrier/rust-toolchain.toml"),
        repository.resolve("tools/native/build-noise.py"))
    outputs.file(repository.resolve(".local/noise/jvm/" + System.mapLibraryName("tokenworks_noise")))
}

tasks.test {
    dependsOn(buildNoiseJvm)
    systemProperty("tokenworks.noise.library", rootProject.file("../.local/noise/jvm/" + System.mapLibraryName("tokenworks_noise")).absolutePath)
}

tasks.register<JavaExec>("noiseInterop") {
    group = "verification"
    description = "Verify production-random Node/JVM Noise binding interoperability."
    dependsOn(tasks.testClasses, buildNoiseJvm)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("cn.edu.ustc.tokenworks.E2eInterop")
    systemProperty("noise.repository", rootProject.projectDir.parentFile.absolutePath)
    systemProperty("tokenworks.noise.library", rootProject.file("../.local/noise/jvm/" + System.mapLibraryName("tokenworks_noise")).absolutePath)
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
