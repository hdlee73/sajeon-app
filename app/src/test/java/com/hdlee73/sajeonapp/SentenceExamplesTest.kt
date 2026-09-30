package com.hdlee73.sajeonapp

import org.junit.Assert.*
import org.junit.Test

class SentenceExamplesTest {
    @Test fun rejectsFragments() {
        assertFalse(SentenceExamples.isSentence("a spontaneous decision"))
        assertFalse(SentenceExamples.isSentence("pay off"))
        assertFalse(SentenceExamples.isSentence("Hello!"))
    }
    @Test fun acceptsSentencesAndQuestions() {
        assertTrue(SentenceExamples.isSentence("We finally paid off our mortgage."))
        assertTrue(SentenceExamples.isSentence("Could you look after my cat?"))
        assertTrue(SentenceExamples.isSentence("He said, \"Please take off your shoes.\""))
    }
    @Test fun cleansSavedFragmentsAndKeepsTranslations() {
        assertEquals("We paid off the loan.\t대출을 다 갚았다.", SentenceExamples.clean("a loan\nWe paid off the loan.\t대출을 다 갚았다.", "pay off"))
        assertTrue(SentenceExamples.isSentence(SentenceExamples.clean("a loan", "loan").substringBefore('\t')))
    }
}
