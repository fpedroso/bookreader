package com.fpedroso.bookreader

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The models the app can download and delete, and the state of each.
 *
 * Only ML Kit language packs live here now. They live in a Google-managed store: the SDK can say
 * whether a pack is present, download it, and delete it - but it reports **no size and no download
 * progress**, so those rows show a nominal size and an indeterminate bar. That is a limit of the SDK,
 * verified against `RemoteModelManager`, not an omission here.
 *
 * A local LLM (Qwen3 1.7B/4B via LiteRT-LM) used to be offered alongside it as an offline, private
 * alternative. It worked, but it cost ~48 MB of native libraries in the APK, a Kotlin 2.4 toolchain
 * bump just to compile the runtime, 1-2.7 GB of downloads per model, and seconds per sentence on a
 * phone - all to be beaten on quality by a cloud call costing a fraction of a cent per book. Removed
 * rather than kept as dead weight; the probe that compared them is in `~/bookreader-probe` and the
 * measurements are in the README.
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

    /**
     * Both ML Kit packs are listed because BOTH are required for a German→English translation - the
     * source language and the target - and a reader who deletes "English" to save space should see
     * that German is still there rather than wonder why translation stopped.
     */
    fun packs(): List<ModelPack> = listOf(
        MlKitPack("de", "German"),
        MlKitPack("en", "English"),
    )

    /**
     * Whether an engine can be used right now.
     *
     * Stated in one place because the condition differs per engine: ML Kit is always usable (it
     * fetches its own packs), and the cloud engine needs a key. Anything else would mean offering a
     * choice that fails on the first tap.
     */
    fun ready(context: Context, engine: Engine, settings: AppSettings): Boolean = when {
        !engine.runnable -> false
        engine.cloud -> settings.apiKey.isNotBlank()
        else -> true
    }

    /**
     * The engine to actually translate with: the chosen one when it is ready, otherwise ML Kit.
     *
     * A stored choice whose key was cleared must not turn every tap into an error - the reader gets
     * ML Kit, and the modal says which engine answered.
     */
    fun resolve(context: Context, selected: Engine, settings: AppSettings): Engine =
        if (ready(context, selected, settings)) selected else Engine.MLKIT
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
