package com.vpn.server.controller;

import com.vpn.server.service.DiagnosticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Reading side of the fleet's error collection: what is currently breaking on
 * the nodes and in the apps, already grouped, counted and dated.
 *
 * Under /api/v1/admin/** so SecurityConfig's ROLE_ADMIN rule covers it — the
 * reports are unauthenticated going in (a client that cannot log in still has
 * to be able to report that) but must not be readable by anyone going out.
 *
 * The response is deliberately one self-contained JSON document with the
 * totals, the breakdowns and the issues in it, rather than a paginated list
 * of rows: the intended reader is an analysis of "how is this running, does
 * anything need fixing" — including an LLM handed the output as-is — and that
 * reader should not have to make a second call or know the schema to make
 * sense of the first.
 */
@RestController
@RequestMapping("/api/v1/admin/diagnostics")
public class DiagnosticsController {

    private final DiagnosticsService diagnosticsService;

    public DiagnosticsController(DiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    /**
     * @param hours  how far back to look (default 24, i.e. "since yesterday")
     * @param source optional filter: NODE_AGENT, ANDROID, DESKTOP, WEB, SERVER
     * @param limit  maximum number of distinct issues (default 50, capped at 200)
     */
    @GetMapping
    public ResponseEntity<DiagnosticsService.DiagnosticsSummary> summary(
            @RequestParam(required = false, defaultValue = "24") int hours,
            @RequestParam(required = false) String source,
            @RequestParam(required = false, defaultValue = "50") int limit
    ) {
        return ResponseEntity.ok(diagnosticsService.summarize(hours, source, limit));
    }

    /**
     * The same data with a short preamble explaining what the numbers mean —
     * meant to be handed straight to a model as context, so the reader does
     * not have to be told separately that rows are aggregated, that messages
     * are masked, or how far back the window goes.
     */
    @GetMapping("/report")
    public ResponseEntity<?> report(
            @RequestParam(required = false, defaultValue = "24") int hours,
            @RequestParam(required = false) String source,
            @RequestParam(required = false, defaultValue = "50") int limit
    ) {
        DiagnosticsService.DiagnosticsSummary summary = diagnosticsService.summarize(hours, source, limit);
        return ResponseEntity.ok(Map.of(
                "about", String.join(" ",
                        "Errors reported by VPN node agents and client apps over the last",
                        summary.windowHours() + " hours.",
                        "Each entry is one distinct issue, not one occurrence:",
                        "'occurrences' is how many times it was reported and 'reporters' roughly how many",
                        "different machines reported it, so a high occurrence count with one reporter is a",
                        "single machine looping, while many reporters means it is happening to everyone.",
                        "Messages have their variable parts masked (<ip>, <uuid>, <email>, <hex>, # for numbers)",
                        "so that repetitions of the same failure group together.",
                        "'appVersions' lists the builds an issue has been seen on, oldest first."),
                "summary", summary
        ));
    }
}
