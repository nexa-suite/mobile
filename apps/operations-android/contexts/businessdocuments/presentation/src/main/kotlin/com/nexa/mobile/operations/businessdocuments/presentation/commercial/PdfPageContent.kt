package com.nexa.mobile.operations.businessdocuments.presentation.commercial

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.businessdocuments.application.commercial.BusinessDocumentPdfPageRenderer
import com.nexa.mobile.operations.businessdocuments.application.commercial.RenderedBusinessDocumentPage
import com.nexa.mobile.operations.businessdocuments.domain.model.commercial.BusinessDocumentContent
import kotlinx.coroutines.CancellationException

@Composable
fun BusinessDocumentsScreen(
    state: BusinessDocumentsState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpen: (String) -> Unit,
    onCloseContent: () -> Unit,
    pdfPageRenderer: BusinessDocumentPdfPageRenderer
) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(
            WindowInsets.safeDrawing
        ).verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("Volver") }
        Text("Documentos emitidos", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Acceso vigente obligatorio. Contenido emitido inmutable; copia local no autoriza decisiones."
        )
        Text(
            "Estado: ${when (state.status) {
                "Current" -> "Información recibida"
                "Pending" -> "Consultando información"
                "PermissionDenied" -> "Acceso no vigente"
                "Unavailable" -> "Contenido no disponible"
                else -> "Sin consultar"
            }}. Visor protegido PDF, CSV y XML hasta 8 MiB."
        )
        if (state.status == "Current" && !state.canDownloadContent) {
            Text("Puede consultar los datos de emisión.")
            Text("Su membresía no permite descargar el contenido privado.")
        }
        Button(onClick = onRefresh, enabled = state.status != "Pending") {
            Text("Actualizar documentos")
        }
        val content = state.content
        if (content != null) {
            Text(
                "${content.identity.number ?: content.identity.id} · versión ${content.identity.version}"
            )
            Text("${content.identity.subjectType}: ${content.identity.subjectId}")
            TextButton(onClick = onCloseContent) { Text("Cerrar contenido") }
            if (content.identity.format ==
                "PDF"
            ) {
                ProtectedPdfContent(content, pdfPageRenderer)
            } else {
                ProtectedTextContent(content)
            }
        } else {
            when (state.contentStatus) {
                "Pending" -> Text("Consultando contenido autorizado…")
                "DownloadDenied" -> Text("Sin permiso para descargar contenido privado.")
                "Unavailable" -> Text("El contenido autorizado no está disponible.")
            }
            if (state.status == "Current" &&
                state.items.isEmpty()
            ) {
                Text("Documento emitido no disponible. Ningún compromiso cambia.")
            }
            if (state.status ==
                "PermissionDenied"
            ) {
                Text("Acceso no vigente. Contenido privado no disponible.")
            }
            if (state.status ==
                "Unavailable"
            ) {
                Text("Documento o contenido autorizado no disponible.")
            }
            state.items.forEach { item ->
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Text("${item.number ?: item.id} · ${item.type} · versión ${item.version}")
                        Text("Cliente: ${item.customerId}\n${item.subjectType}: ${item.subjectId}")
                        Text("Emitido: ${item.generatedAt ?: "fecha no disponible"}")
                        TextButton(
                            onClick = { onOpen(item.id) },
                            enabled = state.status == "Current" &&
                                state.canDownloadContent && state.contentStatus != "Pending"
                        ) {
                            Text(
                                if (state.canDownloadContent) {
                                    "Consultar contenido autorizado"
                                } else {
                                    "Contenido restringido"
                                }
                            )
                        }
                    }
                }
            }
            Row {
                TextButton(
                    onClick = onPrevious,
                    enabled =
                        state.status == "Current" && state.page > 0
                ) { Text("Anterior") }
                Text("Página ${state.page + 1}")
                TextButton(
                    onClick = onNext,
                    enabled =
                        state.status == "Current" &&
                            (state.page + 1) * 25L < (state.totalItems ?: 0)
                ) {
                    Text("Siguiente")
                }
            }
        }
    }
}

private data class PdfPageContent(
    val content: BusinessDocumentContent,
    val page: Int,
    val renderedPage: RenderedBusinessDocumentPage
)

@Composable
private fun ProtectedPdfContent(
    content: BusinessDocumentContent,
    renderer: BusinessDocumentPdfPageRenderer
) {
    var page by remember(content) { mutableIntStateOf(0) }
    val rendered by produceState<PdfPageContent?>(null, content, page, renderer) {
        value = null
        val result = try {
            renderer.renderPage(content.bytes, page)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (result != null) value = PdfPageContent(content, page, result)
    }
    val result = rendered?.takeIf { it.content === content && it.page == page }
    if (result == null) {
        Text("Página no disponible o cargando.")
    } else {
        val image = remember(result) {
            Bitmap.createBitmap(
                result.renderedPage.argbPixels,
                result.renderedPage.width,
                result.renderedPage.height,
                Bitmap.Config.ARGB_8888
            )
        }
        Image(
            image.asImageBitmap(),
            contentDescription = "Documento autorizado, página ${page + 1}",
            modifier = Modifier.fillMaxWidth()
        )
        Row {
            TextButton(onClick = { page-- }, enabled = page > 0) { Text("Página anterior") }
            Text("${page + 1}/${result.renderedPage.totalPages}")
            TextButton(onClick = {
                page++
            }, enabled = page + 1 < result.renderedPage.totalPages) { Text("Página siguiente") }
        }
    }
}

@Composable
private fun ProtectedTextContent(content: BusinessDocumentContent) {
    val text = remember(content) { content.bytes.toString(Charsets.UTF_8) }
    var page by remember(content) { mutableIntStateOf(0) }
    val pageSize = 16000
    val pages = maxOf(1, (text.length + pageSize - 1) / pageSize)
    Text(text.substring(page * pageSize, minOf(text.length, (page + 1) * pageSize)))
    Row {
        TextButton(onClick = { page-- }, enabled = page > 0) { Text("Anterior") }
        Text("${page + 1}/$pages")
        TextButton(onClick = { page++ }, enabled = page + 1 < pages) { Text("Siguiente") }
    }
}
