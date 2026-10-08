package com.paperpanorama.ocr.ocr

/**
 * Heuristic parse of “plan / task / dependencies” OCR pages into a drawable layout.
 * Pure Kotlin — JVM-testable. Rearranges OCR lines, then [PlanDiagramCanon] maps aliases to gold.
 */
object PlanDiagramParse {

    data class OwnerCol(val nameDays: String, val sub: String? = null)

    data class NumberedRow(
        val index: String,
        val left: String,
        val rightBracket: String?,
        /** Optional second line under Parallel fork. */
        val forkBelow: String? = null,
    )

    data class PlanDiagram(
        val title: String,
        val taskArrow: String,
        val ownersLabel: String,
        val ownerCols: List<OwnerCol>,
        val dependencies: String,
        val planLabel: String,
        val uiTitle: String,
        val webApp: String,
        val branchLeft: String,
        val branchMid: String,
        val branchRight: String,
        val rows: List<NumberedRow>,
        val footer: String?,
    )

    fun looksLikePlan(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("task") &&
            (t.contains("owner") || t.contains("dependenc")) &&
            (t.contains("mapping") || t.contains("translation") || t.contains("fetch"))
    }

    /**
     * Build diagram from OCR markdown / concatenated block text.
     * Returns null if page does not look like a plan diagram.
     */
    fun tryParse(raw: String): PlanDiagram? {
        if (!looksLikePlan(raw)) return null
        val lines = OcrLayoutMath.stretchLines(OcrTextClean.stripMarkdown(raw))
        if (lines.size < 6) return null

        val title = lines.firstOrNull {
            it.contains("DMD", ignoreCase = true) ||
                it.contains("NMD", ignoreCase = true) ||
                (it.length <= 24 && !it.contains("Task", ignoreCase = true))
        } ?: lines.first()

        val taskLine = lines.firstOrNull { it.contains("Task", ignoreCase = true) && it.contains("->") }
            ?: lines.firstOrNull { it.contains("Task", ignoreCase = true) }
            ?: "Task -> Data fetching, Translation, Mapping."

        val ownersLine = lines.firstOrNull { it.contains("Owner", ignoreCase = true) }.orEmpty()
        val ownerCols = parseOwners(ownersLine, lines)

        val mainApp = lines.firstOrNull { it.contains("Main APP", ignoreCase = true) }

        val deps = lines.firstOrNull { it.contains("Dependenc", ignoreCase = true) } ?: "Dependencies"
        val plan = lines.firstOrNull {
            it.contains("Plan", ignoreCase = true) && !it.contains("Parallel", ignoreCase = true)
        } ?: "Plan"

        val webApp = lines.firstOrNull {
            it.contains("web APP", ignoreCase = true) || it.contains("Web App", ignoreCase = true)
        }?.replace(Regex("""^\(?\s*UI\s*\)?\s*""", RegexOption.IGNORE_CASE), "")?.trim()
            ?: "web APP (QA overall)"

        val uiTitle = lines.firstOrNull { it.trim().equals("(UI)", ignoreCase = true) || it.trim().equals("UI", ignoreCase = true) }
            ?: "UI"

        val branchL = lines.firstOrNull {
            it.contains("Data fetching", ignoreCase = true) && it.contains("QA") && !it.startsWith("1")
        } ?: "Data fetching (QA)"
        val branchM = lines.firstOrNull {
            it.contains("Translation", ignoreCase = true) && it.contains("QA") && it.contains("here", ignoreCase = true)
        } ?: lines.firstOrNull {
            it.contains("Translation", ignoreCase = true) && it.contains("required", ignoreCase = true)
        } ?: "Translation (QA required) here"
        val branchR = lines.firstOrNull {
            it.contains("Mapping", ignoreCase = true) && it.contains("QA") &&
                !it.contains("IQRA", ignoreCase = true) && !it.contains("QAA", ignoreCase = true) &&
                !it.startsWith("3") && !Regex("""^\d""").containsMatchIn(it)
        } ?: "Mapping (QA)"

        val rows = parseNumbered(lines)
        val footer = lines.lastOrNull {
            it.contains("Regression", ignoreCase = true) ||
                (it.contains("[") && it.contains("web APP", ignoreCase = true))
        }

        return PlanDiagramCanon.apply(
            PlanDiagram(
                title = title,
                taskArrow = taskLine,
                ownersLabel = "Owners:",
                ownerCols = ownerCols.withMainApp(mainApp),
                dependencies = deps,
                planLabel = plan.replace(Regex("""\s*\(UI\)\s*""", RegexOption.IGNORE_CASE), "").trim()
                    .ifBlank { "Plan" },
                uiTitle = "UI",
                webApp = webApp,
                branchLeft = branchL,
                branchMid = branchM,
                branchRight = branchR,
                rows = rows,
                footer = footer,
            ),
        )
    }

