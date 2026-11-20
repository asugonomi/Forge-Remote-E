package jp.local.forgedesk

import org.junit.Assert.*
import org.junit.Test

class PromptOrderTest {
    @Test fun physicalLinesAndBlankRowsSurviveCrossLineDrag() {
        val parser=PromptOrder("\r\none, two\r\n\r\nthree\nfour, (five,\nsix), seven\n")
        assertEquals(listOf(1,2,3,4,5,6,7),parser.lines.map { it.number })
        assertEquals(listOf(emptyList(),listOf(0,1),emptyList(),listOf(2),listOf(3,4),listOf(5),emptyList()),
            parser.lines.map { it.positions })
        val ids=parser.slots.indices.toList()
        assertEquals("\r\nthree, one\r\n\r\ntwo\nfour, (five,\nsix), seven\n",
            parser.apply(movePromptSlot(ids,2,0),2).first)
        val blanks=PromptOrder("a,\n\nb")
        assertEquals("b,\n\na",blanks.apply(listOf(1,0),1).first)
    }
    @Test fun nestedSyntaxAndEscapesRemainWhole() {
        val text="""first,(wide, [frame,a]:1.2),<lora:writer,style:0.75>,escaped\, comma,{red,blue},"warm, cool",last"""
        val order=PromptOrder(text)
        assertEquals(listOf("first","(wide, [frame,a]:1.2)","<lora:writer,style:0.75>","escaped\\, comma","{red,blue}","\"warm, cool\"","last"),
            order.slots.indices.map(order::text))
    }
    @Test fun preserveDelimitersAndOnlyMoveTokenContents() {
        val text="  one,\r\n\ttwo , , three  ,\n"
        val parser=PromptOrder(text)
        val (result,selection)=parser.apply(movePromptSlot(listOf(0,1,2),2,0),2)
        assertEquals("  three,\r\n\tone , , two  ,\n",result)
        assertEquals("three",result.substring(selection.min,selection.max))
    }
    @Test fun stableDuplicateIdentityAndReverse() {
        val parser=PromptOrder("aa, same, x, same")
        val ids=parser.slots.indices.toList()
        val shifted=movePromptSlot(ids,3,0)
        val (written,selection)=parser.apply(shifted,3)
        assertEquals("same, aa, same, x",written)
        assertEquals(0,selection.start)
        assertEquals(parser.source,parser.apply(movePromptSlot(shifted,0,3),3).first)
    }
    @Test fun unchangedSourceWithUnmatchedBracketsAndEmptySlots() {
        for(text in listOf("","  ",",,  ,","", "first, (unfinished,tag", "first, two\nmore, third","猫,\t狐")) {
            val parser=PromptOrder(text)
            assertEquals(text,parser.apply(parser.slots.indices.toList(),-1).first)
        }
    }
    @Test fun backslashPairsReleaseFollowingComma() {
        val parser=PromptOrder("""a\\, b\,c, \(x\), <lora:n:1>""")
        assertEquals(listOf("a\\\\","b\\,c","\\(x\\)","<lora:n:1>"),parser.slots.indices.map(parser::text))
    }
    @Test fun rejectInvalidOrderAndIgnoreOutOfRangeGesture() {
        val parser=PromptOrder("a,b,c")
        val ids=parser.slots.indices.toList()
        assertEquals(ids,movePromptSlot(ids,0,-1)); assertEquals(ids,movePromptSlot(ids,3,0))
        assertThrows(IllegalArgumentException::class.java){parser.apply(listOf(0,0,2),0)}
    }
}
