import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

val deploymentAssets = layout.buildDirectory.dir("generated/deploymentAssets")
val deploymentSource = providers.gradleProperty("deploymentConfig")
val pushSource = providers.gradleProperty("pushConfig")
val appUpdateSource = providers.gradleProperty("appUpdateConfig")
val prepareDeployment by tasks.registering {
    inputs.property("configuration", deploymentSource.orElse("<absent>"))
    inputs.property("pushConfiguration", pushSource.orElse("<absent>"))
    inputs.property("appUpdateConfiguration", appUpdateSource.orElse("<absent>"))
    pushSource.orNull?.let { inputs.file(rootProject.file(it)) }
    appUpdateSource.orNull?.let { inputs.file(rootProject.file(it)) }
    deploymentSource.orNull?.let { inputs.file(rootProject.file(it)) }
    outputs.dir(deploymentAssets)
    if (deploymentSource.isPresent) dependsOn(":core:auth:validateDeployment")
    if (pushSource.isPresent) { check(deploymentSource.isPresent) { "pushConfig requires deploymentConfig" }; dependsOn(":core:auth:validatePushDeployment") }
    if (appUpdateSource.isPresent) { check(deploymentSource.isPresent) { "appUpdateConfig requires deploymentConfig" }; dependsOn(":core:auth:validateAppUpdateDeployment") }
    doLast {
        val directory = deploymentAssets.get().asFile
        // A build without configuration must also remove a previous build's asset.
        directory.deleteRecursively()
        directory.mkdirs()
        if (deploymentSource.isPresent) {
            val input = rootProject.file("core/auth/build/generated/validatedDeployment/deployment.json")
            check(input.isFile && input.length() <= 128 * 1024) { "Invalid public deployment configuration file" }
            input.copyTo(directory.resolve("deployment.json"))
            if (pushSource.isPresent) rootProject.file("core/auth/build/generated/validatedDeployment/push-deployment.json").copyTo(directory.resolve("push-deployment.json"))
            if (appUpdateSource.isPresent) rootProject.file("core/auth/build/generated/validatedDeployment/app-update-deployment.json").copyTo(directory.resolve("app-update-deployment.json"))
        }
    }
}

// Opt-in trust is generated into the debug source set only, never main/release.
val localDevCaSource = providers.gradleProperty("localDevCa")
val localDevTrustResources = layout.buildDirectory.dir("generated/localDevTrust/res")
val prepareLocalDevTrust by tasks.registering {
    inputs.property("localDevCa", localDevCaSource.orElse("<absent>"))
    localDevCaSource.orNull?.let { inputs.file(rootProject.file(it)) }
    outputs.dir(localDevTrustResources)
    doLast {
        val directory = localDevTrustResources.get().asFile
        // Switching back to an ordinary debug build must remove the old CA.
        directory.deleteRecursively()
        val localAnchors = if (localDevCaSource.isPresent) {
            val input = rootProject.file(localDevCaSource.get())
            check(input.isFile && input.length() in 1L..(128L * 1024)) {
                "localDevCa must be a public CA PEM file no larger than 128 KiB"
            }
            val pem = input.readText(Charsets.US_ASCII).trim()
            check(!pem.contains("PRIVATE KEY", ignoreCase = true)) {
                "localDevCa must not contain private key material"
            }
            val match = Regex("""\A-----BEGIN CERTIFICATE-----\r?\n([A-Za-z0-9+/=\r\n]+)-----END CERTIFICATE-----\z""").matchEntire(pem)
            check(match != null) { "localDevCa must contain exactly one public PEM certificate and nothing else" }
            val certificate = runCatching {
                val der = Base64.getDecoder().decode(match.groupValues[1].replace("\r", "").replace("\n", ""))
                val stream = der.inputStream()
                val parsed = CertificateFactory.getInstance("X.509").generateCertificate(stream) as X509Certificate
                check(stream.available() == 0 && parsed.encoded.contentEquals(der))
                parsed.checkValidity()
                check(parsed.basicConstraints >= 0)
                check(parsed.keyUsage?.let { it.size > 5 && it[5] } != false)
                parsed
            }.getOrNull()
            check(certificate != null) { "localDevCa must be a currently valid X.509 CA certificate with certificate-signing usage" }
            val raw = directory.resolve("raw/local_dev_ca.pem")
            raw.parentFile.mkdirs()
            val encoded = Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(certificate.encoded)
            raw.writeText("-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----\n", Charsets.US_ASCII)
            """
                <domain-config cleartextTrafficPermitted="false">
                    <domain includeSubdomains="false">localhost</domain>
                    <domain includeSubdomains="false">127.0.0.1</domain>
                    <trust-anchors>
                        <certificates src="system" />
                        <certificates src="@raw/local_dev_ca" />
                    </trust-anchors>
                </domain-config>
            """.trimIndent()
        } else ""
        val configuration = directory.resolve("xml/local_dev_network_security_config.xml")
        configuration.parentFile.mkdirs()
        configuration.writeText("""
            <network-security-config>
                <base-config cleartextTrafficPermitted="false">
                    <trust-anchors>
                        <certificates src="system" />
                    </trust-anchors>
                </base-config>
        """.trimIndent() + "\n" + localAnchors + "\n</network-security-config>\n")
    }
}

android {
    namespace = "cn.edu.ustc.tokenworks"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "cn.edu.ustc.tokenworks"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
        manifestPlaceholders["appAuthRedirectScheme"] = "cn.edu.ustc.tokenworks"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["accountFixture"] = providers.gradleProperty("accountFixture").orElse("false").get()
        testInstrumentationRunnerArguments["networkFixture"] = providers.gradleProperty("networkFixture").orElse("false").get()
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets.getByName("main").assets.srcDir(deploymentAssets)
    sourceSets.getByName("debug").res.srcDir(localDevTrustResources)
}
tasks.named("preBuild") { dependsOn(prepareDeployment) }
tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(prepareLocalDevTrust) }
kotlin { jvmToolchain(17) }
kapt { correctErrorTypes = true }
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(project(":core:auth"))
    implementation(project(":core:network"))
    implementation(project(":core:keystore"))
    implementation(project(":core:ui"))
    implementation(libs.activity.compose)
    implementation(libs.activity.ktx)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.browser)
    implementation(libs.appauth)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.nimbus.jose)
    debugImplementation(libs.compose.ui.test.manifest)
}
