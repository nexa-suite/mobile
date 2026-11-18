import 'package:firebase_app_installations/firebase_app_installations.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';

import '../application/buyer_push_registration.dart';

final class FirebaseBuyerPushDevice implements BuyerPushDevice {
  FirebaseBuyerPushDevice(this._messaging);

  final FirebaseMessaging _messaging;

  static Future<FirebaseBuyerPushDevice?> configure() async {
    if (kIsWeb ||
        !{
          TargetPlatform.android,
          TargetPlatform.iOS,
        }.contains(defaultTargetPlatform)) {
      return null;
    }
    const projectId = String.fromEnvironment('NEXA_FIREBASE_PROJECT_ID');
    const apiKey = String.fromEnvironment('NEXA_FIREBASE_API_KEY');
    const senderId = String.fromEnvironment('NEXA_FIREBASE_SENDER_ID');
    const androidAppId = String.fromEnvironment('NEXA_FIREBASE_ANDROID_APP_ID');
    const iosAppId = String.fromEnvironment('NEXA_FIREBASE_IOS_APP_ID');
    const iosBundleId = String.fromEnvironment('NEXA_FIREBASE_IOS_BUNDLE_ID');
    final appId = defaultTargetPlatform == TargetPlatform.iOS
        ? iosAppId
        : androidAppId;
    if ([projectId, apiKey, senderId, appId].any((value) => value.isEmpty)) {
      return null;
    }
    try {
      await Firebase.initializeApp(
        options: FirebaseOptions(
          apiKey: apiKey,
          appId: appId,
          messagingSenderId: senderId,
          projectId: projectId,
          iosBundleId: iosBundleId.isEmpty ? null : iosBundleId,
        ),
      );
      final messaging = FirebaseMessaging.instance;
      await messaging.setAutoInitEnabled(false);
      await messaging.setForegroundNotificationPresentationOptions(
        alert: true,
        badge: true,
        sound: true,
      );
      return FirebaseBuyerPushDevice(messaging);
    } catch (_) {
      // Authentication and the server inbox remain available if setup fails.
      return null;
    }
  }

  @override
  Stream<String> get registrations => _messaging.onRegistered;

  @override
  Stream<void> get foregroundMessages =>
      FirebaseMessaging.onMessage.map((_) {});

  @override
  Stream<void> get openedMessages =>
      FirebaseMessaging.onMessageOpenedApp.map((_) {});

  @override
  Future<bool> register() async {
    final settings = await _messaging.requestPermission();
    if (settings.authorizationStatus != AuthorizationStatus.authorized &&
        settings.authorizationStatus != AuthorizationStatus.provisional) {
      return false;
    }
    if (defaultTargetPlatform == TargetPlatform.iOS &&
        await _messaging.getAPNSToken() == null) {
      return false;
    }
    await _messaging.register();
    return true;
  }

  @override
  Future<void> reset() async {
    await _messaging.unregister();
    await FirebaseInstallations.instance.delete();
  }

  @override
  Future<bool> wasOpenedFromNotification() async =>
      await _messaging.getInitialMessage() != null;
}
