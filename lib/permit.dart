import 'dart:async';

import 'dart:collection';
import 'package:flutter/services.dart';

enum PermitType { camera, coarseLocation, fineLocation, whenInUseLocation, alwaysLocation, phone, push }
enum PermissionStatusCode { unknown, needsRationale, denied, granted, unavailable }

class PermissionStatus {
  PermissionStatus(PermissionStatusCode code, int requestCount)
      : _code = code,
        _requestCount = requestCount;

  PermissionStatusCode _code;
  PermissionStatusCode get code => _code;

  int _requestCount;
  int get requestCount => _requestCount;
}

class PermitResult {
  static final errorCodeGeneral = 500;

  // generic error result
  static PermitResult error =
      new PermitResult(null, errorCode: errorCodeGeneral, errorMessage: "Permissions Check Failed");

  // constructors
  PermitResult(this.statuses, {this.errorCode = 0, this.errorMessage});

  /// if there was an error returned while trying to check the permission status
  final int errorCode;

  /// if the platform plugin has provided an error message
  final String errorMessage;

  /// see: https://developer.android.com/training/permissions/requesting.html#perm-request
  /// NOTE: needsJustification will always be false on iOS as you can only request
  /// permissions once
  final Map<PermitType, PermissionStatus> statuses;

  // Used to check if the result was successful, or if there was an error
  bool success() => errorCode == 0;

  bool hasGrantedAny(List<PermitType> permissions, {bool grantedIfUnavailable = true}) {
    if (permissions == null || permissions.length == 0 || statuses == null || statuses.length == 0) {
      return false;
    }
    for (PermitType type in permissions) {
      PermissionStatusCode code = statuses[type].code;
      if (code == PermissionStatusCode.granted) {
        return true;
      } else if (grantedIfUnavailable && code == PermissionStatusCode.unavailable) {
        return true;
      }
    }
    return false;
  }

  PermissionStatus resultCodeForPermitType(PermitType permitType) {
    return statuses[permitType];
  }
}

class Permit {
  static const MethodChannel _channel = const MethodChannel('permit');

  // plugin functions
  static Future<PermitResult> checkPermissions(List<PermitType> permissions) async {
    return invokePermitChannelMethod(permissions, 'check');
  }

  static Future<PermitResult> requestPermissions(List<PermitType> permissions) async {
    return invokePermitChannelMethod(permissions, 'request');
  }

  static Future<PermitResult> invokePermitChannelMethod(List<PermitType> permissions, String method) async {
    var intPermissionsSet = new Set<int>.from(permissions.map((type) => type.index));

    try {
      // channelResults should be a LinkedHashMap with int keys and int values
      var channelResults = await _channel.invokeMethod(method, {
        'permissions': intPermissionsSet.toList(),
      });

      if (channelResults == null) {
        return new PermitResult(
          null,
          errorCode: PermitResult.errorCodeGeneral,
          errorMessage: "Check permissions failed: null channel results",
        );
      } else {
        return new PermitResult(_resultsFromMap(channelResults));
      }
    } on PlatformException catch (e) {
      print(e.message);
      return new PermitResult(
        null,
        errorCode: PermitResult.errorCodeGeneral,
        errorMessage: "Check permissions failed: ${e.message}",
      );
    }
  }

  static Map<PermitType, PermissionStatus> _resultsFromMap(Map<int, Map<String, dynamic>> resultsMap) {
    Map<PermitType, PermissionStatus> finalResults = new Map<PermitType, PermissionStatus>();
    resultsMap.forEach((permitTypeInt, permissionResult) {
      var status;
      if (permissionResult is int) {
        status = new PermissionStatus(
          PermissionStatusCode.values[permissionResult as int],
          0,
        );
      } else {
        status = new PermissionStatus(
          PermissionStatusCode.values[permissionResult["code"]],
          permissionResult["requestCount"],
        );
      }
      finalResults[_permitTypeFromInt(permitTypeInt)] = status;
    });
    return finalResults;
  }

  static PermitType _permitTypeFromInt(int permitTypeInt) {
    if (permitTypeInt >= 0 && permitTypeInt < PermitType.values.length) {
      return PermitType.values[permitTypeInt];
    }
    return null;
  }
}
