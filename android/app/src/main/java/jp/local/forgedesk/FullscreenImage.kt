package jp.local.forgedesk

import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
internal fun FullscreenAction(bitmap:Bitmap, description:String, original:ByteArray?=null) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick={open=true},contentPadding=PaddingValues(horizontal=6.dp)){Text("全画面表示")}
    if(open) {
        val picture by produceState(bitmap, bitmap, original) {
            if(original!=null) {
                try {value=previewBitmap(original,3072)}
                catch(e:CancellationException){throw e}
                catch(_:Exception){/* The usable preview is already displayed. */}
            } else value=bitmap
        }
        FullscreenImage(if(original==null) bitmap else picture,description,{open=false})
    }
}

@Composable
internal fun FullscreenImage(bitmap:Bitmap, description:String, close:()->Unit,
                             previous:(()->Unit)?=null, next:(()->Unit)?=null) {
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false,
        decorFitsSystemWindows=false,dismissOnClickOutside=false)) {
        val view=LocalView.current
        DisposableEffect(view) {
            val window=(view.parent as DialogWindowProvider).window
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
            if(android.os.Build.VERSION.SDK_INT>=28) {
                window.attributes=window.attributes.apply {
                    layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            val controller=WindowCompat.getInsetsController(window,window.decorView)
            controller.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { /* Android removes this dialog window and restores the underlying window. */ }
        }
        var controls by rememberSaveable { mutableStateOf(true) }
        LaunchedEffect(Unit) { delay(2800); controls=false }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ZoomableResult(bitmap,Modifier.fillMaxSize(),description,onImageTap={controls=!controls})
            if(controls) {
                val colors=ButtonDefaults.textButtonColors(containerColor=Color.Black.copy(alpha=.76f),contentColor=Color.White)
                Row(Modifier.align(Alignment.TopEnd).displayCutoutPadding().padding(8.dp),
                    horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    previous?.let { TextButton(onClick=it,colors=colors){Text("前の画像")} }
                    next?.let { TextButton(onClick=it,colors=colors){Text("次の画像")} }
                    TextButton(onClick=close,colors=colors){Text("全画面を閉じる")}
                }
            }
        }
    }
}
