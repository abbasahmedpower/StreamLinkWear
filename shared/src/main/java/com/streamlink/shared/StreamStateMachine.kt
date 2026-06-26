package com.streamlink.shared

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * StreamStateMachine — replaces raw enum transitions with a proper guarded FSM.
 *
 * Improvements over GlobalStreamState:
 * - Entry/exit actions per state
 * - Event-driven (not just direct transitions)
 * - Transition history with timestamps
 * - Listener callbacks for subsystems
 */
class StreamStateMachine {

    sealed class Event {
        object WatchFound       : Event()
        object StartCapture     : Event()
        object FirstFrameSent   : Event()
        object NetworkDegraded  : Event()
        object NetworkRecovered : Event()
        object RecoveryStarted  : Event()
        object RecoverySuccess  : Event()
        object RecoveryFailed   : Event()
        object Stop             : Event()
        object Error            : Event()
        object Reset            : Event()
        data class ThermalWarning(val level: Int) : Event()
    }

    data class StateEntry(
        val state: GlobalStreamState.State,
        val enteredAtMs: Long = System.currentTimeMillis()
    )

    private val _state = MutableStateFlow(GlobalStreamState.State.IDLE)
    val state: StateFlow<GlobalStreamState.State> = _state

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events

    private val mutex = Mutex()
    private val history = ArrayDeque<StateEntry>(30)
    private val tag = "StreamFSM"

    var onEnterState: (GlobalStreamState.State) -> Unit = {}
    var onExitState:  (GlobalStreamState.State) -> Unit = {}
    var onInvalidEvent: (GlobalStreamState.State, Event) -> Unit = { s, e ->
        Log.w(tag, "Invalid event $e in state $s")
    }

    suspend fun send(event: Event) = mutex.withLock {
        val current = _state.value
        val next = transition(current, event)

        if (next == null) {
            onInvalidEvent(current, event)
            return@withLock
        }
        if (next == current) return@withLock

        Log.i(tag, "FSM: $current --[${event::class.simpleName}]--> $next")
        onExitState(current)

        if (history.size >= 30) history.removeFirst()
        history.addLast(StateEntry(next))

        _state.value = next
        onEnterState(next)
        _events.emit(event)
    }

    private fun transition(
        state: GlobalStreamState.State,
        event: Event
    ): GlobalStreamState.State? = when (state) {

        GlobalStreamState.State.IDLE -> when (event) {
            is Event.WatchFound    -> GlobalStreamState.State.CONNECTING
            is Event.Reset         -> GlobalStreamState.State.IDLE
            else                   -> null
        }

        GlobalStreamState.State.CONNECTING -> when (event) {
            is Event.StartCapture  -> GlobalStreamState.State.STREAM_STARTING
            is Event.Error         -> GlobalStreamState.State.FAILED
            is Event.Stop          -> GlobalStreamState.State.STOPPED
            else                   -> null
        }

        GlobalStreamState.State.STREAM_STARTING -> when (event) {
            is Event.FirstFrameSent   -> GlobalStreamState.State.STREAMING
            is Event.ThermalWarning   -> GlobalStreamState.State.DEGRADED   // ✅ Fixed
            is Event.Error            -> GlobalStreamState.State.FAILED
            is Event.Stop             -> GlobalStreamState.State.STOPPED
            else                      -> null
        }

        GlobalStreamState.State.STREAMING -> when (event) {
            is Event.NetworkDegraded  -> GlobalStreamState.State.DEGRADED
            is Event.RecoveryStarted  -> GlobalStreamState.State.RECOVERING
            is Event.ThermalWarning   -> GlobalStreamState.State.DEGRADED
            is Event.StartCapture     -> GlobalStreamState.State.STREAM_STARTING  // ✅ Codec reset
            is Event.Stop             -> GlobalStreamState.State.STOPPED
            is Event.Error            -> GlobalStreamState.State.RECOVERING
            else                      -> null
        }

        GlobalStreamState.State.DEGRADED -> when (event) {
            is Event.NetworkRecovered -> GlobalStreamState.State.STREAMING
            is Event.RecoveryStarted  -> GlobalStreamState.State.RECOVERING
            is Event.Error            -> GlobalStreamState.State.FAILED    // ✅ Fixed
            is Event.Stop             -> GlobalStreamState.State.STOPPED
            else                      -> null
        }

        GlobalStreamState.State.RECOVERING -> when (event) {
            is Event.RecoverySuccess  -> GlobalStreamState.State.STREAMING
            is Event.RecoveryFailed   -> GlobalStreamState.State.FAILED
            is Event.Stop             -> GlobalStreamState.State.STOPPED
            else                      -> null
        }

        GlobalStreamState.State.STOPPED,
        GlobalStreamState.State.FAILED -> when (event) {
            is Event.Reset            -> GlobalStreamState.State.IDLE
            else                      -> null
        }

        GlobalStreamState.State.PRELOADING -> when (event) {
            is Event.WatchFound       -> GlobalStreamState.State.CONNECTING
            is Event.Stop             -> GlobalStreamState.State.IDLE
            else                      -> null
        }
    }

    fun historyLog(): String = history.joinToString(" → ") { it.state.name }

    suspend fun reset() {
        mutex.withLock {
            _state.value = GlobalStreamState.State.IDLE
            history.clear()
        }
    }
}
