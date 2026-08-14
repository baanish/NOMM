package com.combat.nomm

import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.path
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.contentType
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath

class PreviewImageKeyer : Keyer<Extension> {
    override fun key(data: Extension, options: Options): String {
        val hashOrUrl = data.imageHash ?: data.imageUrl?.hashCode()?.toString() ?: "no_image"
        return "${data.id}_$hashOrUrl"
    }
}

class HashValidatingFetcher(
    private val data: Extension,
    private val options: Options,
    private val ktorClient: HttpClient
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val url = data.imageUrl ?: throw IllegalArgumentException("Image URL is null for ID ${data.id}")

        val response = ktorClient.get(url)
        val bytes = response.bodyAsBytes()
        
        val expectedHash = data.imageHash
        if (!expectedHash.isNullOrBlank()) {
            val downloadedHash = bytes.toByteString().sha256().hex()
            if (!downloadedHash.equals(expectedHash, ignoreCase = true)) {
                throw IllegalStateException(
                    "Image hash mismatch for ID ${data.id}! Expected: $expectedHash, Downloaded: $downloadedHash"
                )
            }
        }

        return SourceFetchResult(
            source = ImageSource(
                source = Buffer().write(bytes),
                fileSystem = options.fileSystem
            ),
            mimeType = response.contentType()?.toString(),
            dataSource = DataSource.NETWORK
        )
    }

    class Factory(private val ktorClient: HttpClient) : Fetcher.Factory<Extension> {
        override fun create(data: Extension, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.imageUrl.isNullOrBlank()) return null
            return HashValidatingFetcher(data, options, ktorClient)
        }
    }
}

@Composable
fun SetupCoil(ktorClient: HttpClient) {
    val platformContext = LocalPlatformContext.current

    setSingletonImageLoaderFactory {
        val previewImagesPath = "${FileKit.filesDir.path}/previewImages".toPath()

        ImageLoader.Builder(platformContext)
            .diskCache {
                DiskCache.Builder()
                    .directory(previewImagesPath)
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .components {
                add(PreviewImageKeyer())
                add(HashValidatingFetcher.Factory(ktorClient))
            }
            .build()
    }
}