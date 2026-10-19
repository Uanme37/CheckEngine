package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.Report;
import com.guiltypotato.checkengine.core.scan.ReportSections;
import com.guiltypotato.checkengine.core.scan.ReportSections.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportSectionsTest {
    private static final List<Finding> FINDINGS = List.of(
            new Finding(Finding.Severity.WARNING, "x", "2 recipe clashes",
                    "Each group below is crafting recipes with exactly the same ingredients but different results:\n\n"
                            + "- Diamond / Emerald - from 2x Dirt\n    datapack cetest\n\n"
                            + "Recipe ids, for KubeJS or CraftTweaker:\n- Diamond: cetest:a, cetest:b",
                    "Remove the extra recipes.", List.of()),
            new Finding(Finding.Severity.INFO, "y", "3 crafting ingredients nothing gives", "Often fine:\n- Ambergris", null,
                    List.of()));

    @Test
    void savedReportReadsBackAsPanels() {
        StringBuilder sb = new StringBuilder("Check Engine data scan\nChecked: 5 recipes\n\n");
        Report.appendFindings(sb, FINDINGS);
        ReportSections.Parsed p = ReportSections.parse(sb.toString());
        assertEquals("0 problems, 1 warning", p.summary());
        assertEquals(2, p.blocks().size());
        ReportSections.Block clash = p.blocks().get(0);
        assertEquals("WARNING", clash.severity());
        assertEquals("2 recipe clashes", clash.title());
        assertEquals(List.of(Kind.TEXT, Kind.ITEM, Kind.MORE, Kind.HEADING, Kind.ITEM, Kind.FIX),
                clash.lines().stream().map(ReportSections.Entry::kind).toList());
        assertEquals("datapack cetest", clash.lines().get(2).text());
    }

    @Test
    void chatNamesTheWarnings() {
        assertEquals("0 problems, 1 warning: 2 recipe clashes", Report.chatSummary(FINDINGS));
        assertEquals("no problems found", Report.chatSummary(List.of()));
    }
}
