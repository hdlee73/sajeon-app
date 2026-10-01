package com.hdlee73.sajeonapp

/** Reject single words and dictionary fragments; preserve complete example sentences. */
internal object SentenceExamples {
    fun isSentence(text: String): Boolean {
        val clean = text.trim().trimEnd('"', '\u201d', '\u2019', '\'')
        return clean.lastOrNull() in listOf('.', '!', '?') &&
            Regex("[A-Za-z]+(?:'[A-Za-z]+)?").findAll(clean).count() >= 3
    }
    /** Keep real example sentences only; older versions stored a placeholder sentence. */
    fun clean(stored: String): String =
        stored.lines().filter { line ->
            val english = line.substringBefore('\t')
            isSentence(english) && !(english.startsWith("I heard “") && english.endsWith("in a conversation today."))
        }.joinToString("\n")
}
