package com.fenixhub.mobile.util

import kotlin.math.min

object TxtRecordCodec {
    private const val CHUNK_SIZE = 240

    fun encode(json: String): Map<String, String> {
        if (json.isEmpty()) return mapOf("data0" to "")

        val result = linkedMapOf<String, String>()
        val builder = StringBuilder()
        var chunkIndex = 0

        for (char in json) {
            val candidate = builder.toString() + char
            if (candidate.toByteArray(Charsets.UTF_8).size > CHUNK_SIZE && builder.isNotEmpty()) {
                result["data$chunkIndex"] = builder.toString()
                builder.clear()
                chunkIndex += 1
            }
            builder.append(char)
        }

        if (builder.isNotEmpty()) {
            result["data$chunkIndex"] = builder.toString()
        }

        return result
    }

    fun decode(attributes: Map<String, ByteArray>): String? {
        val payload = attributes
            .filterKeys { it.startsWith("data") }
            .toSortedMap(compareBy { key ->
                key.removePrefix("data").toIntOrNull() ?: Int.MAX_VALUE
            })
            .values
            .fold(ByteArray(0)) { acc, chunk -> acc + chunk }

        if (payload.isEmpty()) return null
        return payload.toString(Charsets.UTF_8)
    }
}
