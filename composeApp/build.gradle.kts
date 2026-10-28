import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs
import kotlin.math.sin

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.9.2")
            implementation("androidx.core:core-ktx:1.13.1")
            implementation("androidx.appcompat:appcompat:1.7.0")
        }
    }
}

android {
    namespace = "cz.mares.wavecal"
    compileSdk = 36

    defaultConfig {
        applicationId = "cz.mares.wavecal"
        minSdk = 24
        targetSdk = 36

        val rawBuild = System.getenv("PROJECT_BUILD_NUMBER")?.toIntOrNull()
            ?: System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
            ?: 1
        val ciBuildNumber = if (rawBuild < 100) rawBuild + 100 else rawBuild
        versionCode = ciBuildNumber
        versionName = "1.0.$ciBuildNumber"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("CM_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("CM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CM_KEY_ALIAS")
                keyPassword = System.getenv("CM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            val keystorePath = System.getenv("CM_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val generateIosAppIcon by tasks.registering {
    doLast {
        val iconDir = rootProject.file("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset")
        iconDir.mkdirs()
        val outFile = File(iconDir, "app-icon-1024.png")
        val width = 1024
        val height = 1024

        val rawBytes = ByteArrayOutputStream(height * (1 + width * 3))
        for (y in 0 until height) {
            rawBytes.write(0)
            for (x in 0 until width) {
                var r = 15
                var g = 23
                var b = 42

                if (x in 140..884 && y in 180..884) {
                    if (y <= 370) {
                        r = 2; g = 132; b = 199
                    } else {
                        r = 248; g = 250; b = 252
                    }
                }

                for (rx in intArrayOf(310, 512, 714)) {
                    if (x in (rx - 26)..(rx + 26) && y in 125..225) {
                        r = 226; g = 232; b = 240
                    }
                }

                val cols = intArrayOf(260, 386, 512, 638, 764)
                val rows = intArrayOf(450, 560, 670)
                for (ri in rows.indices) {
                    for (ci in cols.indices) {
                        val cx = cols[ci]
                        val cy = rows[ri]
                        if (x in (cx - 38)..(cx + 38) && y in (cy - 38)..(cy + 38)) {
                            if (ri == 1 && ci == 2) {
                                r = 239; g = 68; b = 68
                            } else {
                                r = 203; g = 213; b = 225
                            }
                        }
                    }
                }

                if (x in 180..844) {
                    val waveY = 795.0 + 28.0 * sin((x - 180) * 2.0 * Math.PI / 330.0)
                    if (abs(y - waveY) <= 14.0) {
                        r = 14; g = 165; b = 233
                    }
                }

                rawBytes.write(r)
                rawBytes.write(g)
                rawBytes.write(b)
            }
        }

        fun writeChunk(out: DataOutputStream, type: String, data: ByteArray) {
            out.writeInt(data.size)
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            out.write(typeBytes)
            out.write(data)
            val crc = CRC32()
            crc.update(typeBytes)
            crc.update(data)
            out.writeInt(crc.value.toInt())
        }

        val pngOut = ByteArrayOutputStream()
        val dataOut = DataOutputStream(pngOut)
        dataOut.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        val ihdr = ByteArrayOutputStream()
        val ihdrData = DataOutputStream(ihdr)
        ihdrData.writeInt(width)
        ihdrData.writeInt(height)
        ihdrData.writeByte(8)
        ihdrData.writeByte(2)
        ihdrData.writeByte(0)
        ihdrData.writeByte(0)
        ihdrData.writeByte(0)
        writeChunk(dataOut, "IHDR", ihdr.toByteArray())

        val idatBos = ByteArrayOutputStream()
        DeflaterOutputStream(idatBos).use { it.write(rawBytes.toByteArray()) }
        writeChunk(dataOut, "IDAT", idatBos.toByteArray())
        writeChunk(dataOut, "IEND", ByteArray(0))

        outFile.writeBytes(pngOut.toByteArray())
    }
}

tasks.matching { it.name.startsWith("link") && it.name.contains("FrameworkIos") }.configureEach {
    dependsOn(generateIosAppIcon)
}
