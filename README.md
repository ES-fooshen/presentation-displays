# presentation_displays

Flutter plugin for rendering Flutter UI on a connected secondary display.

## Features

- Lists connected displays and reports display connection changes.
- Shows and hides a Flutter route on a secondary display.
- Transfers data from the main Flutter engine to the secondary engine.
- Transfers data from the secondary Flutter engine back to the main engine on Android.
- Keeps Android presentation windows non-focusable for HID scanner routing, with an
  explicit API for temporary keyboard focus.
- Supports Android presentations and the existing iOS external-display implementation.

## Requirements

- Dart 3.12 or later.
- Flutter 3.44 or later.
- Android API 21 or later.

## Setup

Define a secondary Dart entry point. The plugin uses the route passed to
`showSecondaryDisplay` as the secondary engine's initial route.

```dart
void main() {
  runApp(const MainApp());
}

@pragma('vm:entry-point')
void secondaryDisplayMain() {
  runApp(const SecondaryApp());
}
```

## Show A Secondary Display

```dart
final DisplayManager displayManager = DisplayManager();
final List<Display> displays = await displayManager.getDisplays() ?? <Display>[];

if (displays.length > 1) {
  await displayManager.showSecondaryDisplay(
    displayId: displays.last.displayId!,
    routerName: '/presentation',
  );
}
```

## Main To Secondary Data

Send data from the main Flutter engine:

```dart
await displayManager.transferDataToPresentation(<String, dynamic>{
  'total': 42.00,
});
```

Receive it in the secondary Flutter engine:

```dart
SecondaryDisplay(
  callback: (dynamic data) {
    // Update the secondary display.
  },
  child: const PresentationView(),
);
```

## Secondary To Main Data

Register the listener in the main Flutter engine:

```dart
displayManager.listenDataFromPresentationDisplay((dynamic data) {
  // Handle input from the secondary display.
});
```

Send data from the secondary Flutter engine:

```dart
await DisplayManager().transferDataToMain(<String, dynamic>{
  'customerName': 'Customer',
});
```

Remove the main-engine listener when it is no longer needed:

```dart
displayManager.removeDataFromPresentationDisplayListener();
```

Secondary-to-main transfer is currently implemented on Android. The existing iOS
implementation remains one-way.

## Android Focus Control

Android presentation windows start non-focusable so touching the secondary display
does not redirect HID barcode scanner input away from the main activity.

Enable focus only while the secondary display needs keyboard input:

```dart
await displayManager.setSecondaryDisplayFocusable(true);
```

Restore scanner routing to the main display when input completes:

```dart
await displayManager.setSecondaryDisplayFocusable(false);
```

The secondary display still receives touch events while non-focusable.

## Display Events

```dart
displayManager.connectedDisplaysChangedStream?.listen((int? event) {
  // 1 means a display was connected; 0 means a display was disconnected.
});
```

See the bundled example for a complete two-way Android presentation flow.
