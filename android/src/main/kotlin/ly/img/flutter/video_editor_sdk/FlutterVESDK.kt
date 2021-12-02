package ly.img.flutter.video_editor_sdk

import android.app.Activity
import androidx.annotation.NonNull
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.Result
import ly.img.android.AuthorizationException

import ly.img.android.IMGLY
import ly.img.android.VESDK
import ly.img.android.pesdk.VideoEditorSettingsList
import ly.img.android.pesdk.backend.model.state.LoadSettings
import ly.img.android.pesdk.kotlin_extension.continueWithExceptions
import ly.img.android.pesdk.utils.UriHelper
import ly.img.android.sdk.config.*
import ly.img.android.pesdk.backend.encoder.Encoder
import ly.img.android.pesdk.backend.model.EditorSDKResult
import ly.img.android.pesdk.backend.model.state.VideoCompositionSettings
import ly.img.android.serializer._3.IMGLYFileWriter

import org.json.JSONObject
import java.io.File

import ly.img.flutter.imgly_sdk.FlutterIMGLY

/** FlutterVESDK */
class FlutterVESDK : FlutterIMGLY() {

    companion object {
        const val EDITOR_RESULT_ID = 29064
        const val CAMERA_AND_GALLERY_RESULT = 256756
    }

    var serializationCache: String? = null
    var configCache: HashMap<String, Any>? = null
    private var maxVideoWidthCache: Int? = null
    private var maxVideoHeightCache: Int? = null
    private var maxDurationInSecondsCache: Int? = null

    override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        super.onAttachedToEngine(binding)

