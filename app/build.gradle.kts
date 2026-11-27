import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.io.ByteArrayInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp") version "2.2.20-2.0.2"
}
android {
    namespace = "ru.mycapital.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "ru.mycapital.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 144
        versionName = "0.4.34"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("ANDROID_KEYSTORE_PATH") ?: "my-capital-signing.jks")
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.2")
    implementation("androidx.room:room-ktx:2.8.2")
    ksp("androidx.room:room-compiler:2.8.2")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-moshi:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    implementation("org.jsoup:jsoup:1.20.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    testImplementation("junit:junit:4.13.2")
    // Use a real org.json implementation in local JVM tests; Android SDK stubs otherwise swallow parser calls.
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.room:room-testing:2.8.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}


// Download the official NCA root certificate from the Gosuslugi CDN.
// Pin the DER certificate fingerprint so a substituted download is never trusted.
val downloadMoexNcaRootCertificate by tasks.registering {
    val certificateFile = layout.projectDirectory.file("src/main/res/raw/russian_trusted_root_ca.cer")
    outputs.file(certificateFile)
    doLast {
        val sourceUrl = "https://gu-st.ru/content/lending/russian_trusted_root_ca_pem.crt"
        val downloaded = URI(sourceUrl).toURL().openStream().use { it.readBytes() }
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(downloaded)) as X509Certificate
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02X".format(it) }
        check(fingerprint == "D26D2D0231B7C39F92CC738512BA54103519E4405D68B5BD703E9788CA8ECF31") {
            "Unexpected MOEX NCA root certificate fingerprint: $fingerprint"
        }
        check(certificate.subjectX500Principal.name.contains("Russian Trusted Root CA")) {
            "Downloaded certificate is not Russian Trusted Root CA: ${certificate.subjectX500Principal}"
        }
        certificate.checkValidity()
        certificateFile.asFile.apply {
            parentFile.mkdirs()
            writeBytes(certificate.encoded)
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(downloadMoexNcaRootCertificate)
}
