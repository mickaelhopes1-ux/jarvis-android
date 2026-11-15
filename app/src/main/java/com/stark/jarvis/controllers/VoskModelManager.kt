package com.stark.jarvis.controllers

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import android.util.Log
import com.stark.jarvis.data.JarvisRuntimeStore
import com.stark.jarvis.data.VoskDownloadStage
import com.stark.jarvis.data.VoskModelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.zip.ZipInputStream

sealed class VoskPrepareResult {
    data class Ready(val modelPath: String) : VoskPrepareResult()
    data class Failed(val errorMessage: String) : VoskPrepareResult()
}

/**
 * Gère le téléchargement initial, la vérification d'espace disque et l'extraction sécurisée
 * du petit modèle français Vosk (vosk-model-small-fr) dans le stockage privé de l'application.
 * Aucun compte et aucune clé API ne sont nécessaires.
 */
object VoskModelManager {

    private const val TAG = "VoskModelManager"

    const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"
    private const val MODELS_ROOT_DIR = "vosk_models"
    private const val MODEL_DIR_NAME = "vosk-model-small-fr"
    private const val TEMP_DIR_NAME = "vosk-model-small-fr_tmp"
    private const val TEMP_ZIP_NAME = "vosk-model-small-fr.zip.tmp"
    private const val READY_MARKER_NAME = ".vosk_ready"

    // ~42 Mo compressé + ~125 Mo décompressé : on exige 180 Mo libres pour garantir l'extraction
    private const val REQUIRED_FREE_BYTES = 180L * 1024L * 1024L
    private const val ESTIMATED_ZIP_BYTES = 42_233_323L

    private val downloadMutex = Mutex()

    fun getModelDirectory(context: Context): File {
        val root = File(context.applicationContext.filesDir, MODELS_ROOT_DIR)
        return File(root, MODEL_DIR_NAME)
    }

    fun isModelReady(context: Context): Boolean {
        val dir = getModelDirectory(context)
        val readyMarker = File(dir, READY_MARKER_NAME)
        val mdl = File(dir, "am/final.mdl")
        val conf = File(dir, "conf/model.conf")
        return dir.isDirectory && readyMarker.exists() && mdl.exists() && conf.exists()
    }

    fun getReadyModelPath(context: Context): String? {
        return if (isModelReady(context)) getModelDirectory(context).absolutePath else null
    }

    fun refreshPublishedStatus(context: Context) {
        val readyPath = getReadyModelPath(context)
        if (readyPath != null) {
            JarvisRuntimeStore.updateVoskState(
                VoskModelState(
                    stage = VoskDownloadStage.READY,
                    progress = 1f,
                    statusText = "Modèle vocal FR hors ligne prêt (mot-clé : « jarvis »)",
                    errorMessage = null,
                    modelPath = readyPath
                )
            )
        } else {
            val current = JarvisRuntimeStore.voskState.value
            if (current.stage != VoskDownloadStage.DOWNLOADING && current.stage != VoskDownloadStage.EXTRACTING) {
                JarvisRuntimeStore.updateVoskState(
                    VoskModelState(
                        stage = VoskDownloadStage.NOT_DOWNLOADED,
                        progress = 0f,
                        statusText = "Modèle vocal FR hors ligne (~42 Mo) non téléchargé",
                        errorMessage = current.errorMessage,
                        modelPath = null
                    )
                )
            }
        }
    }

    suspend fun ensureModelReady(context: Context, forceRetry: Boolean = false): VoskPrepareResult {
        val appContext = context.applicationContext

        return downloadMutex.withLock {
            if (!forceRetry) {
                val existing = getReadyModelPath(appContext)
                if (existing != null) {
                    JarvisRuntimeStore.updateVoskState(
                        VoskModelState(
                            stage = VoskDownloadStage.READY,
                            progress = 1f,
                            statusText = "Modèle vocal FR hors ligne prêt (mot-clé : « jarvis »)",
                            errorMessage = null,
                            modelPath = existing
                        )
                    )
                    return@withLock VoskPrepareResult.Ready(existing)
                }
            }

            withContext(Dispatchers.IO) {
                downloadAndExtractInternal(appContext)
            }
        }
    }

