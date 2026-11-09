import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.GeneralPath
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

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
        val assetsDir = rootProject.file("iosApp/iosApp/Assets.xcassets")
        val iconDir = File(assetsDir, "AppIcon.appiconset")
        iconDir.mkdirs()

        File(assetsDir, "Contents.json").writeText(
            """
            {
              "info" : {
                "author" : "xcode",
                "version" : 1
              }
            }
            """.trimIndent()
        )

        val baseSize = 1024
        val master = BufferedImage(baseSize, baseSize, BufferedImage.TYPE_INT_RGB)
        val g = master.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

        // Tyrkysovy prechod pozadi podle ikony v Google Play (#26A69A -> #00695C)
        g.paint = GradientPaint(
            0f, 0f, Color(0x26, 0xA6, 0x9A),
            0f, baseSize.toFloat(), Color(0x00, 0x69, 0x5C)
        )
        g.fillRect(0, 0, baseSize, baseSize)

        // Jemny vnitrni lesk v horni casti
        g.paint = GradientPaint(
            0f, 0f, Color(255, 255, 255, 38),
            0f, 450f, Color(255, 255, 255, 0)
        )
        g.fillRect(0, 0, baseSize, 450)

        g.color = Color.WHITE

        // 1. Obrys kalendare
        g.stroke = BasicStroke(36f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(RoundRectangle2D.Double(210.0, 210.0, 604.0, 560.0, 96.0, 96.0))

        // 2. Vodorovna linka hlavicky kalendare
        g.stroke = BasicStroke(30f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.drawLine(218, 338, 806, 338)

        // 3. Krouzky vazby kalendare nahore
        val ringBg = Color(0x1B, 0x93, 0x86)
        for (rx in intArrayOf(370, 654)) {
            g.color = ringBg
            g.fill(RoundRectangle2D.Double((rx - 38).toDouble(), 142.0, 76.0, 136.0, 38.0, 38.0))
            g.color = Color.WHITE
            g.fill(RoundRectangle2D.Double((rx - 20).toDouble(), 154.0, 40.0, 112.0, 20.0, 20.0))
        }

        // 4. Mrizka dnu kalendare v horni casti
        val cols = intArrayOf(302, 422, 542, 662)
        val rows = intArrayOf(392, 488)
        for (rIdx in rows.indices) {
            for (cIdx in cols.indices) {
                if (rIdx == 1 && cIdx >= 2) continue
                val cx = cols[cIdx].toDouble()
                val cy = rows[rIdx].toDouble()
                g.fill(RoundRectangle2D.Double(cx, cy, 62.0, 54.0, 14.0, 14.0))
            }
        }

        // 5. Morska vlna ve spodni casti kalendare (styl WaveCal z Google Play)
        g.color = Color(0x00, 0x76, 0x68)
        g.fill(RoundRectangle2D.Double(186.0, 540.0, 652.0, 255.0, 60.0, 60.0))

        // Znovu dokresleni spodniho a bocniho ramu kalendare nad podkladem
        g.color = Color.WHITE
        g.stroke = BasicStroke(36f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val frameTopClip = g.clip
        g.clipRect(180, 190, 670, 410)
        g.draw(RoundRectangle2D.Double(210.0, 210.0, 604.0, 560.0, 96.0, 96.0))
        g.clip = frameTopClip

        // Horni hreben vlny se zavinutim vpravo
        g.stroke = BasicStroke(38f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val wave1 = GeneralPath().apply {
            moveTo(156f, 648f)
            curveTo(255f, 580f, 360f, 585f, 458f, 642f)
            curveTo(555f, 698f, 680f, 670f, 742f, 575f)
            curveTo(775f, 522f, 735f, 470f, 682f, 492f)
            curveTo(645f, 508f, 648f, 556f, 686f, 568f)
        }
        g.draw(wave1)

        // Spodni soubezna vlna
        g.stroke = BasicStroke(32f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val wave2 = GeneralPath().apply {
            moveTo(176f, 734f)
            curveTo(275f, 675f, 382f, 680f, 480f, 732f)
            curveTo(585f, 786f, 715f, 760f, 828f, 668f)
        }
        g.draw(wave2)

        g.dispose()

        fun writeRgbPng(img: BufferedImage, targetFile: File) {
            val w = img.width
            val h = img.height
            val rawBytes = ByteArrayOutputStream(h * (1 + w * 3))
            for (y in 0 until h) {
                rawBytes.write(0)
                for (x in 0 until w) {
                    val rgb = img.getRGB(x, y)
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
            ihdrData.writeInt(w)
            ihdrData.writeInt(h)
            ihdrData.writeByte(8)
            ihdrData.writeByte(2) // 24-bit RGB bez alfa kanalu
            ihdrData.writeByte(0)
            ihdrData.writeByte(0)
            ihdrData.writeByte(0)
            writeChunk(dataOut, "IHDR", ihdr.toByteArray())

            val idatBos = ByteArrayOutputStream()
            DeflaterOutputStream(idatBos).use { it.write(rawBytes.toByteArray()) }
            writeChunk(dataOut, "IDAT", idatBos.toByteArray())
            writeChunk(dataOut, "IEND", ByteArray(0))

            targetFile.writeBytes(pngOut.toByteArray())
        }

        fun resizeImage(src: BufferedImage, targetPx: Int): BufferedImage {
            if (targetPx == src.width && targetPx == src.height) return src
            val dst = BufferedImage(targetPx, targetPx, BufferedImage.TYPE_INT_RGB)
            val g2 = dst.createGraphics()
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.drawImage(src, 0, 0, targetPx, targetPx, null)
            g2.dispose()
            return dst
        }

        data class IconSpec(val idiom: String, val sizeStr: String, val scale: String, val px: Int)

        val specs = listOf(
            IconSpec("iphone", "20x20", "2x", 40),
            IconSpec("iphone", "20x20", "3x", 60),
            IconSpec("iphone", "29x29", "2x", 58),
            IconSpec("iphone", "29x29", "3x", 87),
            IconSpec("iphone", "40x40", "2x", 80),
            IconSpec("iphone", "40x40", "3x", 120),
            IconSpec("iphone", "60x60", "2x", 120),
            IconSpec("iphone", "60x60", "3x", 180),
            IconSpec("ipad", "20x20", "1x", 20),
            IconSpec("ipad", "20x20", "2x", 40),
            IconSpec("ipad", "29x29", "1x", 29),
            IconSpec("ipad", "29x29", "2x", 58),
            IconSpec("ipad", "40x40", "1x", 40),
            IconSpec("ipad", "40x40", "2x", 80),
            IconSpec("ipad", "76x76", "1x", 76),
            IconSpec("ipad", "76x76", "2x", 152),
            IconSpec("ipad", "83.5x83.5", "2x", 167),
            IconSpec("ios-marketing", "1024x1024", "1x", 1024)
        )

        val jsonEntries = mutableListOf<String>()
        for (spec in specs) {
            val fileName = "icon_${spec.idiom}_${spec.sizeStr}_${spec.scale}.png"
            val scaled = resizeImage(master, spec.px)
            writeRgbPng(scaled, File(iconDir, fileName))
            jsonEntries.add(
                """
                {
                  "filename" : "$fileName",
                  "idiom" : "${spec.idiom}",
                  "scale" : "${spec.scale}",
                  "size" : "${spec.sizeStr}"
                }
                """.trimIndent()
            )
        }

        // Záložní 1024x1024 ikona i pod názvem app-icon-1024.png
        writeRgbPng(master, File(iconDir, "app-icon-1024.png"))

        val contentsJson = """
        {
          "images" : [
        ${jsonEntries.joinToString(",\n").prependIndent("    ")}
          ],
          "info" : {
            "author" : "xcode",
            "version" : 1
          }
        }
        """.trimIndent()

        File(iconDir, "Contents.json").writeText(contentsJson)
    }
}

tasks.matching { it.name.startsWith("link") && it.name.contains("FrameworkIos") }.configureEach {
    dependsOn(generateIosAppIcon)
}
