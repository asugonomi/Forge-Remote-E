package jp.local.forgedesk

import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class PreviewPreferences(val enabled:Boolean?,val steps:Int?,val interval:Long)

/** Reading the PC settings is optional; its progress handler always enforces its own settings. */
internal suspend fun readPreviewPreferences(client:ForgeClient):PreviewPreferences = withContext(Dispatchers.IO) {
    val values=JSONObject(client.get("/sdapi/v1/options").toString(Charsets.UTF_8))
    PreviewPreferences(
        if(values.has("live_previews_enable")) values.getBoolean("live_previews_enable") else null,
        if(values.has("show_progress_every_n_steps")) values.getInt("show_progress_every_n_steps") else null,
        values.optLong("live_preview_refresh_period",1200).coerceIn(1000,5000)
    )
}

internal fun previewAppVisible():Boolean {
    val info=ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(info)
    return info.importance<=ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
}

/** Downsample a single server preview and reject large compressed or decoded images before allocating pixels. */
internal suspend fun decodePreviewFrame(uri:String):Bitmap=withContext(Dispatchers.Default) {
    val separator=uri.indexOf(',')
    require(separator in 1..40) { "途中画像の形式を読み取れません" }
    val header=uri.substring(0,separator).lowercase(Locale.ROOT)
    require(header in setOf("data:image/jpeg;base64","data:image/jpg;base64","data:image/png;base64",
        "data:image/webp;base64","data:image/gif;base64")) { "途中画像の形式に対応していません" }
    val maxBytes=4*1024*1024
    val length=uri.length-separator-1
    require(length in 1..((maxBytes+2)/3*4)) { "途中画像が大きすぎます" }
    val bytes=Base64.decode(uri.substring(separator+1),Base64.DEFAULT)
    require(bytes.size<=maxBytes) { "途中画像が大きすぎます" }
    val options=BitmapFactory.Options().apply{inJustDecodeBounds=true}
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
    require(options.outWidth in 1..8192 && options.outHeight in 1..8192 &&
        options.outWidth.toLong()*options.outHeight<=32_000_000L) { "途中画像の寸法に対応していません" }
    var divisor=1
    while(maxOf(options.outWidth,options.outHeight)/divisor>768)divisor*=2
    options.inJustDecodeBounds=false
    options.inSampleSize=divisor
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,options) ?: error("途中画像をデコードできません")
}
