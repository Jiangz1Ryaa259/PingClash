plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

val verificationKeyStore = layout.buildDirectory.file("verification/localhost.p12")
val testCertificate by tasks.registering(Exec::class) {
    val keyStore = verificationKeyStore.get().asFile
    val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
    commandLine(
        "${System.getProperty("java.home")}/bin/keytool$suffix", "-genkeypair",
        "-alias", "localhost", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
        "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-storetype", "PKCS12",
        "-keystore", keyStore.absolutePath, "-storepass", "test-only", "-keypass", "test-only", "-noprompt",
    )
    doFirst { keyStore.parentFile.mkdirs() }
    outputs.file(keyStore)
    onlyIf { !keyStore.exists() }
}

tasks.register<JavaExec>("verifyCore") {
    dependsOn(tasks.testClasses, testCertificate)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.pingclash.diagnostics.CoreVerification")
    args(verificationKeyStore.get().asFile.absolutePath,rootProject.file("app/src/main/res/values-zh-rCN/diagnostics.xml").absolutePath)
}

