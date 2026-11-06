package org.dshwatch.app

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

object ChatText {
    fun format(raw: String): CharSequence {
        val out = SpannableStringBuilder()
        var code = false
        for ((index, source) in raw.split('\n').withIndex()) {
            if (index > 0) out.append('\n')
            if (source.startsWith("```")) { code = !code; continue }
            val start = out.length
            if (code) {
                out.append(source)
                if(out.length>start) {
                    out.setSpan(TypefaceSpan("monospace"),start,out.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(BackgroundColorSpan(WhaleTheme.SELECTED),start,out.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                continue
            }
            val heading = source.startsWith("#") && source.contains(' ')
            val line = if (heading) source.substringAfter(' ') else source.replace(Regex("^[-*] "),"• ")
            val pattern = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
            var cursor = 0
            for (match in pattern.findAll(line)) {
                out.append(line.substring(cursor,match.range.first))
                val offset = out.length
                val bold = match.groupValues[1].isNotEmpty()
                out.append(if (bold) match.groupValues[1] else match.groupValues[2])
                out.setSpan(if (bold) StyleSpan(Typeface.BOLD) else TypefaceSpan("monospace"),offset,out.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                cursor = match.range.last+1
            }
            out.append(line.substring(cursor))
            if (heading && out.length > start) {
                out.setSpan(StyleSpan(Typeface.BOLD),start,out.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(1.08f),start,out.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }
}
