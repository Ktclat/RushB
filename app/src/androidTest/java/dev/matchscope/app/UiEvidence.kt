package dev.matchscope.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

internal fun ComposeContentTestRule.saveEvidence(name: String) {
    val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "ui-evidence-$name.png")
    file.outputStream().use { onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
}
