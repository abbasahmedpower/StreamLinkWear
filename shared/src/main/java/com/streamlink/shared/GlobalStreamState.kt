package com.streamlink.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object GlobalStreamState {
    enum class State {
        IDLE, CONNECTING, AUTHENTICATING, STREAMING, RECONNECTING, FAILED
    }



    data class Snapshot(
        val state: State = State.IDLE,
        val bitrateKbps: Int = 0,
        val fps: Int = StreamProtocol.WEAR_FPS_FULL,
        val latencyMs: Long = 0L,
        val thermalLevel: Int = 0,
        val mode: String = StreamProtocol.MODE_MIRROR,
        val errorMessage: String = "",
        val isFatal: Boolean = false,
        val predictedAction: StreamAction = StreamAction.IDLE
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot
    val current: State get() = _snapshot.value.state

    private val mutex = Mutex()

    private val fsm = StreamStateMachine().also { machine ->
        machine.onEnterState = { state ->
            android.util.Log.i("GSS", "→ $state")
        }
    }

    suspend fun transition(
        newState: State,
        update: Snapshot.() -> Snapshot = { this }
    ) = mutex.withLock {
        val current = _snapshot.value
        if (!isValidTransition(current.state, newState)) return@withLock
        
        // Atomic teardown hook for terminal states
        if (newState == State.IDLE || newState == State.FAILED) {
            _snapshot.value = Snapshot(state = newState, isFatal = current.isFatal)
        } else {
            _snapshot.value = current.update().copy(state = newState)
        }
        fsm.send(stateToFsmEvent(newState) ?: return@withLock)
    }

    suspend fun update(block: Snapshot.() -> Snapshot) = mutex.withLock {
        _snapshot.value = _snapshot.value.block()
    }

    suspend fun resetSafe() = mutex.withLock {
        _snapshot.value = Snapshot()
        fsm.reset()
    }

    fun historyLog(): String = fsm.historyLog()

    private fun stateToFsmEvent(state: State): StreamStateMachine.Event? = when (state) {
        State.CONNECTING     -> StreamStateMachine.Event.WatchFound
        State.AUTHENTICATING -> StreamStateMachine.Event.StartCapture
        State.STREAMING      -> StreamStateMachine.Event.FirstFrameSent
        State.RECONNECTING   -> StreamStateMachine.Event.RecoveryStarted
        State.FAILED         -> StreamStateMachine.Event.Error
        State.IDLE           -> StreamStateMachine.Event.Reset
        else                 -> null
    }

    private fun isValidTransition(from: State, to: State): Boolean {
        if (to == State.IDLE || to == State.FAILED) return true
        return when (from) {
            State.IDLE -> to == State.CONNECTING
            State.CONNECTING -> to in setOf(State.AUTHENTICATING, State.FAILED, State.IDLE)
            State.AUTHENTICATING -> to in setOf(State.STREAMING, State.FAILED)
            State.STREAMING -> to in setOf(State.RECONNECTING, State.IDLE)
            State.RECONNECTING -> to in setOf(State.STREAMING, State.FAILED, State.IDLE)
            State.FAILED -> to == State.IDLE
        }
    }
}
