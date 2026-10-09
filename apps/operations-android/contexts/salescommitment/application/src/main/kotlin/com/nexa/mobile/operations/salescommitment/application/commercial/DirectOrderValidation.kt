package com.nexa.mobile.operations.salescommitment.application.commercial

import com.nexa.mobile.operations.salescommitment.domain.model.commercial.DirectOrderDraft
import java.time.LocalDate

fun DirectOrderDraft.isValidForSubmission(): Boolean =
    customerId.isNotBlank() && lines.isNotEmpty() && lines.size <= 100 &&
        lines.all {
            it.quantity.toBigDecimalOrNull()?.signum() == 1 && !it.unit.isNullOrBlank()
        } &&
        runCatching { LocalDate.parse(deliveryDate) }.isSuccess && deliveryProfile.isNotBlank()
