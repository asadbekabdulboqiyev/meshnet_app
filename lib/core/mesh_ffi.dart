import 'dart:ffi';

/// MeshNet FFI — Dart bindings for native crypto bridge.
///
/// This is scaffolding for future migration from MethodChannel to FFI.
/// The actual crypto still runs in Kotlin/JVM via MethodChannel.
///
/// Usage:
/// ```dart
/// final bridge = MeshFFIBridge();
/// if (bridge.isAvailable) {
///   final sharedSecret = bridge.computeSharedSecret(privKey, pubKey);
/// }
/// ```
class MeshFFIBridge {
  // Assigned on load; read once the C bridge exposes real functions.
  // ignore: unused_field
  static DynamicLibrary? _lib;
  static bool _loaded = false;

  /// Whether the native library is available.
  bool get isAvailable => _loaded;

  MeshFFIBridge() {
    if (!_loaded) {
      try {
        _lib = DynamicLibrary.open('libmesh_bridge.so');
        _loaded = true;
      } catch (_) {
        // Native library not available (e.g., on iOS or test environment)
        _loaded = false;
      }
    }
  }

  // Placeholder function pointers — will be populated when
  // the C bridge has real implementations.
  //
  // final _computeSharedSecret = _lib?.lookupFunction<...>(...);
  // final _encrypt = _lib?.lookupFunction<...>(...);
  // final _decrypt = _lib?.lookupFunction<...>(...);
}
