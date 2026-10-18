package com.elthon.broexe.voice

import org.junit.Assert.assertTrue
import org.junit.Test

class ToolboxTest {

    @Test
    fun promptBlockNamesEveryTool() {
        val block = Toolbox.promptBlock()
        for (tool in Toolbox.all) {
            assertTrue("names ${tool.name}", block.contains(tool.name))
        }
    }

    @Test
    fun promptBlockClosesTheDoorOnInventedAbilities() {
        val block = Toolbox.promptBlock()
        assertTrue(block.contains("no others", ignoreCase = true))
        assertTrue(block.contains("Never claim", ignoreCase = true))
    }

    @Test
    fun promptBlockIsStable() {
        assertTrue(Toolbox.promptBlock() == Toolbox.promptBlock())
    }
}
