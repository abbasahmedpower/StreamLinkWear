package com.streamlink.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object GlobalStreamState {
    enum class State {
        IDLE, PRELOADING, CONNECTING, STREAM_STARTING,
        STREAMING, DEGRADED, RECOVERING, STOPPED, FAILED
    }



    data class Snapshot(
        val state: State = State.IDLE,
        val bitrateKbps: Int = 0,
        val fps: Int = StreamProtocol.WEAR_FPS_FULL,
        val latencyMs: Long = 0L,
        val thermalLevel: Int = 0,
        val mode: String = StreamProtocol.MODE_MIRROR,
        val errorMessage: String = "",
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
        _snapshot.value = current.update().copy(state = newState)
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
        State.STREAM_STARTING -> StreamStateMachine.Event.StartCapture
        State.STREAMING      -> StreamStateMachine.Event.FirstFrameSent
        State.DEGRADED       -> StreamStateMachine.Event.NetworkDegraded
        State.RECOVERING     -> StreamStateMachine.Event.RecoveryStarted
        State.STOPPED        -> StreamStateMachine.Event.Stop
        State.FAILED         -> StreamStateMachine.Event.Error
        State.IDLE           -> StreamStateMachine.Event.Reset
        else                 -> null
    }

    private fun isValidTransition(from: State, to: State): Boolean {
        if (to == State.IDLE || to == State.STOPPED || to == State.FAILED) return true
        return when (from) {
            State.IDLE -> to in setOf(State.PRELOADING, State.CONNECTING)
            State.PRELOADING -> to in setOf(State.CONNECTING, State.IDLE)
            State.CONNECTING -> to in setOf(State.STREAM_STARTING, State.FAILED, State.IDLE)
            State.STREAM_STARTING -> to in setOf(State.STREAMING, State.FAILED)
            State.STREAMING -> to in setOf(State.DEGRADED, State.RECOVERING, State.STOPPED)
            State.DEGRADED -> to in setOf(State.STREAMING, State.RECOVERING, State.STOPPED)
            State.RECOVERING -> to in setOf(State.STREAMING, State.FAILED, State.STOPPED)
            State.STOPPED -> to == State.IDLE
            State.FAILED -> to == State.IDLE
        }
    }
}
