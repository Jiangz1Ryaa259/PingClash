plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.pingclash.app"
    compileSdk = 35
    testBuildType = providers.gradleProperty("pingclashTestBuildType").getOrElse("debug")

    defaultConfig {
        applicationId = "dev.pingclash.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 149
        versionName = "0.2.3"
        // Marks first-run setup done before the app starts, so tests open on the app itself.
        testInstrumentationRunner = "dev.pingclash.app.PingClashTestRunner"
        testProguardFiles("proguard-test-rules.pro")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildFeatures { compose = true }
    // The user guide ships inside the app from its one source, docs/user-guide.md.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/guide-assets"))
    bundle { language { enableSplit = false } }
    lint {
        error += setOf("MissingTranslation", "HardcodedText")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro")
            // Instrumentation keep rules only for the release-under-test build
            // (-PpingclashTestBuildType=release); shipped APKs shrink fully.
            if (providers.gradleProperty("pingclashTestBuildType").orNull == "release")
                proguardFiles("proguard-instrumentation.pro")
        }
    }
    splits {
        abi {
            isEnable = providers.gradleProperty("pingclashSplitApks").orNull=="true"
            reset()
            include("arm64-v8a","x86_64")
            isUniversalApk = true
        }
    }
}

dependencies {
    implementation(libs.zxing.core)
    implementation(project(":terminal-view"))
    implementation(project(":backdrop"))
    implementation(files(rootProject.file(".tools/native/pingcore.aar")))
    implementation(project(":diagnostics"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.graphics.shapes)
    implementation(libs.haze)
    implementation(libs.haze.materials)
    implementation(libs.compose.preview)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.coroutines.android)
    debugImplementation(libs.compose.tooling)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext)
    androidTestImplementation(libs.test.uiautomator)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    debugImplementation(libs.compose.test.manifest)
}

val copyUserGuide by tasks.registering(Copy::class) {
    from(
        rootProject.file("docs/user-guide.md"),
        rootProject.file("docs/user-guide.en.md"),
        rootProject.file("docs/user-guide.ja.md"),
    )
    into(layout.buildDirectory.dir("generated/guide-assets/guide"))
}
tasks.named("preBuild") { dependsOn(copyUserGuide) }

// Produce original classpaths for R8 TraceReferences; no test code is shipped.
tasks.register("writeReleaseTestTraceInputs") {
    dependsOn("compileReleaseAndroidTestKotlin", "compileReleaseAndroidTestJavaWithJavac")
    doLast {
        val directory=layout.buildDirectory.dir("test-reference-inputs").get().asFile.apply {mkdirs()}
        for ((kind,name) in listOf("target" to "releaseRuntimeClasspath","source" to "releaseAndroidTestRuntimeClasspath")) {
            val jars=configurations.getByName(name).incoming.artifactView {
                attributes.attribute(org.gradle.api.attributes.Attribute.of("artifactType",String::class.java),"android-classes-jar")
            }.files.files
            directory.resolve("$kind.txt").writeText(jars.joinToString("\n") {it.absolutePath})
        }
    }
}

