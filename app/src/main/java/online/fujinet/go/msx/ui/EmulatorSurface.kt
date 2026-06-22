package online.fujinet.go.msx.ui

import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import online.fujinet.go.msx.SessionController

// The MSX display is presented at its native ~4:3 aspect; openMSX delivers the
// active picture (with border) as an XRGB8888 frame to the host
// (session_runtime.cpp::PresentTo), and the surface letterboxes to 4:3.
private const val FRAME_RATIO = 4f / 3f

/**
 * Hosts the MSX video output. The native layer renders openMSX's XRGB8888 frames
 * into this SurfaceView's Surface (session_runtime.cpp::OnFrame). The surface is
 * sized to the MSX's 4:3 aspect ratio and centered on black, so the frame is
 * letter-/pillar-boxed (never stretched).
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
        val surfaceModifier = if (maxWidth / maxHeight > FRAME_RATIO) {
            Modifier.fillMaxHeight().aspectRatio(FRAME_RATIO)
        } else {
            Modifier.fillMaxWidth().aspectRatio(FRAME_RATIO)
        }

        AndroidView(
            modifier = surfaceModifier,
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            holder.requestFrameRate60()
                            session.attachSurface(holder.surface)
                            session.startIfNeeded()
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            holder.requestFrameRate60()
                            session.surfaceChanged(holder.surface, width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            session.detachSurface()
                        }
                    })
                }
            },
        )
    }
}

private fun SurfaceHolder.requestFrameRate60() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && surface.isValid) {
        surface.setFrameRate(60.0f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
    }
}
