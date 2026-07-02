package com.namit.presentation_displays

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngineCache

class PresentationDisplay(context: Context, private val tag: String, display: Display) :
    Presentation(context, display) {

    private var isWindowFocusable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep hardware key input (e.g. HID barcode scanners) routed to the main
        // activity window. A non-focusable window still receives touch events,
        // but never takes Android key input focus when touched. Call
        // setWindowFocusable(true) only while this display needs the soft keyboard.
        isWindowFocusable = false
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

        val flContainer = FrameLayout(context)
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        flContainer.layoutParams = params

        setContentView(flContainer)

        val flutterView = FlutterView(context)
        flContainer.addView(flutterView, params)
        val flutterEngine = FlutterEngineCache.getInstance().get(tag)
        if (flutterEngine != null) {
            flutterView.attachToFlutterEngine(flutterEngine)
        } else {
            Log.e("PresentationDisplay", "Can't find the FlutterEngine with cache name $tag")
        }
    }

    fun setWindowFocusable(focusable: Boolean) {
        // Skip no-op calls: Window.setFlags triggers a window relayout even
        // when the flags are unchanged, and this is called on every data push.
        if (focusable == isWindowFocusable) {
            return
        }
        isWindowFocusable = focusable
        if (focusable) {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        } else {
            window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }
}
