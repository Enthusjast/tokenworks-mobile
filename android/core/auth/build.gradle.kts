plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:crypto"))
    implementation(libs.nimbus.jose)
    testImplementation(libs.junit)
}

val validatedDeployment = layout.buildDirectory.file("generated/validatedDeployment/deployment.json")
tasks.register<JavaExec>("validateDeployment") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("cn.edu.ustc.tokenworks.ValidateDeployment")
    val source = providers.gradleProperty("deploymentConfig")
    source.orNull?.let { inputs.file(rootProject.file(it)) }
    outputs.file(validatedDeployment)
    doFirst {
        args(rootProject.file(source.get()).absolutePath, validatedDeployment.get().asFile.absolutePath)
    }
}

val validatedPush = layout.buildDirectory.file("generated/validatedDeployment/push-deployment.json")
tasks.register<JavaExec>("validatePushDeployment") {
    dependsOn("validateDeployment")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("cn.edu.ustc.tokenworks.ValidatePushDeployment")
    val source = providers.gradleProperty("pushConfig")
    source.orNull?.let { inputs.file(rootProject.file(it)) }
    inputs.file(validatedDeployment)
    outputs.file(validatedPush)
    doFirst { args(rootProject.file(source.get()).absolutePath, validatedDeployment.get().asFile.absolutePath, validatedPush.get().asFile.absolutePath) }
}

val validatedUpdates = layout.buildDirectory.file("generated/validatedDeployment/app-update-deployment.json")
tasks.register<JavaExec>("validateAppUpdateDeployment") {
    dependsOn("validateDeployment")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("cn.edu.ustc.tokenworks.ValidateAppUpdateDeployment")
    val source = providers.gradleProperty("appUpdateConfig")
    source.orNull?.let { inputs.file(rootProject.file(it)) }
    inputs.file(validatedDeployment)
    outputs.file(validatedUpdates)
    doFirst { args(rootProject.file(source.get()).absolutePath, validatedDeployment.get().asFile.absolutePath, validatedUpdates.get().asFile.absolutePath) }
}

tasks.test {
    inputs.dir(rootProject.file("../contracts").resolve("ws/v1"))
    inputs.dir(rootProject.file("../contracts").resolve("security"))
    inputs.dir(rootProject.file("../contracts").resolve("files/v1"))
    inputs.dir(rootProject.file("../contracts").resolve("push/v1"))
    inputs.file(rootProject.file("../contracts").resolve("golden/app-release-selection.json"))
    systemProperty("contracts.root", rootProject.file("../contracts").absolutePath)
}
