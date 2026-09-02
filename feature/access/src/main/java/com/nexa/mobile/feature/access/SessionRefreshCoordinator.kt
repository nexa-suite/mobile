package com.nexa.mobile.feature.access

import com.nexa.mobile.core.network.ApiError
import com.nexa.mobile.core.network.ApiErrorCategory
import com.nexa.mobile.core.network.ApiResult
import com.nexa.mobile.core.storage.SessionMaterial
import com.nexa.mobile.core.storage.SessionStore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RetrySafety {
    UNSAFE,
    SAFE,
}

/** Coordinates authenticated calls while keeping session rotation single-flight. */
class SessionRefreshCoordinator(
    private val sessionStore: SessionStore,
    private val refresh: suspend (SessionMaterial) -> ApiResult<SessionMaterial>,
) {
    private val refreshMutex = Mutex()

    suspend fun <T> execute(
        retrySafety: RetrySafety = RetrySafety.UNSAFE,
        operation: suspend (accessToken: String) -> ApiResult<T>,
    ): ApiResult<T> {
        val session = when (val stored = readSession()) {
            is ApiResult.Success -> stored.value
                ?: return failure(missingSessionError())

            is ApiResult.Failure -> return failure(stored.error)
        }

        val initial = operation(session.accessToken)
        if (retrySafety != RetrySafety.SAFE || !isUnauthorized(initial)) {
            return initial
        }

        return when (val current = refreshSingleFlight(session)) {
            is ApiResult.Success -> when (val retried = operation(current.value.accessToken)) {
                is ApiResult.Failure -> if (isUnauthorized(retried)) {
                    clearAndReturn(retried)
                } else {
                    retried
                }
                is ApiResult.Success -> retried
            }
            is ApiResult.Failure -> failure(current.error)
        }
    }

    private suspend fun readSession(): ApiResult<SessionMaterial?> = try {
        ApiResult.Success(sessionStore.read())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        clearAndReturn(storageFailure())
    }

    private suspend fun refreshSingleFlight(failedSession: SessionMaterial): ApiResult<SessionMaterial> =
        refreshMutex.withLock {
            val current = try {
                sessionStore.read()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return@withLock clearAndReturn(storageFailure())
            }

            when {
                current == null -> missingSessionFailure()
                current != failedSession -> ApiResult.Success(current)
                else -> refreshAndPersist(current)
            }
        }

    private suspend fun refreshAndPersist(session: SessionMaterial): ApiResult<SessionMaterial> {
        val rotated = try {
            refresh(session)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            refreshFailure()
        }

        return when (rotated) {
            is ApiResult.Failure -> clearAndReturn(rotated)
            is ApiResult.Success -> try {
                sessionStore.write(rotated.value)
                ApiResult.Success(rotated.value)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                clearAndReturn(storageFailure())
            }
        }
    }

    private suspend fun clearAndReturn(failure: ApiResult.Failure): ApiResult.Failure = try {
        sessionStore.clear()
        failure
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        storageFailure()
    }

    private fun <T> failure(error: ApiError): ApiResult<T> = ApiResult.Failure(error)

    private fun <T> isUnauthorized(result: ApiResult<T>): Boolean =
        result is ApiResult.Failure && (
            result.error.status == HTTP_UNAUTHORIZED ||
                result.error.category == ApiErrorCategory.UNAUTHORIZED
            )

    private fun missingSessionFailure(): ApiResult<SessionMaterial> =
        ApiResult.Failure(missingSessionError())

    private fun missingSessionError() = ApiError(
        category = ApiErrorCategory.UNAUTHORIZED,
        status = HTTP_UNAUTHORIZED,
        code = "SESSION_MISSING",
    )

    private fun refreshFailure(): ApiResult.Failure = ApiResult.Failure(
        ApiError(
            category = ApiErrorCategory.UNKNOWN,
            code = "SESSION_REFRESH_FAILURE",
        ),
    )

    private fun storageFailure(): ApiResult.Failure = ApiResult.Failure(
        ApiError(
            category = ApiErrorCategory.UNKNOWN,
            code = "SESSION_STORAGE_FAILURE",
        ),
    )

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
    }
}
