package com.hdlee73.sajeonapp

/** Reject single words and dictionary fragments; preserve complete example sentences. */
internal object SentenceExamples {
    fun isSentence(text: String): Boolean {
        val clean = text.trim().trimEnd('"', '\u201d', '\u2019', '\'')
        return clean.lastOrNull() in listOf('.', '!', '?') &&
            Regex("[A-Za-z]+(?:'[A-Za-z]+)?").findAll(clean).count() >= 3
    }
    fun clean(stored: String, word: String): String {
        val sentences = stored.lines().filter { isSentence(it.substringBefore('\t')) }
        return sentences.joinToString("\n").ifBlank {
            "I heard “$word” in a conversation today.\t오늘 대화에서 “$word”라는 표현을 들었습니다."
        }
    }
}