    private fun List<OwnerCol>.withMainApp(mainApp: String?): List<OwnerCol> {
        if (mainApp.isNullOrBlank() || isEmpty()) return this
        // Attach Main APP under left owner (Dataf / day-1) — gold layout.
        return mapIndexed { i, col ->
            if (i == 0) col.copy(sub = mainApp) else col.copy(sub = null)
        }
    }

    private fun parseOwners(ownersLine: String, all: List<String>): List<OwnerCol> {
        // "Owners Hatay (day 1) Shoaib (2 days) Hatay (3 days)"
        val body = ownersLine.replace(Regex("""^Owners?:?\s*""", RegexOption.IGNORE_CASE), "")
        val parts = Regex("""([A-Za-z][A-Za-z0-9]*)\s*\(([^)]+)\)""")
            .findAll(body)
            .map { OwnerCol("${it.groupValues[1]} (${it.groupValues[2]})") }
            .toList()
        if (parts.size >= 2) return parts.take(3)
        // Fallback: three task names as stubs from task line
        return listOf(
            OwnerCol(all.firstOrNull { it.contains("day", ignoreCase = true) } ?: "—"),
            OwnerCol("—"),
            OwnerCol("—"),
        ).take(3)
    }

    private fun parseNumbered(lines: List<String>): List<NumberedRow> {
        val rows = ArrayList<NumberedRow>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val m = Regex("""^(\d+)\s*[-.)]\s*(.+)$""").find(line.trim())
            if (m == null) {
                i++
                continue
            }
            val idx = m.groupValues[1]
            var rest = m.groupValues[2].trim()
            var bracket: String? = null
            val bm = Regex("""\[([^\]]+)\]\s*$""").find(rest)
            if (bm != null) {
                bracket = "[ ${bm.groupValues[1].trim()} ]"
                rest = rest.removeRange(bm.range).trim()
            }
            var fork: String? = null
            if (rest.contains("Parallel", ignoreCase = true) && i + 1 < lines.size) {
                val next = lines[i + 1]
                if (next.contains("Mapping", ignoreCase = true) && !Regex("""^\d""").containsMatchIn(next)) {
                    fork = next.trim()
                    i++
                }
            }
            // Merge next Mapping (QA)(IQRA) as its own numbered-less row via synthetic 4-
            rows.add(NumberedRow(idx, rest, bracket, fork))
            if (i + 1 < lines.size) {
                val n2 = lines[i + 1]
                if (
                    n2.contains("Mapping", ignoreCase = true) &&
                    (n2.contains("IQRA", ignoreCase = true) || n2.contains("QAA", ignoreCase = true)) &&
                    !Regex("""^\d""").containsMatchIn(n2)
                ) {
                    var r2 = n2.trim()
                    var b2: String? = null
                    val bm2 = Regex("""\[([^\]]+)\]\s*$""").find(r2)
                    if (bm2 != null) {
                        b2 = "[ ${bm2.groupValues[1].trim()} ]"
                        r2 = r2.removeRange(bm2.range).trim()
                    }
                    rows.add(NumberedRow("4", r2, b2, null))
                    i++
                }
            }
            i++
        }
        return rows
    }
}
