plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.termux.terminal"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    sourceSets["main"].jniLibs.srcDir(rootProject.file(".tools/terminal-native"))
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation(libs.annotation) }
