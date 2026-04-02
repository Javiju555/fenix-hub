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
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    inputs.dir(frontendDir.resolve("src"))
    outputs.dir(frontendDistDir)
}

tasks.named("preBuild").configure {
    dependsOn(buildFrontend)
}
