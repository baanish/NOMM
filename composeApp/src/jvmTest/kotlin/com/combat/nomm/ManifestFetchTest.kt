package com.combat.nomm

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManifestFetchTest {
    private val remote = Version.fromString("1.1.0.2459")

    @Test
    fun `skip fetch when versions match and cache has mods`() {
        assertTrue(shouldSkipManifestFetch(remote, remote, cachedManifestNonEmpty = true, ignoreManifestVersion = false))
    }

    @Test
    fun `do not skip when cache is empty even if versions match`() {
        assertFalse(shouldSkipManifestFetch(remote, remote, cachedManifestNonEmpty = false, ignoreManifestVersion = false))
    }

    @Test
    fun `do not skip when versions differ`() {
        assertFalse(shouldSkipManifestFetch(Version(0), remote, cachedManifestNonEmpty = true, ignoreManifestVersion = false))
    }

    @Test
    fun `never skip when ignoring manifest version`() {
        assertFalse(shouldSkipManifestFetch(remote, remote, cachedManifestNonEmpty = true, ignoreManifestVersion = true))
    }

    @Test
    fun `never skip when ignoring manifest version even with empty cache`() {
        assertFalse(shouldSkipManifestFetch(remote, remote, cachedManifestNonEmpty = false, ignoreManifestVersion = true))
    }
}
