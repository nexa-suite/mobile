package com.nexa.mobile.operations.fulfillmentdelivery.presentation.testsupport

import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DeliveryRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery.JsonDeliveryRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch.JsonDispatchRequestBodyCodec

/** Exercise the production encoder while keeping infrastructure outside presentation runtime. */
class TestDispatchRequestBodyCodec : DispatchRequestBodyCodec by JsonDispatchRequestBodyCodec()

class TestDeliveryRequestBodyCodec : DeliveryRequestBodyCodec by JsonDeliveryRequestBodyCodec()
