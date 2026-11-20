package jp.local.forgedesk

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class TagSeparatorTest {
    private fun expect(old:String, caret:TextRange, tag:String, completion:Boolean, before:String, after:String="") {
        val result=insertPrompt(TextFieldValue(old,caret),tag,completion)
        assertEquals(before+after,result.text)
        assertEquals(TextRange(before.length),result.selection)
    }
    @Test fun liveTagAtEndIsReadyForNextTag() {
        expect("blue, re",TextRange(8),"red fox",true,"blue, red fox, ")
        expect("",TextRange(0),"red fox",false,"red fox, ")
    }
    @Test fun existingSeparatorsAndHorizontalWhitespaceAreReused() {
        expect("blue, re  ,\t next",TextRange(8),"red fox",true,"blue, red fox, ","next")
        expect("blue,  next",TextRange(6),"red fox",false,"blue, red fox, ","next")
        expect("blue, re,  ",TextRange(8),"red fox",true,"blue, red fox, ")
    }
    @Test fun selectedSpanAndUntouchedNewlinesSurvive() {
        expect("layer\n  green\r\n\nnext",TextRange(8,13),"red fox",false,"layer\n  red fox, ","\r\n\nnext")
        expect("abc, def",TextRange(1),"blue",false,"abc, blue, ","def")
        expect("one\n",TextRange(4),"red",false,"one\nred, ")
    }
    @Test fun completeSavedSnippetKeepsInternalSyntaxAndExistingTrailingComma() {
        expect("",TextRange(0),"(red:1.2), warm,",false,"(red:1.2), warm, ")
        expect("edge\\,",TextRange(6),"blue",false,"edge\\,, blue, ")
    }
    @Test fun existingTagSelectionAndRawLoraKeepTheirEstablishedBehavior() {
        val s="red fox, tail"
        val result=insertPrompt(TextFieldValue(s,TextRange(s.length)),"red_fox",false)
        assertEquals(s,result.text)
        assertEquals(TextRange(0,7),result.selection)
        expect("sky",TextRange(3),"<lora:some_name:0.8>",false,"sky, <lora:some_name:0.8>")
    }
}
