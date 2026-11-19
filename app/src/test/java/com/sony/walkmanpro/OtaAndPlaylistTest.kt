package com.sony.walkmanpro

import com.sony.walkmanpro.playback.nextTrackIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OtaAndPlaylistTest {
    @Test
    fun comparesStableAndPrereleaseVersionsNumerically() {
        assertTrue(OtaUpdateManager.isRemoteVersionNewer("11.8.5", "v11.9.0"))
        assertTrue(OtaUpdateManager.isRemoteVersionNewer("v11.9.0-rc.2", "v11.9.0-rc.10"))
        assertTrue(OtaUpdateManager.isRemoteVersionNewer("v11.9.0-rc.10", "11.9.0"))
        for (tag in listOf("v11.8.5", "v11.8.4", "prefix-11.9.0", "v11.09.0", "v11.9.0-01")) {
            assertFalse(OtaUpdateManager.isRemoteVersionNewer("11.8.5", tag))
        }
    }

    @Test
    fun checksExactSizeDigestAndReleaseAssetOrigin() {
        val file = File.createTempFile("walkman-test-", ".apk")
        try {
            file.writeText("abc")
            val digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            assertTrue(OtaUpdateManager.matchesApkBytes(file, 3L, digest))
            assertFalse(OtaUpdateManager.matchesApkBytes(file, 4L, digest))
            file.writeText("abd")
            assertFalse(OtaUpdateManager.matchesApkBytes(file, 3L, digest))
        } finally {
            file.delete()
        }

        val tag = "v11.9.0"
        val path = "/${BuildConfig.GITHUB_REPO}/releases/download/$tag/"
        assertTrue(OtaUpdateManager.isTrustedAssetUrl("https://github.com${path}Walkman.apk", tag))
        for (uri in listOf(
            "http://github.com${path}Walkman.apk", "https://github.com.evil.test${path}Walkman.apk",
            "https://someone@github.com${path}Walkman.apk", "https://github.com:444${path}Walkman.apk",
            "https://github.com${path}child%2fWalkman.apk", "https://github.com${path}Walkman.apk?x=1",
            "https://github.com/other/player/releases/download/$tag/Walkman.apk"
        )) assertFalse(uri, OtaUpdateManager.isTrustedAssetUrl(uri, tag))
    }

    @Test
    fun restoresTrackByUriAndSupportsLegacyIndex() {
        val tracks = listOf("track-b", "track-a", "track-c")
        assertEquals(1, PlaylistStorage.restoredIndex(tracks, "track-a", 0))
        assertEquals(2, PlaylistStorage.restoredIndex(tracks, null, 2))
        assertEquals(0, PlaylistStorage.restoredIndex(tracks, "removed", 2))
        assertEquals(0, PlaylistStorage.restoredIndex(emptyList(), "removed", 10))
    }

    @Test
    fun queueWrapsAndOnlyAutomaticRepeatReusesTheSameTrack() {
        assertEquals(0, nextTrackIndex(2, 3, PlayMode.SEQUENTIAL, 1, false))
        assertEquals(2, nextTrackIndex(0, 3, PlayMode.SEQUENTIAL, -1, false))
        assertEquals(1, nextTrackIndex(1, 3, PlayMode.REPEAT_ONE, 1, true))
        assertEquals(2, nextTrackIndex(1, 3, PlayMode.REPEAT_ONE, 1, false))
        repeat(20) { assertTrue(nextTrackIndex(1, 3, PlayMode.SHUFFLE, 1, true) in listOf(0, 2)) }
    }
}
