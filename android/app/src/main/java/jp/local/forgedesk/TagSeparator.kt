package jp.local.forgedesk

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

private fun String.endsWithSeparator():Boolean {
    if(lastOrNull()!=',')return false
    var index=lastIndex-1
    while(index>=0 && this[index]=='\\')index--
    return (lastIndex-1-index)%2==0
}

/** Insert one dictionary tag at its replacement span, leaving the caret ready for the next tag. */
internal fun insertDelimitedTag(before:String, tag:String, after:String):TextFieldValue {
    val left=before.trimEnd(' ','\t')
    val prefix=when {
        left.isEmpty() || left.endsWith('\n') || left.endsWith('\r') -> before
        left.endsWithSeparator() -> "$left "
        else -> "$left, "
    }
    val cleaned=tag.trimEnd(' ','\t')
    val body=if(cleaned.endsWithSeparator())cleaned.dropLast(1) else cleaned
    var next=0
    while(next<after.length && (after[next]==' ' || after[next]=='\t'))next++
    if(after.getOrNull(next)==',') {
        next++
        while(next<after.length && (after[next]==' ' || after[next]=='\t'))next++
    }
    val inserted="$prefix$body, "
    return TextFieldValue(inserted+after.substring(next),TextRange(inserted.length))
}
