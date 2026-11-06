package org.dshwatch.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtBackdropTest {
    @Test fun dialRemainsOpaqueAfterDrawingTranslucentTicks() {
        for (panel in listOf(false, true)) for ((width, height) in listOf(480 to 480, 320 to 380)) {
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val dial = DialBackdrop(round = true, panel = panel, deep = false)
            dial.setBounds(0, 0, width, height)
            val canvas = Canvas(output)
            dial.draw(canvas)
            val first = output.getPixel(width / 2, height / 2)
            output.eraseColor(Color.TRANSPARENT)
            dial.draw(canvas)
            assertEquals(first, output.getPixel(width / 2, height / 2))
            for ((x, y) in listOf(0 to 0, width - 1 to height - 1, width / 2 to height - 1)) {
                val color = output.getPixel(x, y)
                assertEquals(255, Color.alpha(color))
                assertTrue("Dial must reach $x,$y", Color.blue(color) in 20..140)
            }
        }
    }
}
