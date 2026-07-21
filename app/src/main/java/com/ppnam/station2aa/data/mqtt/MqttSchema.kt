package com.ppnam.station2aa.data.mqtt

/**
 * The one place the wire schema version is defined. Contract v4.0 rejects any request whose
 * schemaVersion is not exactly "4.0" with errorCode `unsupported_schema` (schema 3.0 survives
 * server-side only for capture actions during cutover — this app never sends it).
 */
object MqttSchema {
    const val VERSION = "4.0"
}
