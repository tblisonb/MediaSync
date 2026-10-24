package com.tlisonbee.mediasync.library

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.tags.TagField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotoDatesTest {
    @Test
    fun datesAreAYearAMonthOrADay() {
        assertEquals("2019", PhotoDates.normalize(" 2019 "))
        assertEquals("2019-07", PhotoDates.normalize("2019-07"))
        assertEquals("2019-07-14", PhotoDates.normalize("2019-07-14"))
        assertEquals("2019-07-14", PhotoDates.normalize("2019:07:14 12:30:05"), "EXIF")
        assertEquals("2019-07-14", PhotoDates.normalize("2019-07-14T12:30:05Z"), "ISO")
        assertEquals("2019-07-14", PhotoDates.normalize("2019/07/14"))
        assertNull(PhotoDates.normalize("0000:00:00 00:00:00"), "a camera without a clock")
        assertNull(PhotoDates.normalize("2019-02-30"))
        assertNull(PhotoDates.normalize("2019-13"))
        assertNull(PhotoDates.normalize("July 2019"))
        assertNull(PhotoDates.normalize(""))
    }

    @Test
    fun camerasAndAppsPutTheDayInTheName() {
        assertEquals("2024-07-14", PhotoDates.fromName("IMG_20240714_153012"))
        assertEquals("2024-07-14", PhotoDates.fromName("PXL_20240714_153012345.MP"))
        assertEquals("2024-07-14", PhotoDates.fromName("Screenshot_2024-07-14-15-30-12-123_com.android.chrome"))
        assertEquals("2024-07-14", PhotoDates.fromName("IMG-20240714-WA0001"))
        assertEquals("2024-07-14", PhotoDates.fromName("VID_20240714_153012"))
        assertEquals("2024-07-14", PhotoDates.fromName("2024-07-14 15.30.12"))
        assertNull(PhotoDates.fromName("IMG_1234"))
        assertNull(PhotoDates.fromName("DSC_2019"))
        assertNull(PhotoDates.fromName("1720912345678"), "a timestamp in milliseconds isn't eight digits")
        assertNull(PhotoDates.fromName("scan 20241399"), "not a real day")
    }

    @Test
    fun photosAreFiledByYearWithTheDateLeadingTheName() {
        fun dated(date: String?) = date?.let { mapOf(TagField.DATE to listOf(it)) } ?: emptyMap()
        assertEquals("images/photos/2024/2024-07-14 IMG_1234", RepoPaths.photo(dated("2024-07-14"), "IMG_1234"))
        assertEquals("images/photos/2019/2019 Christmas", RepoPaths.photo(dated("2019"), "Christmas"))
        assertEquals("images/photos/2019/2019-12 Christmas", RepoPaths.photo(dated("2019-12"), "2019 Christmas"), "its own date isn't repeated")
        assertEquals("images/photos/Undated/scan_017", RepoPaths.photo(dated(null), "scan_017"))
        assertEquals("images/photos/Undated/scan_017", RepoPaths.photo(dated("someday"), "scan_017"))

        assertTrue(RepoPaths.followsFields(AssetKind.IMAGE, "images/photos/2024/x", null))
        assertTrue(RepoPaths.followsFields(AssetKind.VIDEO, "images/photos/Undated/clip", null), "a phone's clips stay with its photos")
        assertFalse(RepoPaths.followsFields(AssetKind.OTHER, "images/photos/2024/notes", null))
        assertFalse(RepoPaths.followsFields(AssetKind.IMAGE, "music/Radiohead/OK Computer/cover", null), "album art keeps its place")
    }

    @Test
    fun theDateIsKeptForPhotosAndClipsWhateverThePolicy() {
        val policy = TagPolicy(fields = mapOf(AssetKind.AUDIO to listOf(TagField.TITLE), AssetKind.VIDEO to listOf(TagField.TITLE)))
        assertEquals(mapOf(TagField.DATE to listOf("2024")), policy.filter(AssetKind.IMAGE, mapOf(TagField.DATE to listOf("2024"), TagField.TITLE to listOf("x"))))
        assertEquals(listOf(TagField.TITLE, TagField.DATE), policy.allowed(AssetKind.VIDEO))
        assertEquals(listOf(TagField.TITLE), policy.allowed(AssetKind.AUDIO))
    }
}
