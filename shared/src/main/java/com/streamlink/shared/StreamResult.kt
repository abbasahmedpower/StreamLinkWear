package com.streamlink.shared

/**
 * Deterministic error handling result type, replacing scattered try-catch.
 * Ensures all failure scenarios are explicitly handled.
 */
sealed class StreamResult<out T> {
    data class Success<out T>(val data: T) : StreamResult<T>()
    
    sealed class Failure : StreamResult<Nothing>() {
        object NetworkTimeout : Failure()
        object HardwareEncoderPanic : Failure()
        object ThermalThrottlingLimit : Failure()
        data class Unknown(val exception: Throwable) : Failure()
    }
    
    inline fun <R> map(transform: (@UnsafeVariance T) -> R): StreamResult<R> {
        return when (this) {
            is Success -> Success(transform(data))
            is Failure -> this
        }
    }

    inline fun getOrElse(onFailure: (Failure) -> @UnsafeVariance T): T {
        return when (this) {
            is Success -> data
            is Failure -> onFailure(this)
        }
    }
}
