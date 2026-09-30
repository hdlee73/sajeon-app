package com.hdlee73.sajeonapp

import org.junit.Assert.*
import org.junit.Test

class StudyMeaningsTest {
    @Test fun limitsSixNumberedSensesToFour() {
        val original = (1..6).joinToString("\n") { "$it. 뜻 $it" }
        assertEquals((1..4).joinToString("\n") { "$it. 뜻 $it" }, StudyMeanings.limit(original))
    }
    @Test fun preservesPartOfSpeechSynonymsAndSenseOrder() {
        val text = "[형용사]\n1. 즉흥적인, 계획하지 않은\n2. 자발적인\n3. 자연 발생적인"
        assertEquals(text, StudyMeanings.limit(text))
    }
    @Test fun excludesContinuationOfOmittedSenses() {
        val text = "1. one\n2. two\n3. three\n4. four\n5. five\ncontinuation of fifth"
        assertEquals("1. one\n2. two\n3. three\n4. four", StudyMeanings.limit(text))
    }
    @Test fun normalizesBothLanguagesBeforeSaveAndExport() {
        val e = WordEntry(word="test", ipa="", korean=(1..6).joinToString("\n") { "$it. 의미" },
            english=(1..6).joinToString("\n") { "$it. meaning" }, examples="An example.\t예문")
        assertEquals(4, e.studyVersion().korean.lines().size)
        assertEquals(4, e.studyVersion().english.lines().size)
        assertEquals(e.examples, e.studyVersion().examples)
    }
}
