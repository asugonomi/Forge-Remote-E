package jp.local.forgedesk

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryDateQueryTest {
    @Test fun datesFromJapaneseAndEnglishKeyboardsMatchIsoFolders() {
        listOf("2025-01-08","２０２５−０１−０８","２０２５－０１－０８",
            "２０２５／０１／０８","２０２５ー０１ー０８").forEach {
            assertEquals("2025-01-08",galleryDateQuery(it))
        }
        assertEquals("2025-01",galleryDateQuery("　２０２５−０１　"))
        assertEquals("202501",galleryDateQuery("２０２５０１"))
    }
}
