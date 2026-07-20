package messenger.android

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.journeyapps.barcodescanner.CaptureActivity

/**
 * zxing-android-embedded's stock [CaptureActivity] is a bare fullscreen camera view with no
 * chrome at all — nothing on screen hints that the system/gesture back button will close it, and
 * the library's own manifest entry hardcodes `android:screenOrientation="sensorLandscape"`, which
 * rotates the camera preview sideways relative to how someone normally holds a phone to scan a QR
 * code. This subclass changes neither zxing's scanning/permission logic (untouched) nor needs one
 * — see the AndroidManifest.xml entry for this activity, which overrides just the orientation —
 * it only adds one floating circular back button so there's a visible way out.
 */
class VoronScanActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val sizePx = (44 * density).toInt()
        val marginPx = (16 * density).toInt()
        val backButton = TextView(this).apply {
            text = "←"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(140, 0, 0, 0))
            }
            setOnClickListener { finish() }
        }
        addContentView(
            backButton,
            FrameLayout.LayoutParams(sizePx, sizePx, Gravity.TOP or Gravity.START).apply {
                setMargins(marginPx, marginPx, 0, 0)
            },
        )
    }
}
