plugins {
  alias(libs.plugins.android.application)
}

// Diisi GitHub Actions dari Secrets; kosong saat build lokal / AI Studio.
val mapsApiKey: String = providers.environmentVariable("MAPS_API_KEY")
  .orElse(providers.gradleProperty("MAPS_API_KEY"))
  .getOrElse("")
val fixedKeystorePath: String? = providers.environmentVariable("ELIO_KEYSTORE_PATH").orNull
val fixedStorePassword: String? = providers.environmentVariable("ELIO_STORE_PASSWORD").orNull

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.elioux.launcher"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
    // Tanpa key, aplikasi otomatis memakai peta web (embed) seperti sebelumnya.
    manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      // signingConfig = signingConfigs.getByName("debugConfig") // Menggunakan default debug keystore bawaan Android/CI
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Kunci tanda tangan tetap dari GitHub Secrets: update APK cukup ditimpa, dan SHA-1 tetap
// sama sehingga API key Google Maps yang dikunci ke aplikasi ini terus berlaku.
if (fixedKeystorePath != null && fixedStorePassword != null && file(fixedKeystorePath).exists()) {
  android.signingConfigs.create("elioFixed") {
    storeFile = file(fixedKeystorePath)
    storePassword = fixedStorePassword
    keyAlias = "elioux"
    keyPassword = fixedStorePassword
    storeType = "pkcs12"
  }
  android.buildTypes.getByName("debug").signingConfig = android.signingConfigs.getByName("elioFixed")
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.play.services.location)
  implementation(libs.play.services.maps)
}
