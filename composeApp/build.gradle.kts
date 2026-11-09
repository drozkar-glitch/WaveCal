import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO

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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val keystorePath = System.getenv("CM_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        getByName("debug") {
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
        val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g2d = canvas.createGraphics()
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

        // Vychozi pozadi z Android ikony (#DCD93D)
        g2d.color = Color(0xDC, 0xD9, 0x3D)
        g2d.fillRect(0, 0, width, height)

        val playstorePng = rootProject.file("app/src/main/ic_launcher-playstore.png")
        var drawnFromSource = false
        if (playstorePng.exists()) {
            try {
                val srcImg = ImageIO.read(playstorePng)
                if (srcImg != null) {
                    // Vykresleni s mirnym presazenim, aby iOS maska orezala pripadne pruhledne okraje z Play Store PNG
                    val margin = -36
                    g2d.drawImage(srcImg, margin, margin, width - 2 * margin, height - 2 * margin, null)
                    drawnFromSource = true
                }
            } catch (_: Exception) {
            }
        }

        if (!drawnFromSource) {
            // Presna vektorova podoba Android ikony (ic_launcher_background #DCD93D + ic_launcher_foreground.xml)
            g2d.color = Color(0x00, 0x00, 0x00)
            // Prepocet 24x24 viewportu se scale 0.58 a translate 5.04 do 1024x1024
            fun mapCoord(v: Double): Int {
                val transformed = 5.04 + v * 0.58
                // Viditelny vyrez adaptive ikony (72dp ze 108dp = 4.0..20.0 v 24x24 soustavne)
                val norm = (transformed - 4.0) / 16.0
                return (norm * 1024.0).toInt()
            }
            val x1 = mapCoord(3.0)
            val x2 = mapCoord(21.0)
            val barW = x2 - x1

            // Horni pruh: M3,6h18v2L3,8z
            val yTop1 = mapCoord(6.0)
            val yTop2 = mapCoord(8.0)
            g2d.fillRoundRect(x1, yTop1, barW, yTop2 - yTop1, 20, 20)

            // Stredni hlavni pruh: M3,10h18v5L3,15z
            val yMid1 = mapCoord(10.0)
            val yMid2 = mapCoord(15.0)
            g2d.fillRoundRect(x1, yMid1, barW, yMid2 - yMid1, 24, 24)

            // Spodni pruh: M3,17h18v2L3,19z
            val yBot1 = mapCoord(17.0)
            val yBot2 = mapCoord(19.0)
            g2d.fillRoundRect(x1, yBot1, barW, yBot2 - yBot1, 20, 20)
        }
        g2d.dispose()

        // Ulozeni jako cisty 24-bit RGB PNG (colorType = 2, bez alfa kanalu pro Apple App Store Connect)
        val rawBytes = ByteArrayOutputStream(height * (1 + width * 3))
        for (y in 0 until height) {
            rawBytes.write(0)
            for (x in 0 until width) {
                val rgb = canvas.getRGB(x, y)
                rawBytes.write((rgb shr 16) and 0xFF)
                rawBytes.write((rgb shr 8) and 0xFF)
                rawBytes.write(rgb and 0xFF)
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
        ihdrData.writeByte(2) // RGB bez alfa kanalu
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
