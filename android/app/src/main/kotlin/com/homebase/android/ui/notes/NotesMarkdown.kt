package com.homebase.android.ui.notes

// Pure (Compose-free) markdown parsing + URL allowlisting for note content. Kept apart
// from NotesScreen.kt so it can be unit-tested on the JVM (see NotesMarkdownTest). The
// rendering of these blocks/spans lives in NotesScreen.kt's MarkdownText / inlineSpans.

internal sealed interface MdBlock {
    data class Heading2(val text: String) : MdBlock
    data class Heading3(val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Image(val alt: String, val src: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class BulletList(val items: List<String>) : MdBlock
    data class NumberedList(val items: List<String>) : MdBlock
}

// `![alt](src)` — pulled out of paragraph text and rendered as its own block
// (Compose can't cleanly inline an arbitrary-size image inside a Text run).
internal val ImageRe = Regex("""!\[([^\]]*)]\(([^)\s]+)\)""")

// Links/images may only point at http(s) or mailto targets; anything else
// (javascript:, data:, relative paths the app can't open) renders as plain text.
private val SafeLinkRe = Regex("^(https?|mailto):", RegexOption.IGNORE_CASE)
internal fun isSafeLinkUrl(url: String): Boolean = SafeLinkRe.containsMatchIn(url.trim())

/** Line-by-line markdown block parser. Robust to plain text (→ paragraphs). */
internal fun parseMarkdown(md: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = md.replace("\r\n", "\n").replace("\r", "\n").split("\n")

    val paragraph = StringBuilder()
    var bullets: MutableList<String>? = null
    var numbers: MutableList<String>? = null

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        paragraph.setLength(0)
        if (text.isEmpty()) return
        // Split out any ![alt](src) into standalone Image blocks; the text around them
        // stays as paragraphs (an inserted image sits on its own line anyway).
        var last = 0
        for (m in ImageRe.findAll(text)) {
            val pre = text.substring(last, m.range.first).trim()
            if (pre.isNotEmpty()) blocks.add(MdBlock.Paragraph(pre))
            blocks.add(MdBlock.Image(alt = m.groupValues[1].trim(), src = m.groupValues[2].trim()))
            last = m.range.last + 1
        }
        val tail = text.substring(last).trim()
        if (tail.isNotEmpty()) blocks.add(MdBlock.Paragraph(tail))
    }
    fun flushBullets() {
        bullets?.let { if (it.isNotEmpty()) blocks.add(MdBlock.BulletList(it)) }
        bullets = null
    }
    fun flushNumbers() {
        numbers?.let { if (it.isNotEmpty()) blocks.add(MdBlock.NumberedList(it)) }
        numbers = null
    }
    fun flushAll() { flushParagraph(); flushBullets(); flushNumbers() }

    for (raw in lines) {
        val trimmed = raw.trim()
        when {
            trimmed.isEmpty() -> flushAll()

            trimmed.startsWith("### ") -> {
                flushAll(); blocks.add(MdBlock.Heading3(trimmed.removePrefix("### ").trim()))
            }
            trimmed.startsWith("## ") -> {
                flushAll(); blocks.add(MdBlock.Heading2(trimmed.removePrefix("## ").trim()))
            }
            trimmed.startsWith("# ") -> {
                flushAll(); blocks.add(MdBlock.Heading2(trimmed.removePrefix("# ").trim()))
            }
            trimmed.startsWith("> ") -> {
                flushParagraph(); flushBullets(); flushNumbers()
                blocks.add(MdBlock.Quote(trimmed.removePrefix("> ").trim()))
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushParagraph(); flushNumbers()
                val list = bullets ?: mutableListOf<String>().also { bullets = it }
                list.add(trimmed.substring(2).trim())
            }
            isOrderedItem(trimmed) -> {
                flushParagraph(); flushBullets()
                val list = numbers ?: mutableListOf<String>().also { numbers = it }
                list.add(trimmed.substringAfter(". ").trim())
            }
            else -> {
                flushBullets(); flushNumbers()
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(trimmed)
            }
        }
    }
    flushAll()
    return blocks
}

/** True for lines like "1. text" / "12. text". */
private fun isOrderedItem(line: String): Boolean {
    val dot = line.indexOf(". ")
    if (dot <= 0) return false
    return line.substring(0, dot).all { it.isDigit() }
}
