package com.villamediana.novelo.ui.library

import com.villamediana.novelo.settings.NoveloSettings
import com.villamediana.novelo.ui.Filter
import org.junit.Assert.assertEquals
import org.junit.Test

/** The library's tabs: the usual order, hers, the ones hidden, never none. */
class TabOrderTest {
    @Test
    fun theUsualOrderHersAndTheHiddenOnes() {
        assertEquals(listOf(Filter.TODAY, Filter.TASKS, Filter.DIARY, Filter.NOTES, Filter.MEDS, Filter.GALLERY), TabOrder.visible(NoveloSettings()))
        // Hers (a tab missing from what she set goes at the end; an unknown name is ignored).
        val mine = NoveloSettings(tabOrder = "meds,today,nada,tasks")
        assertEquals(listOf(Filter.MEDS, Filter.TODAY, Filter.TASKS, Filter.DIARY, Filter.NOTES, Filter.GALLERY), TabOrder.order(mine))
        assertEquals(listOf(Filter.MEDS, Filter.TODAY, Filter.TASKS, Filter.NOTES), TabOrder.visible(mine.copy(tabsHidden = "diary,gallery")))
        // All hidden: the first stays.
        assertEquals(listOf(Filter.MEDS), TabOrder.visible(mine.copy(tabsHidden = "today,tasks,diary,notes,meds,gallery")))
    }
}