        channel = MethodChannel(binding.binaryMessenger, "video_editor_sdk")
        channel.setMethodCallHandler(this)
        IMGLY.initSDK(binding.applicationContext)
        IMGLY.authorize()
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        if (call.method == "openEditor") {
            var config = call.argument<MutableMap<String, Any>>("configuration")
            val serialization = call.argument<String>("serialization")

            if (config != null) {
                config = this.resolveAssets(config)
            }
            config = config as? HashMap<String, Any>

            val video = call.argument<MutableMap<String, Any>>("video")
            if (video != null) {
                val videosList = video["videos"] as ArrayList<String>?
                val videoSource = video["video"] as String?
                val size = video["size"] as? MutableMap<String, Double>

                this.result = result

                if (videoSource != null) {
                    this.present(
                        videoSource.let { EmbeddedAsset(it).resolvedURI },
                        config,
                        serialization
                    )
                } else {
                    val videos = videosList?.mapNotNull { EmbeddedAsset(it).resolvedURI }
                    this.present(videos, config, serialization, size)
                }
            } else {
                result.error("VESDK", "The video must not be null", null)
            }
        } else if (call.method == "selectVideoAndOpenEditor") {
            var config = call.argument<MutableMap<String, Any>>("configuration")
            serializationCache = call.argument<String>("serialization")
            maxDurationInSecondsCache = call.argument<Int>("maxDurationInSeconds")
            maxVideoWidthCache = call.argument<Int>("maxVideoWidth")
            maxVideoHeightCache = call.argument<Int>("maxVideoHeight")
            this.result = result
            if (config != null) {
                config = this.resolveAssets(config)
            }
            configCache = config as? HashMap<String, Any>
            selectVideo()
        } else if (call.method == "unlock") {
            val license = call.argument<String>("license")
            this.result = result
            this.resolveLicense(license)
        } else {
            result.notImplemented()
        }
    }

    /**
     * Configures and presents the editor.
     *
     * @param asset The video source as *String* which should be loaded into the editor.
     * @param config The *Configuration* to configure the editor with as if any.
     * @param serialization The serialization to load into the editor if any.
     */
    override fun present(asset: String, config: HashMap<String, Any>?, serialization: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            val settingsList = VideoEditorSettingsList()

            currentSettingsList = settingsList
            currentConfig = ConfigLoader.readFrom(config ?: mapOf()).also {
                it.applyOn(settingsList)
            }

            settingsList.configure<LoadSettings> { loadSettings ->
                asset.also {
                    loadSettings.source = retrieveURI(it)
                }
            }

            readSerialisation(settingsList, serialization, false)
            startEditor(settingsList, EDITOR_RESULT_ID)
        } else {
            result?.error(
                "VESDK",
                "The video editor is only available in Android 4.3 and later.",
                null
            )
        }
    }

    /**
     * Configures and presents the editor.
     *
     * @param videos The video sources as *List<String>* which should be loaded into the editor.
     * @param config The *Configuration* to configure the editor with as if any.
     * @param serialization The serialization to load into the editor if any.
     */
    private fun present(
        videos: List<String>?,
        config: HashMap<String, Any>?,
        serialization: String?,
        size: Map<String, Any>?
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            val settingsList = VideoEditorSettingsList()
            var source = resolveSize(size)
            currentSettingsList = settingsList

            currentConfig = ConfigLoader.readFrom(config ?: mapOf()).also {
                it.applyOn(settingsList)
            }

            if (videos != null && videos.count() > 0) {
                if (source == null) {
                    if (size != null) {
                        result?.error(
                            "VESDK",
                            "Invalid video size: width and height must be greater than zero.",
                            null
                        )
                        return
                    }
                    val video = videos.first()
                    source = retrieveURI(video)
                }

                settingsList.configure<VideoCompositionSettings> { loadSettings ->
                    videos.forEach {
                        val resolvedSource = retrieveURI(it)
                        loadSettings.addCompositionPart(
                            VideoCompositionSettings.VideoPart(
                                resolvedSource
                            )
                        )
                    }
                }
            } else {
                if (source == null) {
                    result?.error(
                        "VESDK",
                        "A video composition without assets must have a specific size.",
                        null
                    )
                    return
                }
            }

            settingsList.configure<LoadSettings> {
                it.source = source
            }

            readSerialisation(settingsList, serialization, false)
            startEditor(settingsList, EDITOR_RESULT_ID)
        } else {
            result?.error(
                "VESDK",
                "The video editor is only available in Android 4.3 and later.",
                null
            )
            return
        }
    }

    private fun retrieveURI(source: String): Uri {
        return if (source.startsWith("data:")) {
            UriHelper.createFromBase64String(source.substringAfter("base64,"))
        } else {
            val potentialFile = continueWithExceptions { File(source) }
            if (potentialFile?.exists() == true) {
                Uri.fromFile(potentialFile)
            } else {
                ConfigLoader.parseUri(source)
            }
        }
    }

    private fun resolveSize(size: Map<String, Any>?): Uri? {
        val height = size?.get("height") as? Int ?: 0.0
        val width = size?.get("width") as? Int ?: 0.0
        if (height == 0.0 || width == 0.0) {
            return null
        }
        return LoadSettings.compositionSource(width.toInt(),height.toInt(), 60)
    }

    /**
     * Unlocks the SDK with a stringified license.
     *
     * @param license The license as a *String*.
     */
    override fun unlockWithLicense(license: String) {
        try {
            VESDK.initSDKWithLicenseData(license)
            IMGLY.authorize()
            this.result?.success(null)
        } catch (e: AuthorizationException) {
            this.result?.error("Invalid license", "The license must be valid.", e.message)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (data == null) {
            return false
        }
        if (requestCode == CAMERA_AND_GALLERY_RESULT) {
            if (resultCode == Activity.RESULT_OK) {
                val videoUri = data.data
                if (videoUri != null) {
                    present(
                        listOf(videoUri.toString()),
                        configCache,
                        serializationCache,
                        videoSize(videoUri)
                    )
                } else {
                    this.result?.error(
                        "No path",
                        "Failed to get the video path from the intent",
                        null
                    )
                }
                return true
            } else if (resultCode == Activity.RESULT_CANCELED) {
                currentActivity?.runOnUiThread {
                    this.result?.success(null)
                }
                return true
            }
            return false
        } else {
            val intentData = try {
                EditorSDKResult(data)
            } catch (e: EditorSDKResult.NotAnImglyResultException) {
                null
            } ?: return false // If intentData is null the result is not from us.

            if (resultCode == Activity.RESULT_CANCELED && requestCode == EDITOR_RESULT_ID) {
                currentActivity?.runOnUiThread {
                    this.result?.success(null)
                }
                return true
            } else if (resultCode == Activity.RESULT_OK && requestCode == EDITOR_RESULT_ID) {
                val settingsList = intentData.settingsList
                val serializationConfig = currentConfig?.export?.serialization
                val resultUri = intentData.resultUri
                val sourceUri = intentData.sourceUri

                val serialization: Any? = if (serializationConfig?.enabled == true) {
                    skipIfNotExists {
                        settingsList.let { settingsList ->
                            if (serializationConfig.embedSourceImage == true) {
                                Log.i(
                                    "ImglySDK",
                                    "EmbedSourceImage is currently not supported by the Android SDK"
                                )
                            }
                            when (serializationConfig.exportType) {
                                SerializationExportType.FILE_URL -> {
                                    val uri = serializationConfig.filename?.let {
                                        Uri.parse(it)
                                    } ?: Uri.fromFile(File.createTempFile("serialization", ".json"))
                                    Encoder.createOutputStream(uri).use { outputStream ->
                                        IMGLYFileWriter(settingsList).writeJson(outputStream)
                                    }
                                    uri.toString()
                                }
                                SerializationExportType.OBJECT -> {
                                    jsonToMap(JSONObject(IMGLYFileWriter(settingsList).writeJsonAsString()))
                                }
                            }
                        }
                    } ?: run {
                        Log.i(
                            "ImglySDK",
                            "You need to include 'backend:serializer' Module, to use serialisation!"
                        )
                        null
                    }
                } else {
                    null
                }

                val map = mutableMapOf<String, Any?>()
                map["video"] = resultUri.toString()
                map["hasChanges"] = (sourceUri?.path != resultUri?.path)
                map["serialization"] = serialization
                currentActivity?.runOnUiThread {
                    this.result?.success(map)
                }
                return true
            }
            return false
        }
    }

    private fun selectVideo() {

        val intent = Intent(Intent.ACTION_PICK)

        intent.setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")

        if (maxDurationInSecondsCache != null) {
            intent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, maxDurationInSecondsCache);
        }
        val packageManager = currentActivity?.packageManager
        if (packageManager == null) {
            this.result?.error("No activity", "The currentActivity is null", null)
            return
        }
        if (Build.VERSION.SDK_INT >= 30 || intent.resolveActivity(packageManager) != null) {
            currentActivity?.startActivityForResult(intent, CAMERA_AND_GALLERY_RESULT)
                ?: this.result?.error("No activity", "The currentActivity is null", null)
        } else {
            this.result?.error(
                "No Gallery APP",
                "No Gallery APP installed on the phone",
                null
            )
        }
    }

    private fun videoSize(videoUri: Uri): Map<String, Int>? {
        val maxVideoWidth = maxVideoWidthCache
        val maxVideoHeight = maxVideoHeightCache
        if (maxVideoWidth == null || maxVideoHeight == null) {
            return null
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(currentActivity, videoUri)
        } catch (e: Throwable) {
            return mapOf("width" to maxVideoWidth, "height" to maxVideoHeight)
        }

        var width =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                ?: maxVideoWidth
        var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: maxVideoHeight
        retriever.release()
        width = if (width <= maxVideoWidth) width else maxVideoWidth
        height = if (height <= maxVideoHeight) height else maxVideoHeight
        return mapOf("width" to width, "height" to height)
    }
}
