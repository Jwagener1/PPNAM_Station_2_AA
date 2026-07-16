package com.ppnam.station2aa.data.mqtt

/**
 * The one place the wire schema version is defined. Contract v3.0 rejects any request whose
 * schemaVersion is not exactly "3.0" with errorCode `unsupported_schema`.
 */
object MqttSchema {
    const val VERSION = "3.0"
}