    private fun downloadAndExtractInternal(appContext: Context): VoskPrepareResult {
        JarvisRuntimeStore.updateVoskState(
            VoskModelState(
                stage = VoskDownloadStage.CHECKING,
                progress = 0f,
                statusText = "Vérification de l'espace libre et du réseau...",
                errorMessage = null,
                modelPath = null
            )
        )

        val rootDir = File(appContext.filesDir, MODELS_ROOT_DIR)
        if (!rootDir.exists() && !rootDir.mkdirs()) {
            val err = "Impossible de créer le dossier de stockage privé du modèle vocal."
            publishError(err)
            return VoskPrepareResult.Failed(err)
        }

        // 1. Vérification de l'espace disque disponible
        val availableBytes = getAvailableInternalBytes(rootDir)
        if (availableBytes < REQUIRED_FREE_BYTES) {
            val freeMb = availableBytes / (1024L * 1024L)
            val err = "Espace de stockage insuffisant ($freeMb Mo disponibles, 180 Mo requis pour le modèle vocal français)."
            publishError(err)
            return VoskPrepareResult.Failed(err)
        }

        // 2. Vérification de la connexion réseau
        if (!hasInternetConnection(appContext)) {
            val err = "Connexion Internet indisponible pour télécharger le modèle vocal français (~42 Mo)."
            publishError(err)
            return VoskPrepareResult.Failed(err)
        }

        val tempZip = File(rootDir, TEMP_ZIP_NAME)
        val tempExtractDir = File(rootDir, TEMP_DIR_NAME)
        val finalDir = File(rootDir, MODEL_DIR_NAME)

        cleanupTemp(tempZip, tempExtractDir)

        var connection: HttpURLConnection? = null
        try {
            JarvisRuntimeStore.updateVoskState(
                VoskModelState(
                    stage = VoskDownloadStage.DOWNLOADING,
                    progress = 0.01f,
                    statusText = "Connexion au serveur Vosk (vosk-model-small-fr)...",
                    errorMessage = null
                )
            )

            connection = openFollowingRedirects(MODEL_URL)
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val err = "Le téléchargement du modèle vocal a échoué (HTTP $responseCode). Réessayez plus tard."
                publishError(err)
                return VoskPrepareResult.Failed(err)
            }

            val contentLength = connection.contentLengthLong.let {
                if (it > 0L) it else ESTIMATED_ZIP_BYTES
            }

            BufferedInputStream(connection.inputStream, 32 * 1024).use { input ->
                FileOutputStream(tempZip).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var downloaded = 0L
                    var lastPublishedPercent = -1

                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val rawRatio = (downloaded.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
                        val percent = (rawRatio * 100f).toInt()
                        if (percent != lastPublishedPercent) {
                            lastPublishedPercent = percent
                            val dlMb = downloaded / (1024f * 1024f)
                            val totMb = contentLength / (1024f * 1024f)
                            JarvisRuntimeStore.updateVoskState(
                                VoskModelState(
                                    stage = VoskDownloadStage.DOWNLOADING,
                                    progress = rawRatio * 0.80f,
                                    statusText = String.format(
                                        java.util.Locale.FRANCE,
                                        "Téléchargement modèle FR : %.1f / %.1f Mo (%d%%)",
                                        dlMb,
                                        totMb,
                                        percent
                                    ),
                                    errorMessage = null
                                )
                            )
                        }
                    }
                    output.flush()
                }
            }

            // 3. Extraction sécurisée (protection Zip-Slip)
            JarvisRuntimeStore.updateVoskState(
                VoskModelState(
                    stage = VoskDownloadStage.EXTRACTING,
                    progress = 0.82f,
                    statusText = "Extraction et installation du modèle vocal hors ligne...",
                    errorMessage = null
                )
            )

            if (!tempExtractDir.mkdirs()) {
                val err = "Impossible de préparer le dossier d'extraction du modèle vocal."
                publishError(err)
                return VoskPrepareResult.Failed(err)
            }

            val destCanonical = tempExtractDir.canonicalPath
            val zipSize = tempZip.length().coerceAtLeast(1L)
            var compressedReadApprox = 0L

