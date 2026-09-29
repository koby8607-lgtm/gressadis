package com.r36s.usb0manager

import android.content.Context
import java.io.File
import java.security.MessageDigest

object HelperManager {
    private const val HELPER_VERSION = "2.0.0"
    private const val ASSET_DIR = "tools/arm64-v8a"

    data class InstalledTools(
        val dir: File,
        val helper: File,
        val native: File,
        val busybox: File,
        val pppd: File?,
        val chat: File?
    )

    fun install(context: Context): InstalledTools {
        val dir = File(context.filesDir, ASSET_DIR).apply { mkdirs() }
        val marker = File(dir, "helper.version")
        val names = listOf("usb0-helper", "usb0-helper.sh", "usb0-native", "busybox", "pppd", "chat")
        val needs = marker.readTextOrNull() != HELPER_VERSION || names.any { n ->
            val assetPresent = assetExists(context, "$ASSET_DIR/$n")
            if (!assetPresent && n in listOf("pppd", "chat")) false
            else {
                val f = File(dir, n)
                !f.exists() || f.length() < minimumSize(n)
            }
        }
        if (needs) {
            copyRequired(context, "$ASSET_DIR/usb0-helper", File(dir, "usb0-helper"))
            copyRequired(context, "$ASSET_DIR/usb0-helper.sh", File(dir, "usb0-helper.sh"))
            copyRequired(context, "$ASSET_DIR/usb0-native", File(dir, "usb0-native"))
            copyRequired(context, "$ASSET_DIR/busybox", File(dir, "busybox"))
            val pppdFile = File(dir, "pppd")
            val chatFile = File(dir, "chat")
            if (assetExists(context, "$ASSET_DIR/pppd")) copyOptional(context, "$ASSET_DIR/pppd", pppdFile) else pppdFile.delete()
            if (assetExists(context, "$ASSET_DIR/chat")) copyOptional(context, "$ASSET_DIR/chat", chatFile) else chatFile.delete()
            copyRequired(context, "r36s/lineageos_r36s_defconfig.txt", File(context.filesDir, "r36s/lineageos_r36s_defconfig.txt"))
            copyRequired(context, "r36s/kernel-capabilities.txt", File(context.filesDir, "r36s/kernel-capabilities.txt"))
            listOf("usb0-helper", "usb0-helper.sh", "usb0-native", "busybox", "pppd", "chat").forEach {
                File(dir, it).takeIf { f -> f.exists() }?.setExecutable(true, false)
            }
            copyOptional(context, "$ASSET_DIR/SHA256SUMS", File(dir, "SHA256SUMS"))
            marker.writeText(HELPER_VERSION)
        }
        verifyManifest(File(dir, "SHA256SUMS"), dir)
        return InstalledTools(
            dir = dir,
            helper = File(dir, "usb0-helper"),
            native = File(dir, "usb0-native"),
            busybox = File(dir, "busybox"),
            pppd = File(dir, "pppd").takeIf { it.exists() && it.length() >= minimumSize("pppd") },
            chat = File(dir, "chat").takeIf { it.exists() && it.length() >= minimumSize("chat") }
        )
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verifyManifest(manifest: File, dir: File) {
        if (!manifest.exists()) return
        manifest.readLines().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size != 2) return@forEach
            val file = File(dir, parts[1].trim())
            require(file.exists()) { "Bundled helper missing: ${file.name}" }
            require(parts[0].equals(sha256(file), ignoreCase = true)) {
                "Bundled helper checksum mismatch: ${file.name}"
            }
        }
    }

    private fun assetExists(context: Context, asset: String): Boolean =
        runCatching { context.assets.open(asset).use { true } }.getOrDefault(false)

    private fun copyRequired(context: Context, asset: String, target: File) {
        copyAsset(context, asset, target)
    }

    private fun copyOptional(context: Context, asset: String, target: File) {
        runCatching { copyAsset(context, asset, target) }
    }

    private fun minimumSize(name: String): Long = when (name) {
        "busybox" -> 100_000L
        "pppd" -> 10_000L
        "chat" -> 1_000L
        else -> 50L
    }

    private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

    private fun copyAsset(context: Context, asset: String, target: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.new")
        temp.delete()
        context.assets.open(asset).use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }
}
