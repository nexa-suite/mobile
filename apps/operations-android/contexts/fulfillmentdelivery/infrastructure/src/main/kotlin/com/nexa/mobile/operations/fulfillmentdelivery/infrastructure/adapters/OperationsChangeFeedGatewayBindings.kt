package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters

import com.nexa.mobile.operations.fulfillmentdelivery.application.changefeed.OperationsChangeFeedGateway
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.NexaOperationsChangeFeedGateway
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object OperationsChangeFeedGatewayBindings {
    @Provides
    @Singleton
    fun operationsChangeFeedGateway(
        implementation: NexaOperationsChangeFeedGateway
    ): OperationsChangeFeedGateway = implementation
}
