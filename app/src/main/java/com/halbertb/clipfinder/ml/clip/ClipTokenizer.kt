package com.halbertb.clipfinder.ml.clip

import android.content.Context
import androidx.core.text.HtmlCompat
import java.io.File
import java.util.LinkedHashMap
import java.util.zip.GZIPInputStream

/**
 * Kotlin port of OpenAI CLIP [simple_tokenizer.py](https://github.com/openai/CLIP/blob/main/clip/simple_tokenizer.py)
 * using the bundled `bpe_simple_vocab_16e6.txt.gz` asset (same as Xenova ONNX export).
 */
class ClipTokenizer(
    context: Context,
    bpeFileOverride: File? = null,
) {
    private val byteEncoder: LinkedHashMap<Int, Char> = bytesToUnicode()
    private val encoder: Map<String, Int>
    private val bpeRanks: Map<Pair<String, String>, Int>
    private val bpeCache = HashMap<String, String>()
    private val pat =
        Regex(
            """<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""",
            RegexOption.IGNORE_CASE,
        )

    init {
        bpeCache["<|startoftext|>"] = "<|startoftext|>"
        bpeCache["<|endoftext|>"] = "<|endoftext|>"

        val bpeInputStream =
            if (bpeFileOverride != null && bpeFileOverride.isFile) {
                bpeFileOverride.inputStream()
            } else {
                context.assets.open(BPE_ASSET_PATH)
            }
        val mergesText =
            GZIPInputStream(bpeInputStream).bufferedReader().use { it.readText() }
        var merges = mergesText.split('\n')
        // Match OpenAI CLIP exactly: merges[1:49152-256-2+1] in Python.
        // Kotlin take() is count-based, while Python slice end is exclusive.
        // Therefore we need 48894 merges, not 48895.
        merges = merges.drop(1).take(49152 - 256 - 2)
        val mergePairs =
            merges.mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val p = line.split(' ')
                if (p.size < 2) return@mapNotNull null
                p[0] to p[1]
            }
        bpeRanks = mergePairs.withIndex().associate { it.value to it.index }

        val vocabList = ArrayList<String>()
        vocabList.addAll(byteEncoder.values.map { it.toString() })
        vocabList.addAll(byteEncoder.values.map { it.toString() + "</w>" })
        for (pair in mergePairs) {
            vocabList.add(pair.first + pair.second)
        }
        vocabList.add("<|startoftext|>")
        vocabList.add("<|endoftext|>")
        encoder = vocabList.withIndex().associate { it.value to it.index }
    }

    fun tokenizeTo77(text: String, contextLength: Int = 77, truncate: Boolean = true): LongArray {
        val sot = encoder.getValue("<|startoftext|>")
        val eot = encoder.getValue("<|endoftext|>")
        val middle = encode(text)
        val tokens = ArrayList<Int>(2 + middle.size)
        tokens.add(sot)
        tokens.addAll(middle)
        tokens.add(eot)
        if (tokens.size > contextLength) {
            if (!truncate) error("Input too long for CLIP context")
            val truncated = tokens.take(contextLength).toMutableList()
            truncated[contextLength - 1] = eot
            return longArrayFromInts(truncated, contextLength)
        }
        return longArrayFromInts(tokens, contextLength)
    }

    private fun longArrayFromInts(tokens: List<Int>, contextLength: Int): LongArray {
        val out = LongArray(contextLength)
        for (i in tokens.indices) {
            if (i >= contextLength) break
            out[i] = tokens[i].toLong()
        }
        return out
    }

    private fun basicClean(text: String): String {
        var t = HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        t = HtmlCompat.fromHtml(t, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        return t.trim()
    }

    private fun whitespaceClean(text: String): String =
        Regex("\\s+").replace(text, " ").trim()

    private fun encode(text: String): List<Int> {
        val bpeTokens = ArrayList<Int>()
        val cleaned = whitespaceClean(basicClean(text)).lowercase()
        for (token in pat.findAll(cleaned).map { it.value }) {
            val tokenBytes = token.encodeToByteArray()
            val mapped = StringBuilder(tokenBytes.size)
            for (b in tokenBytes) {
                mapped.append(byteEncoder[b.toInt() and 0xFF] ?: error("byte not in table"))
            }
            for (piece in bpe(mapped.toString()).split(' ')) {
                bpeTokens.add(encoder[piece] ?: error("Unknown BPE piece: $piece"))
            }
        }
        return bpeTokens
    }

    private fun getPairs(word: List<String>): Set<Pair<String, String>> {
        if (word.size < 2) return emptySet()
        val pairs = HashSet<Pair<String, String>>()
        var prev = word[0]
        for (i in 1 until word.size) {
            val cur = word[i]
            pairs.add(prev to cur)
            prev = cur
        }
        return pairs
    }

    private fun bpe(token: String): String {
        bpeCache[token]?.let { return it }
        val chars = token.dropLast(1).map { it.toString() }.toMutableList()
        chars.add(token.last().toString() + "</w>")
        var word = chars
        var pairs = getPairs(word)
        while (true) {
            if (pairs.isEmpty()) break
            val bigram =
                pairs.minByOrNull { pair -> bpeRanks[pair] ?: Int.MAX_VALUE }
                    ?: break
            if (!bpeRanks.containsKey(bigram)) break
            val first = bigram.first
            val second = bigram.second
            val newWord = ArrayList<String>()
            var i = 0
            while (i < word.size) {
                val rel = if (i < word.size) word.subList(i, word.size).indexOf(first) else -1
                val j = if (rel == -1) -1 else i + rel
                if (j == -1) {
                    newWord.addAll(word.subList(i, word.size))
                    break
                }
                newWord.addAll(word.subList(i, j))
                i = j
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    newWord.add(first + second)
                    i += 2
                } else {
                    newWord.add(word[i])
                    i += 1
                }
            }
            word = newWord
            if (word.size == 1) break
            pairs = getPairs(word)
        }
        val result = word.joinToString(" ")
        bpeCache[token] = result
        return result
    }

    companion object {
        private const val BPE_ASSET_PATH = "clip/bpe_simple_vocab_16e6.txt.gz"

        private fun bytesToUnicode(): LinkedHashMap<Int, Char> {
            val bs = ArrayList<Int>()
            for (c in '!'..'~') bs.add(c.code)
            for (c in '¡'..'¬') bs.add(c.code)
            for (c in '®'..'ÿ') bs.add(c.code)
            val cs = ArrayList<Int>(bs)
            var n = 0
            for (b in 0 until 256) {
                if (b !in bs) {
                    bs.add(b)
                    cs.add(256 + n)
                    n++
                }
            }
            val map = LinkedHashMap<Int, Char>(bs.size)
            for (i in bs.indices) {
                map[bs[i]] = cs[i].toChar()
            }
            return map
        }
    }
}
