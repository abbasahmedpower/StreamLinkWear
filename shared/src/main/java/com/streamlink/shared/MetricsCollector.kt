package com.streamlink.shared

import com.streamlink.shared.telemetry.MetricsCollector as TelemetryCollectorImpl

/**
 * @deprecated Legacy location for MetricsCollector.
 * Migrate usage to [com.streamlink.shared.telemetry.MetricsCollector] via [com.streamlink.shared.telemetry.StreamMetricsSource].
 */
@Deprecated(
    message = "Use com.streamlink.shared.telemetry.MetricsCollector via StreamMetricsSource instead",
    replaceWith = ReplaceWith("MetricsCollector", "com.streamlink.shared.telemetry.MetricsCollector")
)
typealias MetricsCollector = TelemetryCollectorImpl
