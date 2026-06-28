package com.streamlink.shared.domain

sealed class StreamResult<out T> {
    data class Success<out T>(val data: T) : StreamResult<T>()
    
    sealed class Failure(val errorMessage: String, val cause: Throwable? = null) : StreamResult<Nothing>() {
        class NetworkError(msg: String, cause: Throwable? = null) : Failure(msg, cause)
        class HardwareCodecError(msg: String, cause: Throwable? = null) : Failure(msg, cause)
        class SecurityPanic(msg: String, cause: Throwable? = null) : Failure(msg, cause)
        class ThermalThrottling(msg: String) : Failure(msg)
    }

    // Functional method for direct mapping without boilerplate
    inline fun <R> fold(
        onSuccess: (T) -> R,
        onFailure: (Failure) -> R
    ): R = when (this) {
        is Success -> onSuccess(data)
        is Failure -> onFailure(this)
    }
}
