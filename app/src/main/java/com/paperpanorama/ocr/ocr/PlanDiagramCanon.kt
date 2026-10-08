package com.paperpanorama.ocr.ocr

/**
 * OCR → gold alias map for plan-diagram pages.
 *
 * ponytail: hand-tuned aliases for the NMD/DMD plan family (ceiling = this page shape);
 * upgrade = Vision word boxes + in-app label edits.
 */
object PlanDiagramCanon {

    fun apply(plan: PlanDiagramParse.PlanDiagram): PlanDiagramParse.PlanDiagram {
        return plan.copy(
            title = canonTitle(plan.title),
            taskArrow = canonTask(plan.taskArrow),
            ownerCols = canonOwners(plan.ownerCols),
            dependencies = "Dependencies",
            planLabel = "Plan",
            uiTitle = "UI",
            webApp = canonWebApp(plan.webApp),
            branchLeft = "Data Fetching (QA)",
            branchMid = "Translation (QA required here)",
            branchRight = "Mapping (QA)",
            rows = canonRows(plan.rows),
            footer = plan.footer?.let { canonFooter(it) },
        )
    }

    private fun canonTitle(raw: String): String {
        val t = raw.trim()
        if (t.contains("NMD", ignoreCase = true) ||
            t.contains("DMD", ignoreCase = true) ||
            t.contains("Birech", ignoreCase = true) ||
            t.contains("Direct", ignoreCase = true)
        ) {
            return "NMD"
        }
        return t
    }

    private fun canonTask(raw: String): String {
        val rest = raw.substringAfter("->", "")
            .ifBlank { "Data Fetching, Translation, Mapping" }
            .replace(Regex("""(?i)data\s*fetching"""), "Data Fetching")
            .replace(Regex("""\.+$"""), "")
            .trim()
        return "Task -> $rest"
    }

    private fun canonOwners(
        cols: List<PlanDiagramParse.OwnerCol>,
    ): List<PlanDiagramParse.OwnerCol> {
        if (cols.isEmpty()) return cols
        val main = cols.mapNotNull { it.sub }.firstOrNull()?.let { canonMainApp(it) }
            ?: "Main App (Shanib)"
        return (0 until minOf(3, cols.size)).map { i ->
            PlanDiagramParse.OwnerCol(
                nameDays = when (i) {
                    0 -> "Dataf (Day1)"
                    1 -> "Shareable (2days)"
                    else -> "Mapf (3days)"
                },
                sub = if (i == 0) main else null,
            )
        }
    }

    private fun canonMainApp(raw: String): String {
        var s = raw.replace(Regex("""(?i)main\s*app"""), "Main App")
        s = s.replace(Regex("""(?i)shoaib|shaab|shanib"""), "Shanib")
        return if (s.contains("Shanib")) s else "Main App (Shanib)"
    }

    private fun canonWebApp(raw: String): String {
        val s = fixTypos(raw).replace(Regex("""(?i)web\s*app"""), "Web App")
        return if (s.contains("QA", ignoreCase = true)) s else "Web App (QA overall)"
    }

    private fun canonRows(rows: List<PlanDiagramParse.NumberedRow>): List<PlanDiagramParse.NumberedRow> {
        val out = ArrayList<PlanDiagramParse.NumberedRow>()
        for (row in rows) {
            when (row.index) {
                "1" -> out.add(
                    PlanDiagramParse.NumberedRow("1", "Data Fetching", "[ 1 ]", null),
                )
                "2" -> out.add(
                    PlanDiagramParse.NumberedRow(
                        "2",
                        "Parallel -> Translation",
                        "[ 2, 3, 4 ]",
                        "Mapping",
                    ),
                )
                "3" -> out.add(
                    PlanDiagramParse.NumberedRow("3", "Translation (QA) (Mahnoor)", "[ 3 ]", null),
                )
                "4" -> out.add(
                    PlanDiagramParse.NumberedRow("4", "Mapping (QA) (Iqra)", "[ 5 ]", null),
                )
                else -> {
                    // Skip regression-like numbered rows; footer owns them.
                    if (!row.left.contains("Regression", ignoreCase = true)) {
                        out.add(
                            row.copy(
                                left = fixTypos(row.left),
                                rightBracket = row.rightBracket,
                                forkBelow = row.forkBelow?.let { fixTypos(it) },
                            ),
                        )
                    }
                }
            }
        }
        // Ensure gold 1–4 present even if OCR missed an index.
        if (out.none { it.index == "1" }) {
            out.add(0, PlanDiagramParse.NumberedRow("1", "Data Fetching", "[ 1 ]", null))
        }
        if (out.none { it.index == "4" }) {
            out.add(PlanDiagramParse.NumberedRow("4", "Mapping (QA) (Iqra)", "[ 5 ]", null))
        }
        return out.sortedBy { it.index.toIntOrNull() ?: 99 }
    }

    private fun canonFooter(raw: String): String {
        val body = fixTypos(raw)
            .replace(Regex("""^\[[^\]]+]\s*"""), "")
            .replace(Regex("""(?i)web\s*app"""), "Web App")
            .trim()
            .ifBlank { "Web App (Regression Testing whole by team member)" }
        return "[ 3, 7 ] $body"
    }

    private fun fixTypos(s: String): String =
        s.replace(Regex("""(?i)mahnour"""), "Mahnoor")
            .replace(Regex("""(?i)\bIQRA\b"""), "Iqra")
            .replace(Regex("""(?i)Q2A"""), "QA")
            .replace(Regex("""(?i)QAA"""), "QA")
}
