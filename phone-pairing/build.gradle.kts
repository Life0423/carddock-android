plugins { alias(libs.plugins.kotlin.jvm) }

// 直接编译仓库中 Mika 的本机配对协议，避免维护第二份密码学实现。
val upstream = rootProject.file("experiments/mika-android/core/src")
kotlin {
    jvmToolchain(17)
    sourceSets.main {
        kotlin.srcDir(upstream.resolve("main/kotlin/com/wangzan101/mika/core/adb"))
        kotlin.include("AdbConnection.kt", "AdbHosts.kt", "AdbIdentity.kt", "AdbMessage.kt",
            "AdbPairing.kt", "AdbTls.kt", "Ed25519.kt", "PairingAuth.kt", "Spake2.kt")
    }
    sourceSets.test {
        resources.srcDir(upstream.resolve("test/resources"))
        resources.include("adb/spake2-vectors.txt")
        kotlin.srcDir(upstream.resolve("test/kotlin/com/wangzan101/mika/core/adb"))
        kotlin.include("CryptoTest.kt", "AdbHostsTest.kt", "AdbIdentitySourceTest.kt",
            "TestTls.kt", "AdbPairingTest.kt", "AdbConnectionTest.kt")
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation("org.conscrypt:conscrypt-openjdk-uber:2.5.2")
}


