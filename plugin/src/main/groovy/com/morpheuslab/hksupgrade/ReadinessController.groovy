package com.morpheuslab.hksupgrade

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeTypeLayout
import com.morpheusdata.model.Permission
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.JsonResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import groovy.util.logging.Slf4j

/** Start a check, report its live status, print the result. */
@Slf4j
class ReadinessController implements PluginController {

    Plugin plugin
    MorpheusContext morpheus

    ReadinessController(Plugin plugin, MorpheusContext morpheus) { this.plugin = plugin; this.morpheus = morpheus }

    String getCode() { 'hks-upgrade-controller' }
    String getName() { 'HKS Upgrade Readiness Controller' }
    MorpheusContext getMorpheus() { morpheus }
    Plugin getPlugin() { plugin }

    List<Route> getRoutes() {
        Permission read = Permission.build(UpgradeReadinessPlugin.PERMISSION, 'read')
        [Route.build('/hks-upgrade/status', 'status', read),
         Route.build('/hks-upgrade/check', 'check', read),
         Route.build('/hks-upgrade/report', 'report', read)]
    }

    def status(ViewModel<Map> model) {
        if (!Access.canUse(model.user)) return JsonResponse.of([error: 'no access'])
        CheckState s = Checker.state(param(model, 'clusterId') as Long)
        JsonResponse.of(s ? s.toMap() : [running: false])
    }

    def check(ViewModel<Map> model) {
        Long id = param(model, 'clusterId') as Long
        ComputeServerGroup c = hks(id)
        if (!'POST'.equalsIgnoreCase(model.request?.method as String) || !Access.canUse(model.user) || !c) {
            log.warn("HKS Upgrade Readiness: DENIED ${model.user?.username} check cluster=${id}")
            return back(id, model)
        }
        if (Checker.start(id, c.name, KubeClient.of(morpheus, c), model.user?.username, offeredVersions())) {
            log.info("HKS Upgrade Readiness: ${model.user?.username} started a check of cluster ${c.name} (${id})")
        }
        back(id, model)
    }

    /** Printable result: open it and use the browser's "Save as PDF". */
    def report(ViewModel<Map> model) {
        Long id = param(model, 'clusterId') as Long
        ComputeServerGroup c = hks(id)
        if (!Access.canUse(model.user) || !c) return HTMLResponse.error('No access to this cluster', 403)
        CheckState s = Checker.state(id)
        Map result = s?.result ?: s?.previous
        if (!result) return HTMLResponse.error('Run the check first', 404)
        ViewModel<ReadinessView> m = new ViewModel<>()
        m.object = new ReadinessView(clusterId: id, clusterName: c.name, result: result)
        plugin.renderer.renderTemplate('hbs/report-print', m)
    }

    /** Kubernetes versions of the HKS cluster layouts in Morpheus. */
    private List<String> offeredVersions() {
        try {
            List<String> codes = morpheus.services.computeTypeLayout.list(new DataQuery())
                .findAll { ComputeTypeLayout l -> l.groupType?.code == 'kubernetes-cluster' }.collect { it.code }
            return Checks.layoutVersions(codes)
        } catch (Throwable t) {
            log.warn("HKS Upgrade Readiness: cannot read the HKS layouts: ${t}")
            return []
        }
    }

    private ComputeServerGroup hks(Long id) {
        if (!id) return null
        ComputeServerGroup c = null
        try { c = morpheus.services.cluster.get(id) } catch (Throwable ignored) { }
        Access.isHks(c) ? c : null
    }

    private static String param(ViewModel<Map> model, String name) {
        try { (model.request?.parameterMap?.get(name) as List)?.first() as String } catch (Throwable ignored) { null }
    }

    private static HTMLResponse back(Long clusterId, ViewModel model) {
        String url = "/infrastructure/clusters/${clusterId}#!hks-upgrade-tab"
        try { model?.response?.sendRedirect(url); return HTMLResponse.success('') } catch (Throwable ignored) { }
        HTMLResponse.success("<html><head><meta http-equiv=\"refresh\" content=\"0;url=${url}\"></head></html>")
    }
}
