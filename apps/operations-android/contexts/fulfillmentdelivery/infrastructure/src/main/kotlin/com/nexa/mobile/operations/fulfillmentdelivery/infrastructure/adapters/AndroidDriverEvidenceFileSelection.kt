package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters

import android.content.Context
import androidx.core.net.toUri
import com.nexa.mobile.operations.core.local.files.AndroidPrivateImageFileSelection
import com.nexa.mobile.operations.core.local.files.PrivateImageFileCandidate
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverEvidenceFileSelectionPort
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofFileCandidate
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.ReturnedDriverIncidentEvidenceSelectionCoordinator
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.ReturnedDriverProofSelectionCoordinator
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperatureEvidenceSelectionCoordinator
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperaturePhotoSelectionPort
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperatureSelectedPhoto
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Adapts Android content URIs and private temp files to the BC-06 application port. */
class AndroidDriverEvidenceFileSelection(context: Context) : DriverEvidenceFileSelectionPort {
    private val selection = AndroidPrivateImageFileSelection(context)

    override suspend fun prepare(sourceUri: String, scopeKey: String): DriverProofFileCandidate? {
        val uri = runCatching { sourceUri.toUri() }.getOrNull() ?: return null
        val selected = selection.prepare(uri, scopeKey) ?: return null
        return try {
            selected.toDriverCandidate()
        } catch (_: Exception) {
            selection.discard(selected)
            null
        }
    }

    override fun discard(candidate: DriverProofFileCandidate) {
        selection.discard(candidate.toPrivateCandidate())
    }

    private fun PrivateImageFileCandidate.toDriverCandidate() = DriverProofFileCandidate(
        file,
        originalFilename,
        declaredContentType,
        byteSize,
        checksumSha256
    )

    private fun DriverProofFileCandidate.toPrivateCandidate() = PrivateImageFileCandidate(
        file,
        originalFilename,
        declaredContentType,
        byteSize,
        checksumSha256
    )
}

/** Adapts Android content URIs and private temp files to BC-06 dispatch temperature photos. */
class AndroidDispatchTemperaturePhotoSelection(context: Context) :
    DispatchTemperaturePhotoSelectionPort {
    private val selection = AndroidPrivateImageFileSelection(context)

    override suspend fun prepare(
        sourceUri: String,
        scopeKey: String
    ): DispatchTemperatureSelectedPhoto? {
        val uri = runCatching { sourceUri.toUri() }.getOrNull() ?: return null
        val selected = selection.prepare(uri, scopeKey) ?: return null
        return try {
            DispatchTemperatureSelectedPhoto(
                selected.file,
                selected.originalFilename,
                selected.declaredContentType,
                selected.byteSize,
                selected.checksumSha256
            )
        } catch (_: Exception) {
            selection.discard(selected)
            null
        }
    }

    override fun discard(candidate: DispatchTemperatureSelectedPhoto) {
        selection.discard(
            PrivateImageFileCandidate(
                candidate.file,
                candidate.originalFilename,
                candidate.declaredContentType,
                candidate.byteSize,
                candidate.checksumSha256
            )
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DriverEvidenceSelectionModule {
    @Provides
    @Singleton
    fun provideDriverEvidenceFileSelection(
        @ApplicationContext context: Context
    ): DriverEvidenceFileSelectionPort = AndroidDriverEvidenceFileSelection(context)

    @Provides
    @Singleton
    fun provideReturnedDriverProofSelectionCoordinator(
        metadataStore: DriverProofMetadataStore,
        fileSelection: DriverEvidenceFileSelectionPort
    ): ReturnedDriverProofSelectionCoordinator =
        ReturnedDriverProofSelectionCoordinator(metadataStore, fileSelection)

    @Provides
    @Singleton
    fun provideReturnedDriverIncidentEvidenceSelectionCoordinator(
        metadataStore: DriverIncidentMetadataStore,
        fileSelection: DriverEvidenceFileSelectionPort
    ): ReturnedDriverIncidentEvidenceSelectionCoordinator =
        ReturnedDriverIncidentEvidenceSelectionCoordinator(metadataStore, fileSelection)

    @Provides
    @Singleton
    fun provideDispatchTemperaturePhotoSelectionPort(
        @ApplicationContext context: Context
    ): DispatchTemperaturePhotoSelectionPort = AndroidDispatchTemperaturePhotoSelection(context)

    @Provides
    @Singleton
    fun provideDispatchTemperatureEvidenceSelectionCoordinator(
        photos: DispatchTemperaturePhotoSelectionPort
    ): DispatchTemperatureEvidenceSelectionCoordinator =
        DispatchTemperatureEvidenceSelectionCoordinator(photos)
}
