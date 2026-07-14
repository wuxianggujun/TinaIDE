package com.wuxianggujun.tinaide.plugin.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

internal fun hostSuccess(vararg values: JsonElement): PluginHostCallResponse =
    PluginHostCallResponse(JsonArray(values.toList()))

internal fun hostFailure(
    message: String,
    kind: PluginHostErrorKind = PluginHostErrorKind.BUSINESS_ERROR,
): PluginHostCallResponse = PluginHostCallResponse(
    values = JsonArray(listOf(JsonNull, JsonPrimitive(message))),
    errorKind = kind,
    error = message,
)

internal fun hostFailureWithValues(
    firstValue: JsonElement,
    message: String,
    kind: PluginHostErrorKind = PluginHostErrorKind.BUSINESS_ERROR,
): PluginHostCallResponse = PluginHostCallResponse(
    values = JsonArray(listOf(firstValue, JsonPrimitive(message))),
    errorKind = kind,
    error = message,
)

internal fun hostInvalidMethod(request: PluginHostCallRequest): PluginHostCallResponse = hostFailure(
    "Unknown plugin API method: ${request.namespace}.${request.method}",
    PluginHostErrorKind.INVALID_REQUEST,
)
