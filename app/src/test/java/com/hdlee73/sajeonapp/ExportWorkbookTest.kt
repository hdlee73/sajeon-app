package com.hdlee73.sajeonapp

import org.junit.Assert.assertTrue
import org.junit.Test

class ExportWorkbookTest {
    @Test fun ankiCsvUsesWordAsFrontAndMeaningAndExampleAsBack() {
        val entry = WordEntry(word = "keep away", ipa = "", korean = "[구동사] 가까이 오지 않게 하다",
            english = "To prevent something from coming near.", examples = "Keep away from the fire.\t불 가까이에 가지 마세요.")
        val csv = ExportWorkbook.make(listOf(entry), 4).toString(Charsets.UTF_8)
        assertTrue(csv.startsWith("\uFEFF앞면,뒷면"))
        assertTrue(csv.contains("\"keep away\""))
        assertTrue(csv.contains("가까이 오지 않게 하다"))
        assertTrue(csv.contains("Keep away from the fire.<br>불 가까이에 가지 마세요."))
    }
}
