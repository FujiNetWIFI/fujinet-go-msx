package online.fujinet.go.msx.ui

import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import online.fujinet.go.msx.SessionController

// The MSX display is presented at its native ~4:3 aspect; openMSX delivers the
// active picture (with border) as an XRGB8888 frame to the host
// (session_runtime.cpp::PresentTo), and the surface letterboxes to 4:3.
private const val FRAME_RATIO = 4f / 3f

/**
 * Hosts the MSX video output. The native layer renders openMSX's XRGB8888 frames
 * into a [Surface] (session_runtime.cpp::OnFrame); the surface is obtained from a
 * [TextureView]'s [SurfaceTexture].
 *
 * We use a TextureView, not a SurfaceView, on purpose. A SurfaceView lives in its
 * own compositor layer outside the view hierarchy, and on Android 11 / API 30 that
 * layer was being composited over the whole top of the window -- hiding the
 * FunctionBar toolbar (and even the system status bar) behind a black band, while
 * the controls below it drew fine. A TextureView draws inline as an ordinary view,
 * so it can never occlude siblings above or below it. The present path is
 * unchanged: openMSX renders GLES2 offscreen and the session blits the frame to the
 * Surface via ANativeWindow, which works with a SurfaceTexture-backed Surface just
 * as it did with the SurfaceView's holder surface.
 *
 * The view is sized to the MSX's 4:3 aspect ratio and centered on black, so the
 * frame is letter-/pillar-boxed (never stretched).
 */
@Composable
fun EmulatorSurface(
    session: SessionController,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val pillarboxed = maxWidth / maxHeight > FRAME_RATIO
        val surfaceModifier = if (pillarboxed) {
            Modifier.fillMaxHeight().aspectRatio(FRAME_RATIO)
        } else {
            Modifier.fillMaxWidth().aspectRatio(FRAME_RATIO)
        }

        // Landscape / TV: the frame is pillar-boxed, leaving black bars either side.
        // Fill them with a fade from a dim tint of the FS-A1 charcoal keycap colour at
        // the picture edge out to black at the screen edge -- a subtle ambient glow
        // matching the on-screen keyboard's keycaps (the amber accent would be too
        // vivid here). Drawn before the surface so the picture wins any sub-pixel overlap.
        if (pillarboxed) {
            val keycap = Color(0xFF2B2B2B).copy(alpha = 0.3f) // FS-A1 charcoal keycap (MsxKeyboard KeyBg)
            val barWidth = (maxWidth - maxHeight * FRAME_RATIO) / 2
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(barWidth)
                    .background(Brush.horizontalGradient(listOf(Color.Black, keycap))),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(barWidth)
                    .background(Brush.horizontalGradient(listOf(keycap, Color.Black))),
            )
        }

        AndroidView(
            modifier = surfaceModifier,
            factory = { context ->
                TextureView(context).apply {
                    isOpaque = true
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        private var surface: Surface? = null

                        override fun onSurfaceTextureAvailable(
                            texture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            val s = Surface(texture)
                            surface = s
                            s.requestFrameRate60()
                            session.attachSurface(s)
                            session.startIfNeeded()
                            // Mirror SurfaceView's immediate surfaceChanged: publish
                            // the surface dimensions so SDL/openMSX size their output.
                            session.surfaceChanged(s, width, height)
                        }

                        override fun onSurfaceTextureSizeChanged(
                            texture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            surface?.let { s ->
                                s.requestFrameRate60()
                                session.surfaceChanged(s, width, height)
                            }
                        }

                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            session.detachSurface()
                            surface?.release()
                            surface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                    }
                }
            },
        )
    }
}

// Tell the compositor this surface produces a fixed 60 fps. On a 120Hz /
// variable-refresh phone this makes the panel present at 60 (or a 60 multiple)
// instead of judder-mapping 60 fps content onto e.g. 90/120Hz.
private fun Surface.requestFrameRate60() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isValid) {
        setFrameRate(60.0f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
    }
}
