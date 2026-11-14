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
    implementation(project(":core:session"))
    implementation(project(":core:dsh-client"))
    implementation(libs.okhttp)
    implementation(libs.httpcore5)
    implementation(libs.java.websocket)
    runtimeOnly(libs.slf4j.nop)
    api(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.nimbus.jose)
}

tasks.test {
    dependsOn(":core:crypto:buildNoiseJvm")
    systemProperty("tokenworks.noise.library", rootProject.file("../.local/noise/jvm/" + System.mapLibraryName("tokenworks_noise")).absolutePath)
    systemProperty("noise.repository", rootProject.projectDir.parentFile.absolutePath)
}
