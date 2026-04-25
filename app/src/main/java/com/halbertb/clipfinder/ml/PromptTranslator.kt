package com.halbertb.clipfinder.ml

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Fast/reliable multilingual support path:
 * detect prompt language, translate to English with ML Kit, then feed CLIP English text pipeline.
 */
class PromptTranslator {
    private val languageIdClient = LanguageIdentification.getClient()
    private val translators = ConcurrentHashMap<String, Translator>()

    suspend fun toEnglish(input: String): String = withContext(Dispatchers.IO) {
        val text = input.trim()
        if (text.isBlank()) return@withContext text

        val languageTag = runCatching { Tasks.await(languageIdClient.identifyLanguage(text)) }.getOrNull()
        if (languageTag.isNullOrBlank() || languageTag == "und" || languageTag == "en") {
            return@withContext text
        }

        val sourceLang = TranslateLanguage.fromLanguageTag(languageTag) ?: return@withContext text
        val key = "$sourceLang->${TranslateLanguage.ENGLISH}"
        val translator =
            translators.getOrPut(key) {
                val options =
                    TranslatorOptions.Builder()
                        .setSourceLanguage(sourceLang)
                        .setTargetLanguage(TranslateLanguage.ENGLISH)
                        .build()
                Translation.getClient(options)
            }

        return@withContext try {
            Tasks.await(translator.downloadModelIfNeeded())
            Tasks.await(translator.translate(text))
        } catch (_: Exception) {
            text
        }
    }

    fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
        languageIdClient.close()
    }
}

