package com.nexa.mobile.operations.feature.warehouse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Records a reasoned request only; this ViewModel never changes an allocation or stock quantity. */
class LotSubstitutionViewModel(
    private val gateway: LotSubstitutionGateway,
    private val metadataStore: LotSubstitutionMetadataStore,
    private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {
    private val mutableState = MutableStateFlow(LotSubstitutionUiState())
    val state: StateFlow<LotSubstitutionUiState> = mutableState.asStateFlow()

    private var authority: PickingAuthority? = null
    private var work: LotSubstitutionWork? = null
    private var generation = 0L
    private var lookupGeneration = 0L
    private var intent: LotSubstitutionIntent? = null
    private val metadataMutex = Mutex()
    private val commandMutex = Mutex()
    private var restoreJob: Job? = null
    private var lookupJob: Job? = null

    fun activate(currentAuthority: PickingAuthority, currentWork: LotSubstitutionWork) {
        generation++
        val activation = generation
        authority = currentAuthority
        work = currentWork
        intent = null
        restoreJob?.cancel()
        lookupJob?.cancel()
        mutableState.value = LotSubstitutionUiState(
            authorityEpoch = currentAuthority.authorityEpoch,
            work = currentWork,
            status = if (SUBSTITUTION_PERMISSION in currentAuthority.permissions) {
                LotSubstitutionCommandStatus.Editing
            } else {
                LotSubstitutionCommandStatus.PermissionDenied
            },
            alternativeLookup = if (currentAuthority.permissions.any(READ_PERMISSIONS::contains)) {
                LotSubstitutionLookupStatus.Loading
            } else {
                LotSubstitutionLookupStatus.PermissionDenied
            },
            metadataAvailable = false,
            notice = if (SUBSTITUTION_PERMISSION in currentAuthority.permissions) null
            else "No tienes permiso vigente para solicitar esta sustitución."
        )
        restoreJob = viewModelScope.launch { restoreIntent(currentAuthority, activation) }
        loadAlternatives()
    }

    fun deactivate() {
        generation++
        lookupGeneration++
        authority = null
        work = null
        intent = null
        restoreJob?.cancel()
        lookupJob?.cancel()
        restoreJob = null
        lookupJob = null
        mutableState.value = LotSubstitutionUiState()
    }

    fun loadAlternatives(page: Int = 0) {
        val currentAuthority = authority ?: return
        val currentWork = work ?: return
        if (page != 0) return
        lookupGeneration++
        val lookup = lookupGeneration
        val activation = generation
        lookupJob?.cancel()
        mutableState.update { it.copy(alternativeLookup = LotSubstitutionLookupStatus.Loading) }
        lookupJob = viewModelScope.launch {
            val result = try {
                gateway.alternatives(currentWork, currentAuthority)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                LotSubstitutionLookupResult.ServiceUnavailable
            }
            if (!isCurrent(currentAuthority, activation) || lookup != lookupGeneration) return@launch
            when (result) {
                is LotSubstitutionLookupResult.Alternatives -> {
                    val eligible = result.items.distinctBy { it.id }
                        .filter { it.isEligibleFor(currentWork) }
                    mutableState.update {
                        it.copy(
                            alternatives = eligible,
                            selectedAlternativeId = it.frozenIntent?.alternativeLotId
                                ?.takeIf { id -> eligible.any { alternative -> alternative.id == id } },
                            alternativeLookup = if (eligible.isEmpty()) LotSubstitutionLookupStatus.Empty
                            else LotSubstitutionLookupStatus.Ready,
                            hasMoreAlternatives = false,
                            notice = if (eligible.isEmpty()) "No hay lotes alternativos elegibles en la vista actual." else it.notice
                        )
                    }
                    refreshCanRequest()
                }

                LotSubstitutionLookupResult.NetworkUnavailable -> lookupFailure(LotSubstitutionLookupStatus.NetworkUnavailable)
                LotSubstitutionLookupResult.ServiceUnavailable -> lookupFailure(LotSubstitutionLookupStatus.ServiceUnavailable)
                LotSubstitutionLookupResult.PermissionDenied -> lookupFailure(LotSubstitutionLookupStatus.PermissionDenied)
                LotSubstitutionLookupResult.ContextInvalidated -> lookupFailure(LotSubstitutionLookupStatus.ContextInvalidated)
                LotSubstitutionLookupResult.SessionInvalidated -> lookupFailure(LotSubstitutionLookupStatus.SessionInvalidated)
            }
        }
    }

    fun selectAlternative(lotId: String) {
        val current = mutableState.value
        if (intent != null || current.status != LotSubstitutionCommandStatus.Editing) return
        if (current.alternatives.none { it.id == lotId }) return
        mutableState.update { it.copy(selectedAlternativeId = lotId, notice = null) }
        refreshCanRequest()
    }

    fun updateReason(text: String) {
        if (intent != null || mutableState.value.status != LotSubstitutionCommandStatus.Editing) return
        mutableState.update { it.copy(reasonText = text.take(MAX_REASON_LENGTH), notice = null) }
        refreshCanRequest()
    }

    /** Saves the exact body, key and versions before the only network dispatch. */
    fun requestSubstitution() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        val currentWork = work ?: return
        val alternative = current.selectedAlternative ?: return
        if (!current.canRequest || current.status != LotSubstitutionCommandStatus.Editing || intent != null) return
        val key = newIdempotencyKey().takeIf { it.isNotBlank() && it.length <= MAX_IDEMPOTENCY_KEY_LENGTH }
            ?: run {
                mutableState.update { it.copy(notice = "No se pudo preparar la solicitud. Intenta nuevamente.") }
                return
            }
        val quantity = currentWork.preparedQuantityText.toBigDecimalOrNull() ?: return
        val frozen = LotSubstitutionIntent(
            scope = currentAuthority.scope,
            idempotencyKey = key,
            work = currentWork,
            alternativeLotId = alternative.id,
            reason = current.reasonText,
            frozenBody = substitutionBody(currentWork, alternative.id, quantity, current.reasonText),
            status = LotSubstitutionIntentStatus.Pending
        )
        intent = frozen
        mutableState.update {
            it.copy(status = LotSubstitutionCommandStatus.PersistingIntent, frozenIntent = frozen, notice = null)
        }
        val activation = generation
        viewModelScope.launch {
            val stored = withMetadataLock(currentAuthority, activation) {
                metadataStore.freeze(frozen)
            } ?: LotSubstitutionMetadataWrite.Unavailable
            if (!isCurrent(currentAuthority, activation)) return@launch
            if (stored != LotSubstitutionMetadataWrite.Saved) {
                intent = null
                mutableState.update {
                    it.copy(
                        metadataAvailable = false,
                        status = LotSubstitutionCommandStatus.Editing,
                        frozenIntent = null,
                        canRequest = false,
                        notice = "No se pudo guardar la solicitud protegida; no se envió al servidor."
                    )
                }
                return@launch
            }
            mutableState.update { it.copy(status = LotSubstitutionCommandStatus.Pending) }
            dispatch(frozen, currentAuthority, activation)
        }
    }

    /** Explicit user action. Restored/ambiguous requests are never sent automatically. */
    fun retryUnknownOutcome() {
        val currentAuthority = authority ?: return
        val frozen = intent ?: return
        val current = mutableState.value
        if (frozen.scope != currentAuthority.scope || frozen.work != work ||
            current.status != LotSubstitutionCommandStatus.UnknownOutcome ||
            SUBSTITUTION_PERMISSION !in currentAuthority.permissions
        ) return
        val activation = generation
        mutableState.update { it.copy(status = LotSubstitutionCommandStatus.Pending, notice = null) }
        viewModelScope.launch {
            dispatch(frozen.copy(status = LotSubstitutionIntentStatus.UnknownOutcome), currentAuthority, activation)
        }
    }

    /** Leaves the originally selected allocation untouched and asks the operator to reload Picking. */
    fun refreshCurrentAllocation() {
        val currentAuthority = authority ?: return
        val currentWork = work ?: return
        val current = mutableState.value
        if (current.status !in setOf(
                LotSubstitutionCommandStatus.Stale,
                LotSubstitutionCommandStatus.Rejected,
                LotSubstitutionCommandStatus.Conflict
            )
        ) return
        val activation = generation
        viewModelScope.launch {
            val result = try {
                gateway.currentAllocation(currentWork, currentAuthority)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                LotSubstitutionCurrentResult.ServiceUnavailable
            }
            if (!isCurrent(currentAuthority, activation)) return@launch
            when (result) {
                is LotSubstitutionCurrentResult.Current -> mutableState.update {
                    it.copy(
                        currentAllocation = result.facts,
                        notice = if (result.facts.expectedLotId == currentWork.expectedLotId &&
                            result.facts.version == currentWork.allocationVersion
                        ) {
                            "La asignación vigente sigue en la versión ${result.facts.version}; la propuesta no cambió el lote original."
                        } else {
                            "La asignación vigente es versión ${result.facts.version} con lote ${result.facts.expectedLotId ?: "sin línea"}. La propuesta obsoleta no modificó la asignación."
                        }
                    )
                }
                LotSubstitutionCurrentResult.NotFound -> mutableState.update {
                    it.copy(notice = "No se encontró una asignación física vigente para este trabajo.")
                }
                LotSubstitutionCurrentResult.NetworkUnavailable -> mutableState.update {
                    it.copy(notice = "No hay conexión para consultar la asignación vigente.")
                }
                LotSubstitutionCurrentResult.PermissionDenied -> mutableState.update {
                    it.copy(notice = "No tienes permiso vigente para consultar la asignación.")
                }
                LotSubstitutionCurrentResult.ContextInvalidated -> mutableState.update {
                    it.copy(notice = "El contexto cambió. Confirma nuevamente la autoridad activa.")
                }
                LotSubstitutionCurrentResult.SessionInvalidated -> mutableState.update {
                    it.copy(notice = "La sesión cambió. Confirma nuevamente la sesión activa.")
                }
                LotSubstitutionCurrentResult.ServiceUnavailable -> mutableState.update {
                    it.copy(notice = "No fue posible consultar la asignación vigente.")
                }
            }
        }
    }

    private suspend fun restoreIntent(currentAuthority: PickingAuthority, activation: Long) {
        val read = withMetadataLock(currentAuthority, activation) { metadataStore.load(currentAuthority.scope) }
            ?: LotSubstitutionMetadataRead.Unavailable
        if (!isCurrent(currentAuthority, activation)) return
        when (read) {
            LotSubstitutionMetadataRead.Unavailable -> {
                mutableState.update {
                    it.copy(
                        metadataAvailable = false,
                        status = LotSubstitutionCommandStatus.Editing,
                        notice = "El almacenamiento protegido no está disponible."
                    )
                }
            }

            is LotSubstitutionMetadataRead.Available -> {
                val stored = read.value
                if (stored == null) {
                    mutableState.update { it.copy(metadataAvailable = true) }
                    refreshCanRequest()
                    return
                }
                if (stored.scope != currentAuthority.scope || !stored.isValid()) {
                    mutableState.update {
                        it.copy(metadataAvailable = false, notice = "La solicitud guardada no puede recuperarse de forma segura.")
                    }
                    return
                }
                val recovered = stored.copy(status = LotSubstitutionIntentStatus.UnknownOutcome)
                intent = recovered
                work = stored.work
                mutableState.update {
                    it.copy(
                        work = stored.work,
                        selectedAlternativeId = stored.alternativeLotId,
                        reasonText = stored.reason,
                        metadataAvailable = true,
                        status = LotSubstitutionCommandStatus.UnknownOutcome,
                        frozenIntent = recovered,
                        notice = "Resultado incierto. Reintenta manualmente la misma solicitud para recuperar su resultado."
                    )
                }
                if (stored.status == LotSubstitutionIntentStatus.Pending) {
                    val marked = withMetadataLock(currentAuthority, activation) {
                        metadataStore.markUnknown(currentAuthority.scope, stored.idempotencyKey)
                    } ?: LotSubstitutionMetadataWrite.Unavailable
                    if (!isCurrent(currentAuthority, activation)) return
                    if (marked != LotSubstitutionMetadataWrite.Saved) {
                        mutableState.update {
                            it.copy(metadataAvailable = false, notice = "No se pudo actualizar el estado protegido de la solicitud.")
                        }
                    }
                }
                loadAlternatives()
            }
        }
    }

    private suspend fun dispatch(
        frozen: LotSubstitutionIntent,
        currentAuthority: PickingAuthority,
        activation: Long
    ) {
        commandMutex.withLock {
            if (!isCurrent(currentAuthority, activation)) return
            val result = try {
                gateway.request(frozen, currentAuthority)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                LotSubstitutionResult.UnknownOutcome
            }
            if (!isCurrent(currentAuthority, activation)) return
            when (result) {
                is LotSubstitutionResult.Requested -> {
                    if (!result.request.matches(frozen)) {
                        markUnknown(frozen, currentAuthority, activation)
                        return
                    }
                    finishKnown(frozen, currentAuthority, activation)
                    mutableState.update {
                        it.copy(
                            status = LotSubstitutionCommandStatus.Requested,
                            request = result.request,
                            frozenIntent = null,
                            canRequest = false,
                            notice = "Solicitud registrada por el servidor. La asignación original sigue vigente hasta una decisión autorizada."
                        )
                    }
                    intent = null
                }

                is LotSubstitutionResult.Rejected -> {
                    finishKnown(frozen, currentAuthority, activation)
                    mutableState.update {
                        it.copy(
                            status = LotSubstitutionCommandStatus.Rejected,
                            frozenIntent = null,
                            canRequest = false,
                            notice = result.code?.let(::safeRejectionMessage)
                                ?: "El servidor rechazó la solicitud; la asignación original permanece vigente."
                        )
                    }
                    intent = null
                }

                is LotSubstitutionResult.Stale -> {
                    finishKnown(frozen, currentAuthority, activation)
                    mutableState.update {
                        it.copy(
                            status = LotSubstitutionCommandStatus.Stale,
                            frozenIntent = null,
                            canRequest = false,
                            notice = result.currentAllocationVersion?.let {
                                "La asignación cambió a la versión $it. Actualiza Picking; no se cambió el lote original."
                            } ?: "La asignación cambió. Actualiza Picking; no se cambió el lote original."
                        )
                    }
                    intent = null
                }

                LotSubstitutionResult.Conflict -> {
                    finishKnown(frozen, currentAuthority, activation)
                    mutableState.update {
                        it.copy(
                            status = LotSubstitutionCommandStatus.Conflict,
                            frozenIntent = null,
                            canRequest = false,
                            notice = "La solicitud entra en conflicto con el estado vigente. Actualiza Picking antes de continuar."
                        )
                    }
                    intent = null
                }

                LotSubstitutionResult.PermissionDenied -> knownFailure(
                    frozen, currentAuthority, activation, LotSubstitutionCommandStatus.PermissionDenied
                )
                LotSubstitutionResult.ContextInvalidated -> knownFailure(
                    frozen, currentAuthority, activation, LotSubstitutionCommandStatus.ContextInvalidated
                )
                LotSubstitutionResult.SessionInvalidated -> knownFailure(
                    frozen, currentAuthority, activation, LotSubstitutionCommandStatus.SessionInvalidated
                )
                LotSubstitutionResult.ServiceUnavailable,
                LotSubstitutionResult.NetworkUnavailable,
                LotSubstitutionResult.UnknownOutcome -> markUnknown(frozen, currentAuthority, activation)
            }
        }
    }

    private suspend fun knownFailure(
        frozen: LotSubstitutionIntent,
        currentAuthority: PickingAuthority,
        activation: Long,
        status: LotSubstitutionCommandStatus
    ) {
        finishKnown(frozen, currentAuthority, activation)
        val notice = when (status) {
            LotSubstitutionCommandStatus.PermissionDenied -> "No tienes permiso vigente para solicitar esta sustitución."
            LotSubstitutionCommandStatus.ContextInvalidated -> "El contexto cambió. Confirma nuevamente la autoridad activa."
            else -> "La sesión cambió. Confirma nuevamente la sesión activa."
        }
        mutableState.update { it.copy(status = status, frozenIntent = null, canRequest = false, notice = notice) }
        intent = null
    }

    private suspend fun finishKnown(
        frozen: LotSubstitutionIntent,
        currentAuthority: PickingAuthority,
        activation: Long
    ) {
        val cleared = withMetadataLock(currentAuthority, activation) {
            metadataStore.clear(currentAuthority.scope, frozen.idempotencyKey)
        } ?: LotSubstitutionMetadataWrite.Unavailable
        if (!isCurrent(currentAuthority, activation)) return
        if (cleared != LotSubstitutionMetadataWrite.Saved) {
            mutableState.update {
                it.copy(
                    metadataAvailable = false,
                    notice = "El resultado llegó del servidor, pero la copia protegida no se pudo limpiar."
                )
            }
        }
    }

    private suspend fun markUnknown(
        frozen: LotSubstitutionIntent,
        currentAuthority: PickingAuthority,
        activation: Long
    ) {
        val marked = withMetadataLock(currentAuthority, activation) {
            metadataStore.markUnknown(currentAuthority.scope, frozen.idempotencyKey)
        } ?: LotSubstitutionMetadataWrite.Unavailable
        if (!isCurrent(currentAuthority, activation)) return
        intent = frozen.copy(status = LotSubstitutionIntentStatus.UnknownOutcome)
        mutableState.update {
            it.copy(
                status = LotSubstitutionCommandStatus.UnknownOutcome,
                frozenIntent = intent,
                canRequest = false,
                notice = if (marked == LotSubstitutionMetadataWrite.Saved) {
                    "Resultado incierto. Usa el reintento manual para recuperar esta misma solicitud."
                } else {
                    "Resultado incierto y estado protegido no actualizado. No inicies una solicitud distinta."
                }
            )
        }
    }

    private suspend fun <T> withMetadataLock(
        currentAuthority: PickingAuthority,
        activation: Long,
        block: suspend () -> T
    ): T? = metadataMutex.withLock {
        if (!isCurrent(currentAuthority, activation)) return@withLock null
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun lookupFailure(status: LotSubstitutionLookupStatus) {
        mutableState.update {
            it.copy(
                alternativeLookup = status,
                alternatives = emptyList(),
                selectedAlternativeId = null,
                hasMoreAlternatives = false,
                canRequest = false,
                notice = when (status) {
                    LotSubstitutionLookupStatus.NetworkUnavailable -> "No hay conexión para cargar lotes alternativos."
                    LotSubstitutionLookupStatus.PermissionDenied -> "No tienes permiso vigente para consultar lotes."
                    LotSubstitutionLookupStatus.ContextInvalidated -> "El contexto cambió; vuelve a confirmar la selección activa."
                    LotSubstitutionLookupStatus.SessionInvalidated -> "La sesión cambió; vuelve a confirmar la sesión activa."
                    else -> "No fue posible cargar los lotes alternativos vigentes."
                }
            )
        }
    }

    private fun refreshCanRequest() {
        val currentAuthority = authority
        val current = mutableState.value
        val allowed = currentAuthority != null && SUBSTITUTION_PERMISSION in currentAuthority.permissions
        val canRequest = allowed && current.metadataAvailable && current.work.isUsable() &&
            current.selectedAlternative.isEligibleFor(current.work) &&
            current.reasonText.isValidSubstitutionReason() && current.frozenIntent == null &&
            current.status == LotSubstitutionCommandStatus.Editing &&
            current.alternativeLookup == LotSubstitutionLookupStatus.Ready
        mutableState.update { it.copy(canRequest = canRequest) }
    }

    private fun isCurrent(currentAuthority: PickingAuthority, activation: Long): Boolean =
        generation == activation && authority == currentAuthority

    private fun LotSubstitutionIntent.isValid(): Boolean {
        val quantity = work.preparedQuantityText.toBigDecimalOrNull() ?: return false
        return scope.userId.isNotBlank() && scope.tenantId.isNotBlank() && scope.workspaceId.isNotBlank() &&
            scope.membershipId.isNotBlank() && work.isUsable() && idempotencyKey.isNotBlank() &&
            idempotencyKey.length <= MAX_IDEMPOTENCY_KEY_LENGTH && UUID_TEXT.matches(alternativeLotId) &&
            reason.isValidSubstitutionReason() &&
            frozenBody == substitutionBody(work, alternativeLotId, quantity, reason)
    }

    private fun LotSubstitutionRequest.matches(frozen: LotSubstitutionIntent): Boolean =
        UUID_TEXT.matches(id) && expectedLotId.equals(frozen.work.expectedLotId, ignoreCase = true) &&
            alternativeLotId.equals(frozen.alternativeLotId, ignoreCase = true) &&
            quantityText.toBigDecimalOrNull()?.compareTo(frozen.work.preparedQuantityText.toBigDecimal()) == 0 &&
            reason == frozen.reason && status == "REQUESTED" &&
            currentAllocationVersion == frozen.work.allocationVersion

    private fun safeRejectionMessage(code: String): String = when (code) {
        "ALTERNATIVE_NOT_ELIGIBLE", "INSUFFICIENT_ALTERNATIVE_QUANTITY" ->
            "El lote alternativo no cumple la política o disponibilidad vigente. La asignación original permanece."
        "EXPECTED_LOT_MISMATCH", "PHYSICAL_ALLOCATION_LINE_NOT_FOUND" ->
            "La línea de asignación cambió. Actualiza Picking antes de continuar."
        else -> "El servidor rechazó la solicitud; la asignación original permanece vigente."
    }

    private companion object {
        val READ_PERMISSIONS = setOf("warehouse:read", "warehouse.read", "inventory.read")
        const val SUBSTITUTION_PERMISSION = "inventory.adjust"
        const val MAX_REASON_LENGTH = 2_000
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 160
        val UUID_TEXT = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        fun substitutionBody(
            work: LotSubstitutionWork,
            alternativeLotId: String,
            quantity: BigDecimal,
            reason: String
        ): String = """{"fulfillmentId":"${work.fulfillmentId}","allocationId":"${work.allocationId}","physicalAllocationLineId":"${work.allocationLineId}","expectedLotId":"${work.expectedLotId}","alternativeLotId":"$alternativeLotId","quantity":${quantity.toPlainString()},"unit":${reasonUnit(work.unit)},"reason":${reason.jsonString()}}"""

        private fun reasonUnit(unit: String): String = unit.jsonString()

        private fun String.jsonString(): String = buildString {
            append('"')
            for (char in this@jsonString) {
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
                }
            }
            append('"')
        }
    }
}
