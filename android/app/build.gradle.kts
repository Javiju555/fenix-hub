import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val frontendDir = project.file("../../frontend")
val frontendDistDir = frontendDir.resolve("dist")
val bunExecutable = System.getenv("BUN")
    ?: project.file("${System.getProperty("user.home")}/.bun/bin/bun")
        .takeIf { it.exists() }
        ?.absolutePath
    ?: "bun"

android {
    namespace = "com.fenixhub.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fenixhub.mobile"
        minSdk = 29
        targetSdk = 34
        versionCode = 13
        versionName = "0.3.10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // ── Signing ──────────────────────────────────────────────────────────────
    // Local: crea android/signing.properties (gitignored) con las rutas/claves.
    // CI:    usa variables de entorno KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD.
    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("signing.properties")
            if (propsFile.exists()) {
                val props = Properties()
                props.load(propsFile.inputStream())
                storeFile     = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias      = props.getProperty("keyAlias")
                keyPassword   = props.getProperty("keyPassword")
            } else {
                // GitHub Actions / CI
                storeFile     = System.getenv("KEYSTORE_PATH")?.let { rootProject.file(it) }
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias      = System.getenv("KEY_ALIAS")
                keyPassword   = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    sourceSets {
        getByName("main").assets.srcDir(frontendDistDir)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    implementation("androidx.compose.ui:ui:1.6.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.4")
    implementation("androidx.compose.foundation:foundation:1.6.4")
    implementation("androidx.compose.animation:animation:1.6.4")
    implementation("androidx.compose.material:material-icons-extended:1.6.4")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.webkit:webkit:1.10.0")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.lambdapioneer.argon2kt:argon2kt:1.4.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.json:json:20231013")
    testImplementation("org.robolectric:robolectric:4.11.1")

    debugImplementation("androidx.compose.ui:ui-tooling:1.6.4")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.6.4")
}

val buildFrontend by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Android web frontend bundle."
    workingDir(frontendDir)
    commandLine(bunExecutable, "run", "build")
    inputs.files(
        frontendDir.resolve("index.html"),
        frontendDir.resolve("package.json"),
        frontendDir.resolve("tsconfig.json"),
        frontendDir.resolve("vite.config.ts"),
    )
    inputs.dir(frontendDir.resolve("public"))
    inputs.dir(frontendDir.resolve("src"))
    outputs.dir(frontendDistDir)
}

tasks.named("preBuild").configure {
    dependsOn(buildFrontend)
}
