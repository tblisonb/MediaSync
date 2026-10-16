package com.tlisonbee.mediasync.library

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoNamesTest {
    private fun guess(path: String): String {
        val g = VideoNames.guess(path)
        return g.category + " " + listOf("show", "season", "episode", "title", "year").mapNotNull { k -> g.tags[k]?.let { "$k=${it.single()}" } }.joinToString(" ")
    }

    @Test
    fun moviesAndEpisodesAreRecognised() {
        val cases = mapOf(
            "Movies/Inception (2010).mp4" to "movies title=Inception year=2010",
            "Movies/E.T. The Extra-Terrestrial (1982).mp4" to "movies title=E.T. The Extra-Terrestrial year=1982",
            "Movies/Megamind.2010.1080p.BluRay.x264.YIFY.mp4" to "movies title=Megamind year=2010",
            "Movies/How.to.Train.Your.Dragon.The.Hidden.World.2019.1080p.BluRay.AC3.x264-MkvCage.ws.mkv" to "movies title=How to Train Your Dragon The Hidden World year=2019",
            "Movies/Kubo And The Two Strings 2016 1080p BluRay x264 DTS-JYK.mkv" to "movies title=Kubo And The Two Strings year=2016",
            "Movies/Walk.Hard.The.Dewey.Cox.Story.2007.720P.Bluray.X264.[Moviesfd].mkv" to "movies title=Walk Hard The Dewey Cox Story year=2007",
            "Movies/Whiplash_1080p.mp4" to "movies title=Whiplash",
            "Movies/Elf.mp4" to "movies title=Elf",
            "Movies/Planes, Trains and Automobiles (1987).mp4" to "movies title=Planes, Trains and Automobiles year=1987",
            "Movies/Parasite.2019.1080p.HDRip.X264.AC3-EVO.mkv" to "movies title=Parasite year=2019",
            "TV Shows/Fringe/Fringe.S01E01.mp4" to "shows show=Fringe season=1 episode=1",
            "TV Shows/Fringe/Fringe.s03e12.mp4" to "shows show=Fringe season=3 episode=12",
            "Movies/The Owl House/The Owl House S3E1.mkv" to "shows show=The Owl House season=3 episode=1",
            "Movies/Absolute Cinema/The Justin Show (2016) - Episode 2.mp4" to "shows show=The Justin Show (2016) season=1 episode=2",
            "Movies/Absolute Cinema/The Justin Show - Episode 4.mp4" to "shows show=The Justin Show season=1 episode=4",
            "Shows/Lost/Season 1/1x05 White Rabbit.mkv" to "shows show=Lost season=1 episode=5 title=White Rabbit",
            "Home Movies/2019-07-04 fireworks.mp4" to "movies title=2019-07-04 fireworks",
            "DCIM/VID_20260101_120000.mp4" to "other title=VID 20260101 120000",
        )
        val wrong = cases.mapNotNull { (path, expected) -> guess(path).takeIf { it != expected }?.let { "$path\n  expected: $expected\n  got:      $it" } }
        assertEquals("", wrong.joinToString("\n"))
    }
}
