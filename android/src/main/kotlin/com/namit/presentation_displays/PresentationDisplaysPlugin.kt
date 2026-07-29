package com.namit.presentation_displays

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import com.google.gson.Gson
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject

class PresentationDisplaysPlugin :
    FlutterPlugin,
    ActivityAware,
    MethodChannel.MethodCallHandler {

    private lateinit var channel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private var dataToMainChannel: MethodChannel? = null
    private var flutterEngineChannel: MethodChannel? = null
    private var displayConnectedStreamHandler: DisplayConnectedStreamHandler? = null
    private var context: Context? = null
    private var displayManager: DisplayManager? = null
    private var presentation: PresentationDisplay? = null
    private var activeDisplayId: Int? = null
    private var activeRouteName: String? = null
    private var presentationToRestore: PresentationRequest? = null
    private val ownedFlutterEngines = mutableMapOf<String, FlutterEngine>()
    private var activeListenerDisplayManager: DisplayManager? = null
    private val activeDisplayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) {
                onActiveDisplayRemoved(displayId)
            }

            override fun onDisplayChanged(displayId: Int) = Unit
        }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, VIEW_TYPE_ID)
        channel.setMethodCallHandler(this)
        dataToMainChannel = MethodChannel(binding.binaryMessenger, DATA_TO_MAIN_CHANNEL)

        displayManager =
            binding.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        displayConnectedStreamHandler = DisplayConnectedStreamHandler(displayManager)
        eventChannel = EventChannel(binding.binaryMessenger, VIEW_TYPE_EVENTS_ID)
        eventChannel.setStreamHandler(displayConnectedStreamHandler)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        presentationToRestore = null
        dismissPresentation()
        displayConnectedStreamHandler?.dispose()
        displayConnectedStreamHandler = null
        eventChannel.setStreamHandler(null)
        channel.setMethodCallHandler(null)
        dataToMainChannel = null
        flutterEngineChannel = null
        destroyOwnedFlutterEngines()
        context = null
        displayManager = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            SHOW_PRESENTATION_METHOD -> showPresentation(call, result)
            HIDE_PRESENTATION_METHOD -> result.success(dismissPresentation())
            LIST_DISPLAY_METHOD -> listDisplays(call, result)
            TRANSFER_DATA_TO_PRESENTATION_METHOD -> transferDataToPresentation(call, result)
            SET_SECONDARY_DISPLAY_FOCUSABLE_METHOD ->
                setSecondaryDisplayFocusable(call, result)
            else -> result.notImplemented()
        }
    }

    private fun showPresentation(call: MethodCall, result: MethodChannel.Result) {
        try {
            val arguments = JSONObject(call.arguments as String)
            val displayId = arguments.getInt("displayId")
            val routeName = arguments.getString("routerName")
            val currentContext = context
            if (currentContext == null) {
                result.error("CONTEXT_UNAVAILABLE", "Activity context is not available", null)
                return
            }

            val display = displayManager?.getDisplay(displayId)
            if (display == null) {
                result.error("DISPLAY_NOT_FOUND", "Can't find display with displayId $displayId", null)
                return
            }

            result.success(showPresentation(currentContext, display, routeName))
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to show the secondary display", exception)
            result.error(SHOW_PRESENTATION_METHOD, exception.message, null)
        }
    }

    private fun showPresentation(
        currentContext: Context,
        display: Display,
        routeName: String,
    ): Boolean {
        val currentPresentation = presentation
        if (
            currentPresentation?.isShowing == true &&
                activeDisplayId == display.displayId &&
                activeRouteName == routeName
        ) {
            return true
        }

        dismissPresentation()

        val flutterEngine = createFlutterEngine(currentContext, routeName)
        val newPresentation =
            PresentationDisplay(
                context = currentContext,
                display = display,
                flutterEngine = flutterEngine,
                onDataToMain = { arguments ->
                    dataToMainChannel?.invokeMethod(DATA_TRANSFER_METHOD, arguments)
                },
                onDismissed = { dismissedPresentation ->
                    if (presentation === dismissedPresentation) {
                        if (
                            (context as? Activity)?.isChangingConfigurations == true &&
                                activeDisplayId != null &&
                                activeRouteName != null
                        ) {
                            presentationToRestore =
                                PresentationRequest(activeDisplayId!!, activeRouteName!!)
                        }
                        clearPresentationReferences()
                    }
                },
            )

        presentation = newPresentation
        activeDisplayId = display.displayId
        activeRouteName = routeName
        flutterEngineChannel =
            MethodChannel(flutterEngine.dartExecutor.binaryMessenger, ENGINE_CHANNEL)

        return try {
            newPresentation.show()
            registerActiveDisplayListener()
            true
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to attach the secondary presentation", exception)
            newPresentation.dismiss()
            false
        }
    }

    private fun dismissPresentation(): Boolean {
        val currentPresentation = presentation
        if (currentPresentation == null) {
            clearPresentationReferences()
            return false
        }
        currentPresentation.dismiss()
        if (presentation === currentPresentation) {
            clearPresentationReferences()
        }
        return true
    }

    private fun clearPresentationReferences() {
        unregisterActiveDisplayListener()
        presentation = null
        activeDisplayId = null
        activeRouteName = null
        flutterEngineChannel = null
    }

    private fun listDisplays(call: MethodCall, result: MethodChannel.Result) {
        try {
            val category = call.arguments as? String
            val displays = displayManager?.getDisplays(category).orEmpty()
            val displayList =
                displays.map { display ->
                    DisplayJson(
                        display.displayId,
                        display.flags,
                        display.rotation,
                        display.name,
                    )
                }
            result.success(Gson().toJson(displayList))
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to list displays", exception)
            result.error(LIST_DISPLAY_METHOD, exception.message, null)
        }
    }

    private fun transferDataToPresentation(call: MethodCall, result: MethodChannel.Result) {
        val currentPresentation = presentation
        val engineChannel = flutterEngineChannel
        if (currentPresentation?.isShowing != true || engineChannel == null) {
            result.success(false)
            return
        }

        try {
            engineChannel.invokeMethod(DATA_TRANSFER_METHOD, call.arguments)
            result.success(true)
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to transfer data to the secondary display", exception)
            result.error(TRANSFER_DATA_TO_PRESENTATION_METHOD, exception.message, null)
        }
    }

    private fun setSecondaryDisplayFocusable(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        try {
            val focusable = call.arguments as Boolean
            val currentPresentation = presentation
            result.success(
                currentPresentation?.isShowing == true &&
                    currentPresentation.setWindowFocusable(focusable),
            )
        } catch (exception: Exception) {
            result.error(SET_SECONDARY_DISPLAY_FOCUSABLE_METHOD, exception.message, null)
        }
    }

    private fun createFlutterEngine(context: Context, routeName: String): FlutterEngine {
        FlutterEngineCache.getInstance().get(routeName)?.let { return it }

        val applicationContext = context.applicationContext
        val flutterLoader = FlutterInjector.instance().flutterLoader()
        flutterLoader.startInitialization(applicationContext)
        flutterLoader.ensureInitializationComplete(applicationContext, null)

        return FlutterEngine(applicationContext).also { flutterEngine ->
            flutterEngine.navigationChannel.setInitialRoute(routeName)
            val entrypoint =
                DartExecutor.DartEntrypoint(
                    flutterLoader.findAppBundlePath(),
                    SECONDARY_DISPLAY_ENTRYPOINT,
                )
            flutterEngine.dartExecutor.executeDartEntrypoint(entrypoint)
            flutterEngine.lifecycleChannel.appIsResumed()
            FlutterEngineCache.getInstance().put(routeName, flutterEngine)
            ownedFlutterEngines[routeName] = flutterEngine
        }
    }

    private fun destroyOwnedFlutterEngines() {
        val engineCache = FlutterEngineCache.getInstance()
        ownedFlutterEngines.forEach { (tag, flutterEngine) ->
            if (engineCache.get(tag) === flutterEngine) {
                engineCache.remove(tag)
            }
            flutterEngine.destroy()
        }
        ownedFlutterEngines.clear()
    }

    private fun registerActiveDisplayListener() {
        if (activeListenerDisplayManager != null) {
            return
        }
        val currentDisplayManager = displayManager ?: return
        currentDisplayManager.registerDisplayListener(
            activeDisplayListener,
            Handler(Looper.getMainLooper()),
        )
        activeListenerDisplayManager = currentDisplayManager
    }

    private fun unregisterActiveDisplayListener() {
        activeListenerDisplayManager?.unregisterDisplayListener(activeDisplayListener)
        activeListenerDisplayManager = null
    }

    private fun onActiveDisplayRemoved(displayId: Int) {
        if (activeDisplayId == displayId) {
            dismissPresentation()
        }
        if (presentationToRestore?.displayId == displayId) {
            presentationToRestore = null
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        context = binding.activity
        displayManager = binding.activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    }

    override fun onDetachedFromActivity() {
        presentationToRestore = null
        dismissPresentation()
        context = null
        displayManager = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        context = binding.activity
        displayManager = binding.activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

        val request = presentationToRestore
        presentationToRestore = null
        if (request != null) {
            val display = displayManager?.getDisplay(request.displayId)
            if (display != null) {
                showPresentation(binding.activity, display, request.routeName)
            }
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        if (presentation?.isShowing == true && activeDisplayId != null && activeRouteName != null) {
            presentationToRestore =
                PresentationRequest(activeDisplayId!!, activeRouteName!!)
        }
        dismissPresentation()
        context = null
        displayManager = null
    }

    private data class PresentationRequest(
        val displayId: Int,
        val routeName: String,
    )

    private companion object {
        const val TAG = "PresentationDisplays"
        const val VIEW_TYPE_ID = "presentation_displays_plugin"
        const val VIEW_TYPE_EVENTS_ID = "presentation_displays_plugin_events"
        const val ENGINE_CHANNEL = "${VIEW_TYPE_ID}_engine"
        const val DATA_TO_MAIN_CHANNEL = "${VIEW_TYPE_ID}_to_main"
        const val SHOW_PRESENTATION_METHOD = "showPresentation"
        const val HIDE_PRESENTATION_METHOD = "hidePresentation"
        const val LIST_DISPLAY_METHOD = "listDisplay"
        const val TRANSFER_DATA_TO_PRESENTATION_METHOD = "transferDataToPresentation"
        const val SET_SECONDARY_DISPLAY_FOCUSABLE_METHOD = "setSecondaryDisplayFocusable"
        const val DATA_TRANSFER_METHOD = "DataTransfer"
        const val SECONDARY_DISPLAY_ENTRYPOINT = "secondaryDisplayMain"
    }
}

private class DisplayConnectedStreamHandler(
    private val displayManager: DisplayManager?,
) : EventChannel.StreamHandler {

    private var sink: EventChannel.EventSink? = null
    private var handler: Handler? = null

    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                sink?.success(1)
            }

            override fun onDisplayRemoved(displayId: Int) {
                sink?.success(0)
            }

            override fun onDisplayChanged(displayId: Int) = Unit
        }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        dispose()
        sink = events
        handler = Handler(Looper.getMainLooper())
        displayManager?.registerDisplayListener(displayListener, handler)
    }

    override fun onCancel(arguments: Any?) {
        dispose()
    }

    fun dispose() {
        if (handler != null) {
            displayManager?.unregisterDisplayListener(displayListener)
        }
        sink = null
        handler = null
    }
}
