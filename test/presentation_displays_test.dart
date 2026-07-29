import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:presentation_displays/displays_manager.dart';
import 'package:presentation_displays/secondary_display.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const MethodChannel displayChannel = MethodChannel(
    'presentation_displays_plugin',
  );
  const MethodChannel dataToMainChannel = MethodChannel(
    'presentation_displays_plugin_to_main',
  );
  final TestDefaultBinaryMessenger messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  late DisplayManager displayManager;
  late List<MethodCall> displayCalls;
  late List<MethodCall> dataToMainCalls;

  setUp(() {
    displayManager = DisplayManager();
    displayCalls = <MethodCall>[];
    dataToMainCalls = <MethodCall>[];

    messenger.setMockMethodCallHandler(displayChannel, (MethodCall call) async {
      displayCalls.add(call);
      if (call.method == 'listDisplay') {
        return jsonEncode(<Map<String, dynamic>>[
          <String, dynamic>{
            'displayId': 0,
            'flags': 0,
            'name': 'Built-in Screen',
            'rotation': 0,
          },
          <String, dynamic>{
            'displayId': 7,
            'flags': 1,
            'name': 'Customer Display',
            'rotation': 0,
          },
        ]);
      }
      return true;
    });
    messenger.setMockMethodCallHandler(dataToMainChannel, (
      MethodCall call,
    ) async {
      dataToMainCalls.add(call);
      return true;
    });
  });

  tearDown(() {
    displayManager.removeDataFromPresentationDisplayListener();
    messenger.setMockMethodCallHandler(displayChannel, null);
    messenger.setMockMethodCallHandler(dataToMainChannel, null);
  });

  test('lists displays and safely handles an out-of-range index', () async {
    final displays = await displayManager.getDisplays();

    expect(displays, hasLength(2));
    expect(displays![1].displayId, 7);
    expect(await displayManager.getNameByDisplayId(7), 'Customer Display');
    expect(await displayManager.getNameByIndex(1), 'Customer Display');
    expect(await displayManager.getNameByIndex(2), isNull);
    expect(await displayManager.getNameByIndex(-1), isNull);
  });

  test('preserves existing presentation control method contracts', () async {
    expect(
      await displayManager.showSecondaryDisplay(
        displayId: 7,
        routerName: '/presentation',
      ),
      isTrue,
    );
    expect(await displayManager.transferDataToPresentation('checkout'), isTrue);
    expect(await displayManager.setSecondaryDisplayFocusable(true), isTrue);
    expect(await displayManager.hideSecondaryDisplay(displayId: 7), isTrue);

    expect(displayCalls.map((MethodCall call) => call.method), <String>[
      'showPresentation',
      'transferDataToPresentation',
      'setSecondaryDisplayFocusable',
      'hidePresentation',
    ]);
    expect(
      jsonDecode(displayCalls.first.arguments as String),
      <String, dynamic>{'displayId': 7, 'routerName': '/presentation'},
    );
  });

  test('sends data to the main display on the additive channel', () async {
    expect(
      await displayManager.transferDataToMain(<String, dynamic>{
        'value': 'customer',
      }),
      isTrue,
    );

    expect(dataToMainCalls, hasLength(1));
    expect(dataToMainCalls.single.method, 'transferDataToMain');
    expect(dataToMainCalls.single.arguments, <String, dynamic>{
      'value': 'customer',
    });
  });

  test(
    'receives data from the presentation display on the main engine',
    () async {
      dynamic receivedData;
      displayManager.listenDataFromPresentationDisplay((dynamic arguments) {
        receivedData = arguments;
      });

      await messenger.handlePlatformMessage(
        dataToMainChannel.name,
        dataToMainChannel.codec.encodeMethodCall(
          const MethodCall('DataTransfer', <String, dynamic>{
            'value': 'customer',
          }),
        ),
        (ByteData? data) {},
      );

      expect(receivedData, <String, dynamic>{'value': 'customer'});
    },
  );

  test('ignores unrelated methods on the data-to-main channel', () async {
    dynamic receivedData;
    displayManager.listenDataFromPresentationDisplay((dynamic arguments) {
      receivedData = arguments;
    });

    await messenger.handlePlatformMessage(
      dataToMainChannel.name,
      dataToMainChannel.codec.encodeMethodCall(
        const MethodCall('unrelatedMethod', 'ignored'),
      ),
      (ByteData? data) {},
    );

    expect(receivedData, isNull);
  });

  testWidgets('SecondaryDisplay preserves and disposes its existing listener', (
    WidgetTester tester,
  ) async {
    const MethodChannel presentationEngineChannel = MethodChannel(
      'presentation_displays_plugin_engine',
    );
    final List<dynamic> receivedData = <dynamic>[];

    await tester.pumpWidget(
      SecondaryDisplay(callback: receivedData.add, child: const SizedBox()),
    );
    await messenger.handlePlatformMessage(
      presentationEngineChannel.name,
      presentationEngineChannel.codec.encodeMethodCall(
        const MethodCall('DataTransfer', 'checkout'),
      ),
      (ByteData? data) {},
    );

    expect(receivedData, <dynamic>['checkout']);

    await tester.pumpWidget(const SizedBox());
    await messenger.handlePlatformMessage(
      presentationEngineChannel.name,
      presentationEngineChannel.codec.encodeMethodCall(
        const MethodCall('DataTransfer', 'ignored'),
      ),
      (ByteData? data) {},
    );

    expect(receivedData, <dynamic>['checkout']);
  });
}
