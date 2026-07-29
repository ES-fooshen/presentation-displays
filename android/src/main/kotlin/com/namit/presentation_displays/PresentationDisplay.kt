package com.namit.presentation_displays

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class PresentationDisplay(
    context: Context,
    display: Display,
    private val flutterEngine: FlutterEngine,
    private val onDataToMain: (Any?) -> Unit,
    private val onDismissed: (PresentationDisplay) -> Unit,
) : Presentation(context, display) {

    private var isWindowFocusable = false
    private var flutterView: FlutterView? = null
    private var dataToMainChannel: MethodChannel? = null
    private var isCleanedUp = false

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

        flutterView = FlutterView(context).also { view ->
            flContainer.addView(view, params)
            view.attachToFlutterEngine(flutterEngine)
        }

        dataToMainChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            DATA_TO_MAIN_CHANNEL,
        ).also { methodChannel ->
            methodChannel.setMethodCallHandler { call, result ->
                when (call.method) {
                    TRANSFER_DATA_TO_MAIN_METHOD -> {
                        onDataToMain(call.arguments)
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
        }
    }

    fun setWindowFocusable(focusable: Boolean): Boolean {
        // Skip no-op calls: Window.setFlags triggers a window relayout even
        // when the flags are unchanged, and this is called on every data push.
        if (focusable == isWindowFocusable) {
            return true
        }

        val presentationWindow = window ?: return false
        isWindowFocusable = focusable
        if (focusable) {
            presentationWindow.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            presentationWindow.decorView.requestFocus()
        } else {
            presentationWindow.decorView.clearFocus()
            presentationWindow.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        return true
    }

    override fun dismiss() {
        cleanup()
        super.dismiss()
    }

    override fun onDetachedFromWindow() {
        cleanup()
        super.onDetachedFromWindow()
    }

    private fun cleanup() {
        if (isCleanedUp) {
            return
        }

        isCleanedUp = true
        dataToMainChannel?.setMethodCallHandler(null)
        dataToMainChannel = null
        flutterView?.detachFromFlutterEngine()
        flutterView = null
        onDismissed(this)
    }

    private companion object {
        const val DATA_TO_MAIN_CHANNEL = "presentation_displays_plugin_to_main"
        const val TRANSFER_DATA_TO_MAIN_METHOD = "transferDataToMain"
    }
}
