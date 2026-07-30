package com.streamlink.app.core.decision

/**
 * @deprecated Renamed to [PredictiveStreamingDecisionEngine] to avoid class name collision.
 */
@Deprecated(
    message = "Use PredictiveStreamingDecisionEngine instead",
    replaceWith = ReplaceWith("PredictiveStreamingDecisionEngine", "com.streamlink.app.core.decision.PredictiveStreamingDecisionEngine")
)
typealias DecisionEngine = PredictiveStreamingDecisionEngine
