package com.paragrudani.replyly.testing

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * An [Instrumentation] whose context's assets are the exported Room schemas of `app/schemas/`, for
 * `MigrationTestHelper`.
 *
 * The helper reads the schemas from the test context's assets, and Robolectric only serves the
 * app's own merged assets (AGP does not merge unit-test assets). Rather than ship the schemas in
 * the APK, this hands the helper a context whose assets hold them.
 */
internal object SchemaAssets {
    fun instrumentation(): Instrumentation {
        val real = InstrumentationRegistry.getInstrumentation()
        val context = SchemaContext(real.targetContext, assetManager())
        return object : Instrumentation() {
            override fun getContext(): Context = context

            override fun getTargetContext(): Context = context
        }
    }

    private class SchemaContext(
        base: Context,
        private val schemaAssets: AssetManager,
    ) : ContextWrapper(base) {
        override fun getAssets(): AssetManager = schemaAssets
    }

    /** The schemas packed as `assets/...` in a zip, loaded the way the framework loads an APK. */
    private fun assetManager(): AssetManager {
        // Unit tests run in the module directory.
        val schemas = File("schemas").takeIf { it.isDirectory } ?: File("app/schemas")
        val zip = File.createTempFile("room-schemas", ".apk").apply { deleteOnExit() }
        ZipOutputStream(zip.outputStream()).use { out ->
            schemas.walkTopDown().filter { it.isFile }.forEach { file ->
                out.putNextEntry(ZipEntry("assets/" + file.relativeTo(schemas).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        val apkAssets =
            Class
                .forName(
                    "android.content.res.ApkAssets",
                ).getMethod("loadFromPath", String::class.java)
                .invoke(null, zip.path)
        val builderClass = Class.forName("android.content.res.AssetManager\$Builder")
        val builder = builderClass.getConstructor().newInstance()
        builderClass.getMethod("addApkAssets", apkAssets.javaClass).invoke(builder, apkAssets)
        return builderClass.getMethod("build").invoke(builder) as AssetManager
    }
}
