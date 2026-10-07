package com.morpheuslab.hksupgrade

import groovy.json.JsonOutput

/** What the tab and the printable page show. */
class ReadinessView {
    Long clusterId
    String clusterName
    String csrfParam
    String csrfToken
    boolean running
    String error
    Map result
    Map previous

    static final Map<String, String> LABELS = [block: 'Blocker', warn: 'Warning', pass: 'Passed', info: 'Info']

    boolean getHasResult() { result != null }
    boolean getNeverChecked() { !result && !running }
    boolean getHasError() { error as boolean }
    String getStepsJson() { JsonOutput.toJson(Checker.STEPS) }

    String getVersion() { result?.version }
    String getTarget() { result?.target }
    String getVerdict() { result?.verdict }
    String getCheckedAt() { result?.checkedAt ? java.time.format.DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm').withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(result.checkedAt as long)) + ' UTC' : '' }
    String getCheckedAgo() { ago(result?.checkedAt as Long) }
    String getCheckedBy() { result?.user ?: '' }

    List<Map> getChecks() { (result?.checks ?: []) as List<Map> }
    int getBlockers() { checks.count { it.status == 'block' } as int }
    int getWarnings() { checks.count { it.status == 'warn' } as int }
    int getPassed() { checks.count { it.status == 'pass' } as int }

    String getVerdictTitle() {
        verdict == 'block' ? "Not ready to upgrade to ${target}" : verdict == 'warn' ? "Ready to upgrade to ${target}, with warnings" : "Ready to upgrade to ${target}"
    }
    String getVerdictText() {
        verdict == 'block' ? "Fix ${blockers == 1 ? 'the blocker' : "the ${blockers} blockers"} first. ${warnings ? "Also look at ${warnings} warning${warnings == 1 ? '' : 's'}." : ''}" :
            verdict == 'warn' ? "Nothing blocks the upgrade. Read ${warnings == 1 ? 'the warning' : "the ${warnings} warnings"} so there are no surprises." :
                'Every check passed. Start the upgrade from Actions > Upgrade Cluster.'
    }

    List<Map> getRows() {
        checks.withIndex().collect { Map c, int i ->
            c + [index: i, label: LABELS[c.status], hasItems: (c.items as List)?.size() > 0, hasWhy: c.why as boolean, hasFix: c.fix as boolean,
                 countText: (c.items as List)?.size() == 1 ? '1 item' : "${(c.items as List)?.size() ?: 0} items".toString()]
        }
    }

    /** Checks whose result changed since the previous check. */
    List<Map> getChanges() {
        if (!previous || previous.is(result)) return []
        Map<String, String> before = (previous.checks as List<Map>).collectEntries { [(it.id): it.status] }
        checks.findAll { before[it.id] && before[it.id] != it.status }
              .collect { [title: it.title, from: LABELS[before[it.id]], to: LABELS[it.status], better: rank(it.status as String) > rank(before[it.id])] }
    }
    boolean getHasChanges() { changes as boolean }

    static int rank(String status) { ['block', 'warn', 'info', 'pass'].indexOf(status) }

    String getRowCss() {
        rows.collect { Map r ->
            String on = "#ur-c-${r.index}:checked~.ur-list"
            "${on} .ur-detail-${r.index}{display:block}${on} .ur-row-${r.index}{background:rgba(1,169,130,.08)}" +
                "${on} .ur-row-${r.index} .ur-open{display:none}${on} .ur-row-${r.index} .ur-shut{display:flex!important}"
        }.join('')
    }

    static String ago(Long ms) {
        if (!ms) return ''
        long s = Math.max(0L, (long) ((System.currentTimeMillis() - ms) / 1000))
        s < 60 ? 'just now' : s < 3600 ? "${(long) (s / 60)}m ago" : s < 86400 ? "${(long) (s / 3600)}h ago" : "${(long) (s / 86400)}d ago"
    }
}
