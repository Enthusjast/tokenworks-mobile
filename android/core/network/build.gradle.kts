plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

val fixtureClasspath = layout.buildDirectory.file("fixtures/classpath.txt")
tasks.register("writeFixtureClasspath") {
    dependsOn(tasks.testClasses)
    inputs.files(sourceSets.test.get().runtimeClasspath)
    outputs.file(fixtureClasspath)
    doLast {
        fixtureClasspath.get().asFile.apply { parentFile.mkdirs(); writeText(sourceSets.test.get().runtimeClasspath.asPath) }
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:auth"))
    api(project(":core:session"))
    implementation(libs.okhttp)
    api(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.nimbus.jose)
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
