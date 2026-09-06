package com.marketplace.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the incident runbooks committed at
 * {@code docs/observability/runbooks.md} (Phase C of the gap-closure map —
 * closes the declared debt in {@code docs/observability/slo.md} §6):
 *
 * <ul>
 *   <li>1:1 coverage — every {@code alert} name in
 *       {@code monitoring/prometheus-rules/marketplace-alerts.yml} must have
 *       exactly one {@code ## <AlertName>} section in the runbooks, and every
 *       alert-named section must belong to a rule (an alert without a runbook
 *       would fire into an unhandled incident; a runbook without an alert is
 *       dead documentation drifting from the contract);</li>
 *   <li>structural completeness — every alert section carries the five
 *       mandatory subsections (meaning / first triage / diagnosis and
 *       remediation / verification and closure / escalation) so a responder
 *       under pressure always finds the same skeleton;</li>
 *   <li>contract cross-references — every alert section quotes the severity
 *       declared by the rule and links the exact SLO anchor the rule's
 *       {@code annotations.slo} points to, so the rules file, slo.md and the
 *       runbooks cannot drift apart silently.</li>
 * </ul>
 */
class RunbooksFilesTest {

    private static final Path RULES_FILE =
            Path.of("..", "monitoring", "prometheus-rules", "marketplace-alerts.yml");
    private static final Path RUNBOOKS_FILE =
            Path.of("..", "docs", "observability", "runbooks.md");

    /** The five mandatory subsection headings of every alert runbook. */
    private static final List<String> MANDATORY_SUBSECTIONS = List.of(
            "### ماذا يعني هذا الإنذار",
            "### التثليث الأول — أول خمس دقائق",
            "### التشخيص والمعالجة",
            "### التحقق والإغلاق",
            "### التصعيد"
    );

    private static final Pattern SECTION_HEADING = Pattern.compile("(?m)^## (\\S+)\\s*$");

    @Test
    @SuppressWarnings("unchecked")
    void everyAlertRuleHasOneCompleteRunbookSection() throws IOException {
        String raw = Files.readString(RULES_FILE);
        String withoutComments = raw.replaceAll("(?m)^\\s*#.*$", "");
        Map<String, Object> root = new Yaml().load(withoutComments);

        assertThat(root).isNotNull();
        List<Map<String, Object>> groups = (List<Map<String, Object>>) root.get("groups");

        Map<String, String> severityByName = new LinkedHashMap<>();
        Map<String, String> sloAnchorByName = new LinkedHashMap<>();
        for (Map<String, Object> group : groups) {
            for (Map<String, Object> rule : (List<Map<String, Object>>) group.get("rules")) {
                String alert = (String) rule.get("alert");
                Map<String, Object> labels = (Map<String, Object>) rule.get("labels");
                Map<String, Object> annotations = (Map<String, Object>) rule.get("annotations");
                severityByName.put(alert, (String) labels.get("severity"));
                sloAnchorByName.put(alert, (String) annotations.get("slo"));
            }
        }
        assertThat(severityByName).isNotEmpty();

        String runbooks = Files.readString(RUNBOOKS_FILE);
        Map<String, String> sectionByName = sectionsByName(runbooks);

        assertThat(new TreeMap<>(sectionByName).keySet())
                .as("runbooks sections must match the alert rules 1:1 — "
                        + "missing runbooks: %s ; orphan runbooks: %s",
                        severityByName.keySet().stream()
                                .filter(n -> !sectionByName.containsKey(n)).toList(),
                        sectionByName.keySet().stream()
                                .filter(n -> !severityByName.containsKey(n)).toList())
                .containsExactlyInAnyOrderElementsOf(severityByName.keySet());

        for (Map.Entry<String, String> alert : severityByName.entrySet()) {
            String name = alert.getKey();
            String body = sectionByName.get(name);

            assertThat(body)
                    .as("section '%s' must exist with a non-empty body", name)
                    .isNotBlank();

            for (String mandatory : MANDATORY_SUBSECTIONS) {
                assertThat(body)
                        .as("section '%s' must carry mandatory subsection '%s'", name, mandatory)
                        .contains(mandatory);
            }

            assertThat(body)
                    .as("section '%s' must quote the severity declared by its rule", name)
                    .contains("الخطورة **" + alert.getValue() + "**");

            assertThat(body)
                    .as("section '%s' must link the SLO anchor of its rule's annotations.slo", name)
                    .contains(sloAnchorByName.get(name));
        }
    }

    /**
     * Splits the markdown into top-level sections keyed by their heading word
     * (alert names are single words by the rules-file contract); each value is
     * the heading line plus its body up to the next {@code ## } heading.
     */
    private static Map<String, String> sectionsByName(String markdown) {
        Map<String, String> sections = new HashMap<>();
        Matcher heading = SECTION_HEADING.matcher(markdown);
        int previousStart = -1;
        String previousName = null;
        while (heading.find()) {
            if (previousStart >= 0) {
                sections.put(previousName, markdown.substring(previousStart, heading.start()));
            }
            previousStart = heading.start();
            previousName = heading.group(1);
        }
        if (previousStart >= 0) {
            sections.put(previousName, markdown.substring(previousStart));
        }
        return sections;
    }
}
