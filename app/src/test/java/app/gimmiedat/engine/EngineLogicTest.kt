package app.gimmiedat.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts ported from yoinks' TypeScript, same inputs, same outputs. */
class EngineLogicTest {

    @Test fun formatBytesMatchesTheCli() {
        assertEquals("", formatBytes(0.0))
        assertEquals("512 B", formatBytes(512.0))
        assertEquals("3.3 MB", formatBytes(3.3 * 1024 * 1024))
        assertEquals("232 MB", formatBytes(232.0 * 1024 * 1024))
        assertEquals("1.5 GB", formatBytes(1.5 * 1024 * 1024 * 1024))
    }

    @Test fun formatDurationMatchesTheCli() {
        assertEquals("3:33", formatDuration(213.0))
        assertEquals("1:02:03", formatDuration(3723.0))
        assertEquals("0:05", formatDuration(5.0))
        assertEquals("", formatDuration(0.0))
    }

    @Test fun detectsPlatforms() {
        assertEquals("YouTube", detectPlatform("https://youtu.be/dQw4w9WgXcQ").label)
        assertEquals("YouTube", detectPlatform("https://m.youtube.com/watch?v=x").label)
        assertEquals("X / Twitter", detectPlatform("https://x.com/a/status/1").label)
        assertEquals("Threads", detectPlatform("https://www.threads.com/@a/post/x").label)
        assertEquals("example.org", detectPlatform("https://www.example.org/v").label)
    }

    @Test fun urlChecks() {
        assertTrue(isProbablyUrl("https://youtu.be/dQw4w9WgXcQ"))
        assertFalse(isProbablyUrl("youtu.be/dQw4w9WgXcQ"))
        assertFalse(isProbablyUrl("ftp://x.com/a"))
        assertFalse(isProbablyUrl("https://a b"))
    }

    @Test fun extractsLinksFromShareText() {
        assertEquals(
            "https://vm.tiktok.com/ZMabc123/",
            extractUrl("Check out this video! https://vm.tiktok.com/ZMabc123/ #fyp"),
        )
        assertEquals("https://youtu.be/dQw4w9WgXcQ", extractUrl("Never Gonna Give You Up\nhttps://youtu.be/dQw4w9WgXcQ."))
        assertNull(extractUrl("no link here"))
    }

    @Test fun buildsChoicesLikeTheCli() {
        val json = """
        {"id":"x","title":"Rick","uploader":"Rick Astley","duration":213,"formats":[
          {"format_id":"140","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":129.5,"filesize":3460000},
          {"format_id":"251","ext":"webm","vcodec":"none","acodec":"opus","abr":135.0,"filesize":3500000},
          {"format_id":"137","ext":"mp4","vcodec":"avc1.640028","acodec":"none","height":1080,"tbr":4000,"filesize":80000000},
          {"format_id":"248","ext":"webm","vcodec":"vp9","acodec":"none","height":1080,"tbr":5000,"filesize":70000000},
          {"format_id":"18","ext":"mp4","vcodec":"avc1.42001E","acodec":"mp4a.40.2","height":360,"tbr":500,"filesize_approx":11000000},
          {"format_id":"sb0","ext":"mhtml","vcodec":"none","acodec":"none"}
        ]}
        """.trimIndent()
        val info = parseVideoInfo(json)
        val choices = buildChoices(info)
        assertEquals(3, choices.size)
        // 1080p picks the mp4/avc stream (scoreVideo) and adds the best audio (251, highest abr)
        assertEquals("1080p · mp4 · ~80 MB", choices[0].label)
        assertEquals(Kind.VIDEO, choices[0].kind)
        assertEquals(
            listOf("-f", "bv*[height=1080]+ba/b[height=1080]/bv*[height<=1080]+ba/b", "--merge-output-format", "mp4"),
            choices[0].args,
        )
        // 360p is already muxed, no audio added to its size
        assertEquals("360p · mp4 · ~10 MB", choices[1].label)
        assertEquals("audio only · mp3 · ~3.3 MB", choices[2].label)
        assertEquals("♪ audio only · mp3 · ~3.3 MB", choices[2].display)
    }

    @Test fun estimatesMissingSizesFromBitrate() {
        // 240p video-only with no filesize but a bitrate; audio has a real size
        val info = parseVideoInfo(
            """{"title":"zoo","duration":19,"formats":[
                {"format_id":"140","vcodec":"none","acodec":"mp4a","abr":128,"filesize":309000},
                {"format_id":"133","ext":"mp4","vcodec":"avc1","acodec":"none","height":240,"tbr":250},
                {"format_id":"160","ext":"mp4","vcodec":"avc1","acodec":"none","height":144}
            ]}""",
        )
        val labels = buildChoices(info).map { it.label }
        // 250 kbit/s × 19 s ≈ 594 KB, plus 309 KB of audio
        assertEquals("240p · mp4 · ~882 KB", labels[0])
        // nothing to go on for 144p: no size beats a wrong one
        assertEquals("144p · mp4", labels[1])
        assertEquals("audio only · mp3 · ~302 KB", labels[2])
    }

    @Test fun fallsBackWhenNoHeights() {
        val info = parseVideoInfo("""{"title":"clip","formats":[{"format_id":"0","ext":"mp4","vcodec":"h264","acodec":"aac"}]}""")
        val choices = buildChoices(info)
        assertEquals("best available · mp4", choices[0].label)
        assertEquals("audio only · mp3", choices[1].label)
    }

    @Test fun playlistsCountEntriesAndHideSizes() {
        val info = parseVideoInfo(
            """{"_type":"playlist","title":"thread","entries":[
                {"title":"a","formats":[{"format_id":"1","vcodec":"h264","acodec":"aac","height":720,"filesize":100}]},
                {"title":"b","formats":[{"format_id":"2","vcodec":"h264","acodec":"aac","height":480,"filesize":100}]}
            ]}""",
        )
        assertEquals(2, info.entryCount)
        val labels = buildChoices(info).map { it.label }
        assertEquals(listOf("720p · mp4", "480p · mp4", "audio only · mp3"), labels)
    }

    @Test fun cleansYtDlpErrors() {
        assertEquals(
            "Video unavailable. This video is private",
            cleanYtDlpError("WARNING: x\nERROR: [youtube] dQw4w9WgXcQ: Video unavailable. This video is private\n"),
        )
        assertEquals(
            "Unsupported URL: https://example.com/",
            cleanYtDlpError("ERROR: Unsupported URL: https://example.com/"),
        )
        assertEquals(
            "Couldn't reach the site. Check your connection and try again.",
            cleanYtDlpError("ERROR: [generic] Unable to download webpage: <urlopen error [Errno 7] No address associated with hostname>"),
        )
    }
}
