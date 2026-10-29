import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose"); id("org.jetbrains.kotlin.kapt") }
val releaseKeys = Properties().apply { rootProject.file(".signing/release.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
android {
 namespace = "io.github.idavydov.ivybp"
 compileSdk = 36
 defaultConfig { applicationId = "io.github.idavydov.ivybp"; minSdk = 31; targetSdk = 36; versionCode = 8; versionName = "0.1.7"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
 buildFeatures { compose = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 signingConfigs { if (releaseKeys.isNotEmpty()) create("personal") { storeFile=rootProject.file(".signing/release.p12"); storePassword=releaseKeys.getProperty("storePassword"); keyAlias=releaseKeys.getProperty("keyAlias"); keyPassword=storePassword } }
 buildTypes { debug { applicationIdSuffix = ".debug" }; release { isMinifyEnabled = false; if(releaseKeys.isNotEmpty()) signingConfig=signingConfigs.getByName("personal") } }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2025.04.01"))
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.ui:ui-tooling-preview")
 implementation("androidx.activity:activity-compose:1.10.1")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
 implementation("androidx.room:room-runtime:2.7.2")
 implementation("androidx.room:room-ktx:2.7.2")
 kapt("androidx.room:room-compiler:2.7.2")
 implementation("androidx.datastore:datastore-preferences:1.1.7")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
 testImplementation("junit:junit:4.13.2")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 androidTestImplementation("androidx.room:room-testing:2.7.2")
}
