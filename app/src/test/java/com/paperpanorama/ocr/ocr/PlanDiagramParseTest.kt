package com.paperpanorama.ocr.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanDiagramParseTest {

    private val sample = """
        Birech DMD
        Task -> Data fetching, Translation, Mapping.
        Owners Hatay (day 1) Shoaib (2 days) Hatay (3 days)
        Main APP (Shoaib)
        Dependencies
        Plan (UI)
        web APP (QA overall)
        Data fetching (QA)
        Translation (QA required) here
        Mapping (QA)
        1- Data fetching [1]
        2- Parallel -> Translation [2]
        Mapping [2, 3, 4]
        3- Translation (Q2A) (Mahnour) [3]
        Mapping (QAA) (IQRA) [5]
        [3, 7] web APP (Regression Testing whole by team member)
    """.trimIndent()

    @Test
    fun detectsPlanPage() {
        assertTrue(PlanDiagramParse.looksLikePlan(sample))
    }

    @Test
    fun mapsOcrAliasesToGoldLabels() {
        val plan = PlanDiagramParse.tryParse(sample)
        assertNotNull(plan)
        requireNotNull(plan)
        assertEquals("NMD", plan.title)
        assertEquals(3, plan.ownerCols.size)
        assertEquals("Dataf (Day1)", plan.ownerCols[0].nameDays)
        assertEquals("Shareable (2days)", plan.ownerCols[1].nameDays)
        assertEquals("Mapf (3days)", plan.ownerCols[2].nameDays)
        assertTrue(plan.ownerCols[0].sub?.contains("Shanib") == true)
        assertTrue(plan.branchMid.contains("QA required here"))
        assertTrue(plan.rows.any { it.left.contains("Parallel") && it.rightBracket == "[ 2, 3, 4 ]" })
        assertTrue(plan.rows.any { it.left.contains("Mahnoor") })
        assertTrue(plan.rows.any { it.left.contains("Iqra") })
        assertTrue(plan.footer?.startsWith("[ 3, 7 ]") == true)
    }
}
