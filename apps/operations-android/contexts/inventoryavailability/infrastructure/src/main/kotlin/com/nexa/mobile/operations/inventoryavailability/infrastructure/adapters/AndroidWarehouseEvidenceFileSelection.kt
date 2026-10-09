package com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters

import android.content.Context
import androidx.core.net.toUri
import com.nexa.mobile.operations.core.local.files.AndroidPrivateImageFileSelection
import com.nexa.mobile.operations.core.local.files.PrivateImageFileCandidate
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.WarehouseEvidenceFileCandidate
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.WarehouseEvidenceFileSelectionPort
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.WarehouseEvidenceSelectionCoordinator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Adapts Android content URIs and private temp files to the BC-05 application port. */
class AndroidWarehouseEvidenceFileSelection(context: Context) :
    WarehouseEvidenceFileSelectionPort {
    private val selection = AndroidPrivateImageFileSelection(context)

    override suspend fun prepare(
        sourceUri: String,
        scopeKey: String
    ): WarehouseEvidenceFileCandidate? {
        val uri = runCatching { sourceUri.toUri() }.getOrNull() ?: return null
        val selected = selection.prepare(uri, scopeKey) ?: return null
        return try {
            selected.toWarehouseCandidate()
        } catch (_: Exception) {
            selection.discard(selected)
            null
        }
    }

    override fun discard(candidate: WarehouseEvidenceFileCandidate) {
        selection.discard(candidate.toPrivateCandidate())
    }

    private fun PrivateImageFileCandidate.toWarehouseCandidate() = WarehouseEvidenceFileCandidate(
        file,
        originalFilename,
        declaredContentType,
        byteSize,
        checksumSha256
    )

    private fun WarehouseEvidenceFileCandidate.toPrivateCandidate() = PrivateImageFileCandidate(
        file,
        originalFilename,
        declaredContentType,
        byteSize,
        checksumSha256
    )
}

@Module
@InstallIn(SingletonComponent::class)
object WarehouseEvidenceSelectionModule {
    @Provides
    @Singleton
    fun provideWarehouseEvidenceFileSelection(
        @ApplicationContext context: Context
    ): WarehouseEvidenceFileSelectionPort = AndroidWarehouseEvidenceFileSelection(context)

    @Provides
    @Singleton
    fun provideWarehouseEvidenceSelectionCoordinator(
        files: WarehouseEvidenceFileSelectionPort
    ): WarehouseEvidenceSelectionCoordinator = WarehouseEvidenceSelectionCoordinator(files)
}
