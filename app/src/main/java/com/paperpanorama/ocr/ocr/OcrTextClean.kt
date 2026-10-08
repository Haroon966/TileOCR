package com.paperpanorama.ocr.ocr

/**
 * Strip markdown noise from OCR block / page text before painting.
 * Pure Kotlin — JVM unit-testable.
 */
object OcrTextClean {

    fun stripMarkdown(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw.replace("\r\n", "\n").replace('\r', '\n')

        // Fenced code blocks → inner text
        s = s.replace(Regex("```[\\w]*\\n?([\\s\\S]*?)```"), "$1")
        // Images ![alt](url) → alt
        s = s.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        // Links [text](url) → text
        s = s.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        // Headings
        s = s.replace(Regex("(?m)^#{1,6}\\s+"), "")
        // Bold / italic markers
        s = s.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        s = s.replace(Regex("__([^_]+)__"), "$1")
        s = s.replace(Regex("(?<!\\w)\\*([^*]+)\\*(?!\\w)"), "$1")
        s = s.replace(Regex("(?<!\\w)_([^_]+)_(?!\\w)"), "$1")
        // Inline code
        s = s.replace(Regex("`([^`]+)`"), "$1")
        // List markers
        s = s.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        s = s.replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "")
        // Blockquotes
        s = s.replace(Regex("(?m)^>\\s?"), "")
        // Horizontal rules
        s = s.replace(Regex("(?m)^\\s*([-*_]){3,}\\s*$"), "")
        // Table pipes → spaces
        s = s.replace('|', ' ')
        // Collapse blank lines / spaces
        s = s.replace(Regex("[ \\t]+"), " ")
        s = s.replace(Regex(" *\\n *"), "\n")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    /** Non-empty lines after strip (for line packing / markdown fallback strips). */
    fun lines(raw: String): List<String> =
        stripMarkdown(raw)
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * Common Latin OCR name typos (essay pages + plan pages).
     * ponytail: small alias table; ceiling = known misspellings; upgrade = user dictionary.
     */
    fun fixCommonTypos(raw: String): String {
        if (raw.isBlank()) return raw
        return raw
            .replace(Regex("""\bShaab\b"""), "Shoaib")
            .replace(Regex("""\bshaab\b"""), "shoaib")
            .replace(Regex("""\bMahnour\b"""), "Mahnoor")
            .replace(Regex("""\bmahnour\b"""), "mahnoor")
    }

    /**
     * When OCR returns markdown but no blocks: stack paragraphs as full-width strips.
     * Returns normalized [0,1] boxes top→bottom with side margins.
     */
    fun markdownFallbackBlocks(markdown: String): List<OcrBlock> {
        val paras = stripMarkdown(markdown)
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (paras.isEmpty()) {
            val one = stripMarkdown(markdown)
            if (one.isBlank()) return emptyList()
            return listOf(
                OcrBlock("text", one, 0.06f, 0.06f, 0.94f, 0.94f),
            )
        }
        val marginX = 0.06f
        val marginY = 0.05f
        val usable = 1f - 2f * marginY
        val gap = 0.015f
        val n = paras.size
        val slot = (usable - gap * (n - 1).coerceAtLeast(0)) / n
        return paras.mapIndexed { i, text ->
            val top = marginY + i * (slot + gap)
            val bottom = (top + slot).coerceAtMost(1f - marginY)
            OcrBlock("text", text, marginX, top, 1f - marginX, bottom)
        }
    }
}
