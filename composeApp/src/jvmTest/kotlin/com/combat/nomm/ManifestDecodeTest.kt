package com.combat.nomm

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ManifestDecodeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `manifest with downloadURL casing decodes and resolves url`() {
        val manifest = json.decodeFromString<Manifest>(
            """
            [
              {
                "id": "OneLife",
                "displayName": "One Life",
                "artifacts": [
                  {
                    "fileName": "OneLife.zip",
                    "version": "0.1.0",
                    "category": "Release",
                    "type": "plugin",
                    "gameVersion": "0.34",
                    "downloadURL": "https://github.com/Uniplast/One-Life/releases/download/v0.1.0/OneLife.zip",
                    "hash": "sha256:217ed7cd8c77be3939955d909e3f6109af9b83983a9ff5a2b6f4f6fad1f7b8d5"
                  }
                ],
                "downloadCount": 74
              }
            ]
            """.trimIndent()
        )
        assertEquals(1, manifest.size)
        val artifact = manifest.single().artifacts.single()
        assertNull(artifact.downloadUrl)
        assertEquals(
            "https://github.com/Uniplast/One-Life/releases/download/v0.1.0/OneLife.zip",
            artifact.resolvedDownloadUrl
        )
    }

    @Test
    fun `manifest with correct downloadUrl casing decodes`() {
        val manifest = json.decodeFromString<Manifest>(
            """[ { "id": "foo", "artifacts": [ { "version": "1.0.0", "downloadUrl": "https://x.com/a.zip" } ] } ]"""
        )
        assertEquals("https://x.com/a.zip", manifest.single().artifacts.single().resolvedDownloadUrl)
    }

    @Test
    fun `manifest with no download field decodes with null url`() {
        val manifest = json.decodeFromString<Manifest>(
            """[ { "id": "foo", "artifacts": [ { "version": "1.0.0" } ] } ]"""
        )
        assertNull(manifest.single().artifacts.single().resolvedDownloadUrl)
    }

    @Test
    fun `downloadUrl takes precedence over downloadURL`() {
        val manifest = json.decodeFromString<Manifest>(
            """[ { "id": "foo", "artifacts": [ { "version": "1.0.0", "downloadUrl": "https://x.com/good.zip", "downloadURL": "https://x.com/bad.zip" } ] } ]"""
        )
        assertEquals("https://x.com/good.zip", manifest.single().artifacts.single().resolvedDownloadUrl)
    }

    @Test
    fun `full manifest with mixed artifacts decodes`() {
        val manifest = json.decodeFromString<Manifest>(
            """
            [
              {
                "id": "a",
                "displayName": "A",
                "artifacts": [
                  {
                    "fileName": "a.zip",
                    "version": "0.1.0",
                    "category": "Release",
                    "type": "Mod",
                    "gameVersion": "0.34",
                    "downloadUrl": "https://x.com/a.zip",
                    "hash": "sha256:abc",
                    "extends": { "id": "b" },
                    "dependencies": [ { "id": "b", "version": "1.0.0" } ],
                    "incompatibilities": [ { "id": "c" } ]
                  }
                ],
                "urls": [ { "name": "Info", "url": "https://x.com/a" } ],
                "authors": ["X"],
                "tags": ["t"],
                "downloadCount": 5
              },
              { "id": "b", "artifacts": [ { "version": "1.0.0", "downloadURL": "https://x.com/b.zip" } ] }
            ]
            """.trimIndent()
        )
        assertEquals(2, manifest.size)
        assertEquals("https://x.com/a.zip", manifest[0].artifacts.single().resolvedDownloadUrl)
        assertEquals("https://x.com/b.zip", manifest[1].artifacts.single().resolvedDownloadUrl)
    }
}
