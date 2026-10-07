package com.morpheuslab.hksupgrade

import com.morpheusdata.core.AbstractClusterTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ContentSecurityPolicy
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel

/** The "Upgrade Readiness" tab, on HKS clusters only. */
class ReadinessTabProvider extends AbstractClusterTabProvider {

    Plugin plugin
    MorpheusContext morpheus

    ReadinessTabProvider(Plugin plugin, MorpheusContext morpheus) { this.plugin = plugin; this.morpheus = morpheus }

    String getCode() { 'hks-upgrade-tab' }
    String getName() { 'Upgrade' }   // short: Morpheus hides cluster tabs that do not fit, with no menu for them
    MorpheusContext getMorpheus() { morpheus }
    Plugin getPlugin() { plugin }

    Boolean show(ComputeServerGroup cluster, User user, Account account) { Access.isHks(cluster) && Access.canUse(user) }

    HTMLResponse renderTemplate(ComputeServerGroup cluster) {
        Map csrf = Csrf.token()
        CheckState s = Checker.state(cluster.id)
        ReadinessView v = new ReadinessView(clusterId: cluster.id, clusterName: cluster.name, csrfParam: csrf.param, csrfToken: csrf.value,
            running: s?.running as boolean, error: s && !s.running ? s.error : null, result: s?.running ? null : (s?.result ?: s?.previous), previous: s?.previous)
        ViewModel<ReadinessView> m = new ViewModel<>()
        m.object = v
        getRenderer().renderTemplate('hbs/cluster/readiness-tab', m)
    }

    ContentSecurityPolicy getContentSecurityPolicy() { new ContentSecurityPolicy(connectSrc: "'self'") }
}