            ZipInputStream(BufferedInputStream(FileInputStream(tempZip), 32 * 1024)).use { zis ->
                val copyBuf = ByteArray(32 * 1024)
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val rawName = entry.name.replace('\\', '/')
                    // Retirer le dossier racine "vosk-model-small-fr-0.22/"
                    val trimmedName = if (rawName.contains('/')) {
                        rawName.substringAfter('/')
                    } else {
                        rawName
                    }

                    if (trimmedName.isBlank()) {
                        zis.closeEntry()
                        continue
                    }

                    val outFile = File(tempExtractDir, trimmedName)
                    val outCanonical = outFile.canonicalPath
                    if (!outCanonical.startsWith(destCanonical + File.separator)) {
                        throw IOException("Entrée ZIP non sécurisée : $rawName")
                    }

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            while (true) {
                                val count = zis.read(copyBuf)
                                if (count < 0) break
                                fos.write(copyBuf, 0, count)
                            }
                            fos.flush()
                        }
                    }

                    if (entry.compressedSize > 0L) {
                        compressedReadApprox += entry.compressedSize
                        val extractRatio = (compressedReadApprox.toFloat() / zipSize.toFloat()).coerceIn(0f, 1f)
                        JarvisRuntimeStore.updateVoskState(
                            VoskModelState(
                                stage = VoskDownloadStage.EXTRACTING,
                                progress = 0.82f + extractRatio * 0.16f,
                                statusText = "Extraction du modèle vocal français (${(extractRatio * 100).toInt()}%)...",
                                errorMessage = null
                            )
                        )
                    }
                    zis.closeEntry()
                }
            }

            // Validation d'intégrité des fichiers essentiels Vosk
            val amMdl = File(tempExtractDir, "am/final.mdl")
            val confFile = File(tempExtractDir, "conf/model.conf")
            if (!amMdl.exists() || !confFile.exists()) {
                val err = "Archive du modèle français incomplète. Veuillez réessayer."
                cleanupTemp(tempZip, tempExtractDir)
                publishError(err)
                return VoskPrepareResult.Failed(err)
            }

            File(tempExtractDir, READY_MARKER_NAME).writeText("ok")
            tempZip.delete()

            if (finalDir.exists()) {
                finalDir.deleteRecursively()
            }
            if (!tempExtractDir.renameTo(finalDir)) {
                val err = "Impossible de finaliser l'installation du modèle vocal."
                cleanupTemp(tempZip, tempExtractDir)
                publishError(err)
                return VoskPrepareResult.Failed(err)
            }

            val readyPath = finalDir.absolutePath
            JarvisRuntimeStore.updateVoskState(
                VoskModelState(
                    stage = VoskDownloadStage.READY,
                    progress = 1f,
                    statusText = "Modèle vocal FR hors ligne prêt (mot-clé : « jarvis »)",
                    errorMessage = null,
                    modelPath = readyPath
                )
            )
            JarvisRuntimeStore.appendLog(
                "Modèle vocal français hors ligne (Vosk) installé dans le stockage privé.",
                isJarvis = true
            )
            return VoskPrepareResult.Ready(readyPath)
        } catch (e: UnknownHostException) {
            Log.e(TAG, "Réseau indisponible (DNS)", e)
            cleanupTemp(tempZip, tempExtractDir)
            val err = "Réseau absent ou DNS inaccessible. Vérifiez le Wi-Fi/4G puis réessayez."
            publishError(err)
            return VoskPrepareResult.Failed(err)
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Délai dépassé lors du téléchargement", e)
            cleanupTemp(tempZip, tempExtractDir)
            val err = "Délai de téléchargement dépassé. Vérifiez votre connexion puis réessayez."
            publishError(err)
            return VoskPrepareResult.Failed(err)
        } catch (e: ConnectException) {
            Log.e(TAG, "Impossible de joindre le serveur", e)
            cleanupTemp(tempZip, tempExtractDir)
            val err = "Connexion refusée lors du téléchargement du modèle français."
            publishError(err)
            return VoskPrepareResult.Failed(err)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur téléchargement/extraction Vosk", e)
            cleanupTemp(tempZip, tempExtractDir)
            val err = "Échec de l'installation du modèle vocal : ${e.localizedMessage ?: "erreur inconnue"}."
            publishError(err)
            return VoskPrepareResult.Failed(err)
        } finally {
            connection?.disconnect()
        }
    }

    private fun openFollowingRedirects(startUrl: String): HttpURLConnection {
        var currentUrl = startUrl
        repeat(5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "JarvisAndroid/1.0")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IOException("Redirection HTTP sans destination.")
                }
                currentUrl = URL(URL(currentUrl), location).toString()
            } else {
                return conn
            }
        }
        throw IOException("Trop de redirections lors du téléchargement du modèle.")
    }

    private fun publishError(message: String) {
        JarvisRuntimeStore.updateVoskState(
            VoskModelState(
                stage = VoskDownloadStage.ERROR,
                progress = 0f,
                statusText = message,
                errorMessage = message,
                modelPath = null
            )
        )
        JarvisRuntimeStore.appendLog(message, isJarvis = true, isError = true)
    }

    private fun cleanupTemp(tempZip: File, tempExtractDir: File) {
        try {
            if (tempZip.exists()) tempZip.delete()
            if (tempExtractDir.exists()) tempExtractDir.deleteRecursively()
        } catch (_: Exception) {
            // Ignorer
        }
    }

    private fun getAvailableInternalBytes(dir: File): Long {
        return try {
            val stat = StatFs(dir.absolutePath)
            stat.availableBytes
        } catch (_: Exception) {
            0L
        }
    }

    private fun hasInternetConnection(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }
}
