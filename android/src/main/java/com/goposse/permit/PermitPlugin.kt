/*
 * Permit
 * A Posse Production
 * http://goposse.com
 * Copyright (c) 2017 Posse Productions LLC. All rights reserved.
 * See LICENSE for distribution and usage details.
 */
package com.goposse.permit

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.util.Log
import com.goposse.permit.activities.PermissionActivity
import com.goposse.permit.common.*
import com.goposse.permit.types.PermitResult
import com.goposse.permit.types.PermitType
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import com.goposse.permit.common.PERMIT_NOT_USED

class PermitPlugin(val activity: Activity) : MethodCallHandler {

	val LOG_TAG = "PMT:Plugin"

	override fun onMethodCall(call: MethodCall, result: Result) {
		when(call.method) {
			"check", "request" -> {
				Log.d(LOG_TAG, "Method call was ${call.method}, invoking plugin")
				val permissions: List<Int>? = call.argument("permissions")
				if (permissions == null || permissions.isEmpty()) {
					Log.e(LOG_TAG, "No permissions were passed")
					result.error(PERMIT_ERR_INVALID_REQUEST, "No permissions were passed", null)
					return
				}
				if (call.method == "check") {
					checkPermissions(result = result, permissions = permissions)
				} else if (call.method == "request") {
					requestPermissions(result = result, permissions = permissions)
				}
			}
			else -> result.notImplemented()
		}
	}

	/**
	 * Check permissions for the provided list of permission types
	 * @param permissions the list of permissions to check
	 * @param result the Flutter channel result object to notify on completion
	 */
	private fun checkPermissions(permissions: List<Int>, result: Result) {
		val resultsMap = mutableMapOf<Int, Map<String, Any>>()
		for (permissionInt: Int in permissions) {
			val requestedPermission = PermitType.fromInt(permissionInt)?.permission()
			if (requestedPermission == null || requestedPermission == PERMIT_NOT_USED) {
				Log.w(LOG_TAG, "Permit: type ($permissionInt) not supported")
				resultsMap[permissionInt] = hashMapOf<String, Any>(
						"code" to PermitResult.unavailable.value,
						"requestCount" to 0
				)
				continue
			}
			Log.d(LOG_TAG, "Checking $requestedPermission")
			var permissionResult = PermitResult.fromResultCode(
					ContextCompat.checkSelfPermission(activity, requestedPermission)
			)

			if (permissionResult != PermitResult.granted) {
				if (ActivityCompat.shouldShowRequestPermissionRationale(activity, requestedPermission)) {
					Log.d(LOG_TAG, "$requestedPermission has been denied previously and will require a justification")
					permissionResult = PermitResult.needsRationale
				} else {
					val prefs = Prefs(context = activity)
					if (prefs.getPermissionRequestCount(requestedPermission) == 0) {
						Log.d(LOG_TAG, "$requestedPermission has been never been requested previously")
						permissionResult = PermitResult.unknown;
					}
				}
			}
			Log.d(LOG_TAG, "Permission for $requestedPermission ${permissionResult}")
			resultsMap[permissionInt] = hashMapOf<String, Any>(
					"code" to permissionResult.value,
					"requestCount" to 0
			)
		}
		Log.d(LOG_TAG, "All permissions checked, returning ${resultsMap.javaClass.name} with integer keys of permission types and integer values of permission results")
		result.success(resultsMap)
	}

	/**
	 * Request permissions for the provided list of permission types
	 * @param permissions the list of permissions to request
	 * @param result the Flutter channel result object to notify on completion
	 */
	private fun requestPermissions(permissions: List<Int>, result: Result) {
		val permissionsList: Array<String>? = permissions.mapNotNull {
			p -> PermitType.fromInt(p)?.permission()
		}.toTypedArray()
		if (permissionsList == null) {
			Log.w(LOG_TAG, "Permit: No valid permissions were requested")
			result.error(PERMIT_ERR_INVALID_REQUEST, "No permissions were passed", null)
			return
		}

		val resultReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
			override fun onReceiveResult(resultCode: Int, resultData: Bundle?)
					= completeReceiver(result, resultCode, resultData)
		}

		val intent = Intent(activity, PermissionActivity::class.java)
		intent.putExtra("permissions", permissionsList)
		intent.putExtra("resultReceiver", resultReceiver)
		val pendingIntent = PendingIntent.getActivity(activity, PERMIT_PERMISSION_REQUEST_RECEIVER_CODE,
				intent, PendingIntent.FLAG_ONE_SHOT)
		pendingIntent.send()
	}

	private fun completeReceiver(result: Result, resultCode: Int, resultData: Bundle?) {
		Log.d(LOG_TAG, "request resultData: $resultData")
		if (resultCode == PERMIT_RECEIVER_CODE_INVALID_REQUEST) {
			Log.w(LOG_TAG, "Permit: The request was invalid.  Please check the logs.")
			result.error(PERMIT_ERR_INVALID_REQUEST, "The request was invalid", null)
			return
		}
		val permissions = resultData?.getStringArray("permissions")
		val grantResults = resultData?.getIntArray("grantResults")
		if (permissions != null && grantResults != null && (permissions.size == grantResults.size)) {
			val resultsMap = mutableMapOf<Int, MutableMap<String, Int>>()
			val prefs = Prefs(activity)

			for (i in 0 until permissions.size) {
				val permission = permissions[i]
				val permissionPermitType = PermitType.fromString(permission)
				if (permissionPermitType == null) {
					Log.w(LOG_TAG, "An invalid permission ($permission) returned in receiver, ignoring.")
					continue
				}
				val permissionPermitValue = permissionPermitType.value
				val grantResult = grantResults[i]
				var permitGrantResult = PermitResult.denied
				if (grantResult == PackageManager.PERMISSION_GRANTED) {
					permitGrantResult = PermitResult.granted
				}
				// Increment the number of times we have requested this from the user
				val requestCount = prefs.incrementPermissionRequestCount(permission)
				Log.d(LOG_TAG, "Permission $permission requested $requestCount times")

				val map = mutableMapOf<String,Int>()
				map["code"] = permitGrantResult.value
				map["requestCount"] = requestCount
				resultsMap[permissionPermitValue] = map
			}
			Log.d(LOG_TAG, "Returning a map of permisions and request results")
			result.success(resultsMap)
			return
		} else {
			Log.w(LOG_TAG, "Permit: The result data was empty.")
			result.error(PERMIT_ERR_GENERAL, "The request did not return valid result data", null)
			return
		}
	}

	companion object {
		/**
		 * Plugin registration for Flutter
		 * @param registrar the Flutter plugin registry registrar object used for registering the plugin with Flutter
		 */
		@JvmStatic fun registerWith(registrar: Registrar) {
			val channel = MethodChannel(registrar.messenger(), "permit")
			channel.setMethodCallHandler(PermitPlugin(registrar.activity()))
		}
	}
}