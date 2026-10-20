package com.fpedroso.bookreader

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The models the app can download and delete, and the state of each.
 *
 * Two very different sources behind one interface, because the reader should not have to know which:
 *
 * - **ML Kit language packs** live in a Google-managed store. The SDK can say whether a pack is
 *   present, download it, and delete it - but it reports **no size and no download progress**, so
 *   those rows show a nominal size and an indeterminate bar. That is a limit of the SDK, verified
 *   against `RemoteModelManager`, not an omission here.
 * - **The Qwen models** are ordinary files fetched from Hugging Face over HTTP, so they get a real
 *   percentage and a real size on disk. They go to the app-specific EXTERNAL files dir: 2.5 GB is
 *   not something to put in internal storage, and external-app storage is still invisible to the
 *   file manager and removed on uninstall.
 */
enum class PackState { ABSENT, DOWNLOADING, READY, FAILED }

data class PackStatus(
    val state: PackState,
    /** Bytes on disk, when the source can report it. Null for ML Kit, which cannot. */
    val bytes: Long? = null,
    /** 0..1 while downloading; null means indeterminate. */
    val progress: Float? = null,
    val detail: String? = null,
)

interface ModelPack {
    val id: String
    val label: String
    val engine: Engine
    /** Nominal size, shown before anything is downloaded. */
    val approxBytes: Long
    suspend fun status(context: Context): PackStatus
    /** Downloads, reporting progress as it goes. Throws on failure. */
    suspend fun download(context: Context, onProgress: (Float?) -> Unit)
    suspend fun delete(context: Context)
}

object Models {

    /** Subdirectory of the app's external files dir holding the downloaded model files. */
    const val DIR = "models"

    private const val MB = 1_000_000L

    /**
     * Every pack, in the order Settings lists them: the cheap default first, the big optional
     * downloads after it.
     *
     * Both ML Kit packs are listed because BOTH are required for a German→English translation -
     * the source language and the target - and a reader who deletes "English" to save space should
     * see that German is still there rather than wonder why translation stopped.
     */
    fun packs(): List<ModelPack> = listOf(
        MlKitPack("de", "German"),
        MlKitPack("en", "English"),
        QwenPack(
            engine = Engine.QWEN_1_7B,
            fileName = "qwen3-1.7b.litertlm",
            url = "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/" +
                "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
            expectedBytes = 977_184_032L,
        ),
        QwenPack(
            engine = Engine.QWEN_4B,
            fileName = "qwen3-4b.litertlm",
            url = "https://huggingface.co/litert-community/Qwen3-4B/resolve/main/" +
                "qwen3_4b_mixed_int4.litertlm",
            expectedBytes = 2_659_053_760L,
        ),
    )

    /** The packs that belong to an engine - what Settings shows under each engine's heading. */
    fun packsFor(engine: Engine): List<ModelPack> = when (engine) {
        Engine.MLKIT -> packs().filter { it is MlKitPack }
        else -> packs().filter { it.engine == engine }
    }

    /** The model file for an engine, if it is on disk and complete. */
    fun modelFile(context: Context, engine: Engine): File? {
        val pack = packs().filterIsInstance<QwenPack>().firstOrNull { it.engine == engine } ?: return null
        val f = File(context.getExternalFilesDir(DIR), pack.fileName)
        return f.takeIf { it.isFile && it.length() >= pack.expectedBytes }
    }
}

/**
 * One ML Kit language pack, managed through `RemoteModelManager`.
 *
 * Delete is offered even for a pack the app is using: the reader can always re-download it, and the
 * alternative - refusing - would be the app deciding it knows better than the person holding the
 * phone. Switching engines is what the UI nudges toward, not a hard block.
 */
class MlKitPack(val language: String, private val name: String) : ModelPack {

    override val id = "mlkit-$language"
    override val label = "ML Kit $name pack"
    override val engine = Engine.MLKIT
    override val approxBytes = 30 * 1_000_000L

    private fun model() = TranslateRemoteModel.Builder(language).build()
    private fun manager() = RemoteModelManager.getInstance()

    override suspend fun status(context: Context): PackStatus {
        val present = runCatching { manager().isModelDownloaded(model()).await() }.getOrNull()
        return when (present) {
            true -> PackStatus(PackState.READY)
            false -> PackStatus(PackState.ABSENT)
            null -> PackStatus(PackState.FAILED, detail = "state unknown")
        }
    }

    override suspend fun download(context: Context, onProgress: (Float?) -> Unit) {
        // ML Kit exposes no download progress for translate models, so the bar stays indeterminate
        // and this call simply returns when the pack is in place.
        onProgress(null)
        manager().download(model(), DownloadConditions.Builder().build()).await()
        onProgress(1f)
    }

    override suspend fun delete(context: Context) {
        manager().deleteDownloadedModel(model()).await()
    }
}

/**
 * One Qwen model file, downloaded with Android's `DownloadManager`.
 *
 * `DownloadManager` rather than a hand-rolled stream: 2.5 GB needs resume, retry and a notification
 * that survives the app being killed, and it already does all three. The download continues even if
 * the app goes away, so [status] is derived from the FILE - complete or not - rather than from
 * in-memory state, and a half-downloaded file reads as absent instead of as ready.
 */
class QwenPack(
    override val engine: Engine,
    val fileName: String,
    val url: String,
    val expectedBytes: Long,
) : ModelPack {

    override val id = engine.id
    override val label = engine.label
    override val approxBytes = expectedBytes

    private fun file(context: Context) = File(context.getExternalFilesDir(Models.DIR), fileName)

    override suspend fun status(context: Context): PackStatus {
        val f = file(context)
        if (!f.isFile) return PackStatus(PackState.ABSENT)
        val len = f.length()
        return when {
            len >= expectedBytes -> PackStatus(PackState.READY, bytes = len)
            else -> PackStatus(
                PackState.ABSENT,
                bytes = len,
                detail = "incomplete (${formatSize(len)} of ${formatSize(expectedBytes)})",
            )
        }
    }

    override suspend fun download(context: Context, onProgress: (Float?) -> Unit) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val target = file(context)
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(label)
            .setDescription("Book Reader translation model")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Models.DIR, fileName)
            // Allowed on mobile data: the size is stated in Settings and the download is deliberate.
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val id = dm.enqueue(request)

        onProgress(0f)
        while (true) {
            delay(500)
            val query = DownloadManager.Query().setFilterById(id)
            val cursor = runCatching { dm.query(query) }.getOrNull() ?: continue
            val finished = cursor.use { c ->
                if (!c.moveToFirst()) return
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                onProgress(if (total > 0) done.toFloat() / total else null)
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> true
                    DownloadManager.STATUS_FAILED -> {
                        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        throw IOException("download failed (reason $reason)")
                    }
                    else -> false
                }
            }
            if (finished) return
        }
    }

    override suspend fun delete(context: Context) {
        file(context).delete()
    }
}

/**
 * Play Services `Task` -> coroutine, the same bridge [Translate] uses for its own calls.
 *
 * Written here rather than adding kotlinx-coroutines-play-services, whose version would then have to
 * stay aligned with the coroutines version Compose already brings in.
 */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { value -> if (cont.isActive) cont.resume(value) }
    addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
    addOnCanceledListener { cont.cancel() }
}
