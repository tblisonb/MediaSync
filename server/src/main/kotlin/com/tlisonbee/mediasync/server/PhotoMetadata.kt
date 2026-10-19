package com.tlisonbee.mediasync.server

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Directory
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.mov.QuickTimeDirectory
import com.drew.metadata.mp4.Mp4Directory
import com.tlisonbee.mediasync.library.PhotoDates
import java.nio.file.Path
import java.time.ZoneId

/** When a photo or a phone's video was taken, from the file itself. */
object PhotoMetadata {
    /**
     * The capture date the camera wrote: EXIF's DateTimeOriginal (or DateTimeDigitized) in
     * images, the creation time in MP4 and QuickTime videos. Null when there's none, or the file
     * can't be read.
     */
    fun date(file: Path): String? = try {
        val metadata = ImageMetadataReader.readMetadata(file.toFile())
        exifDate(metadata.getDirectoriesOfType(ExifSubIFDDirectory::class.java))
            ?: videoDate(metadata.getDirectoriesOfType(Mp4Directory::class.java), Mp4Directory.TAG_CREATION_TIME)
            ?: videoDate(metadata.getDirectoriesOfType(QuickTimeDirectory::class.java), QuickTimeDirectory.TAG_CREATION_TIME)
    } catch (e: Exception) {
        null
    }

    /** EXIF's orientation (1 upright, 3 upside down, 6 and 8 turned a quarter), 1 if there's none. */
    fun orientation(file: Path): Int = try {
        ImageMetadataReader.readMetadata(file.toFile()).getDirectoriesOfType(ExifIFD0Directory::class.java)
            .firstNotNullOfOrNull { d -> d.getInteger(ExifIFD0Directory.TAG_ORIENTATION) } ?: 1
    } catch (e: Exception) {
        1
    }

    // The camera's own local time, as written; read as text so no time zone shifts the day.
    private fun exifDate(directories: Collection<ExifSubIFDDirectory>): String? = directories.firstNotNullOfOrNull { d ->
        listOf(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED)
            .firstNotNullOfOrNull { tag -> d.getString(tag)?.let(PhotoDates::normalize) }
    }

    // In UTC, as phones write it: this server's time zone gives the day it was where the phone was, usually.
    private fun videoDate(directories: Collection<Directory>, tag: Int): String? = directories.firstNotNullOfOrNull { d ->
        val date = d.getDate(tag)?.toInstant()?.atZone(ZoneId.systemDefault())?.toLocalDate() ?: return@firstNotNullOfOrNull null
        // An unset creation time reads as 1904 or 1970.
        date.takeIf { it.year > 1970 }?.let { PhotoDates.normalize(it.toString()) }
    }
}
