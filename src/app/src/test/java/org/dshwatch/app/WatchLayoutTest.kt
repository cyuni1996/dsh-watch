package org.dshwatch.app

import org.junit.Assert.*
import org.junit.Test

class WatchLayoutTest {
    @Test fun fixedTouchTargetsStayWithinCircleAcrossWatchSizesAndDensities() {
        for(dp in listOf(192,200,227,240,260,320))for(density in listOf(1f,1.5f,2f,3f)) {
            val side=(dp*density).toInt();val m=WatchLayout(side,side,density,true)
            val radius=side/2f-3*density
            for(x in listOf(m.chatHeaderInset+m.chatButton/2f,side-m.chatHeaderInset-m.chatButton/2f))
                assertTrue("$dp header",kotlin.math.hypot(x-side/2f,m.chatTop+m.chatButton/2f-side/2f)+m.chatButton/2f<=radius)
            val cy=side-m.chatBottom-m.modelTouchHeight/2f
            val capOffset=(m.modelWidth-m.modelTouchHeight).coerceAtLeast(0)/2f
            assertTrue("$dp model",kotlin.math.hypot(capOffset,cy-side/2f)+m.modelTouchHeight/2f<=radius+1)
            assertTrue(m.modelWidth>=m.modelTouchHeight)
            val inputBottom=side-m.chatBottom-m.modelTouchHeight-m.chatComposerGap
            assertTrue(kotlin.math.hypot(m.chatComposerWidth/2f,inputBottom-side/2f)<=radius+1)
            assertTrue(m.button/density>=36)
            assertTrue(m.composerWidth>2*m.button+30*density)
            assertTrue(m.chatComposerWidth>2*m.chatButton+30*density)
        }
    }
    @Test fun compactChatReservesAtLeastFortyPercentMoreMessageHeightOnCurrentWatch() {
        val m=WatchLayout(480,480,2f,true)
        val newHeight=m.height-m.chatTop-m.chatButton-m.chatBottom-m.chatComposerHeight
        assertEquals(202,newHeight);assertEquals(96,m.chatHeaderInset)
        assertEquals(28,m.modelVisualHeight);assertEquals(72,m.modelTouchHeight)
        assertTrue(m.chatComposerWidth>=340)
    }

}
