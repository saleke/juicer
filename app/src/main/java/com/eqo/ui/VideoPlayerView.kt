package com.eqo.ui

import android.net.Uri
import android.view.ViewGroup
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.eqo.ui.gl.SharpeningVideoView

/**
 * Media3 ExoPlayer video surface for Jetpack Compose with real-time
 * OpenGL ES 2.0 dynamic sharpening boost (Component 3 §2).
 */
@Composable
fun VideoPlayerView(
    uri: Uri,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 16f / 9f,
    sharpness: Float = 0.0f,
) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_ALL
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            SharpeningVideoView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                attachPlayer(exoPlayer)
            }
        },
        update = { view ->
            view.sharpness = sharpness
            view.attachPlayer(exoPlayer)
        },
        onRelease = { view ->
            view.release()
        },
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(12.dp)),
    )
}
