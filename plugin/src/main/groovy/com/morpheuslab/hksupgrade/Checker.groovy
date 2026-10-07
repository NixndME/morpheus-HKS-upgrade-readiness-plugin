package com.morpheuslab.hksupgrade

import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the readiness check of one cluster in the background. It only reads from the Kubernetes API:
 * nothing is installed or changed in the cluster. The last result is kept in memory.
 */
@Slf4j
class Checker {

    static final List<String> STEPS = ['Read the cluster version and nodes', 'Check the control plane and storage',
                                       'Check APIs the next version removes', 'Check what could block node drains',
                                       'Check room to move pods']

    static final Map<Long, CheckState> STATES = new ConcurrentHashMap<>()

    static CheckState state(Long clusterId) { clusterId ? STATES.get(clusterId) : null }

    static synchronized CheckState start(Long clusterId, String clusterName, KubeClient kube, String user, List<String> offered) {
        CheckState old = STATES.get(clusterId)
        if (old?.running) return null
        CheckState s = new CheckState(clusterId: clusterId, clusterName: clusterName, user: user, previous: old?.result ?: old?.previous,
                                      steps: STEPS.collect { [name: it, status: 'waiting'] })
        STATES.put(clusterId, s)
        Thread t = new Thread({ run(s, kube, offered) } as Runnable, "hks-upgrade-check-${clusterId}")
        t.daemon = true
        t.start()
        s
    }

    static void run(CheckState s, KubeClient kube, List<String> offered) {
        try {
            List<Map> results = []
            Map data = [:]
            step(s, 0, 'reading nodes') {
                data.version = kube.get('/version').data?.gitVersion as String
                if (!data.version) throw new IllegalStateException("cannot reach the cluster API: ${kube.get('/version').error}")
                data.target = Checks.target(data.version as String, offered)
                data.nodes = items(kube, '/api/v1/nodes')
                data.pods = items(kube, '/api/v1/pods')
                s.line = "Kubernetes ${data.version}, ${data.nodes.size()} nodes, ${data.pods.size()} pods"
            }
            results << Checks.morpheusVersions(data.version as String, offered, data.target as String)
            step(s, 1, 'asking the API server for its health') {
                results << Checks.controlPlane(kube.textAny('/readyz?verbose'))
                results << Checks.nodes(data.nodes as List<Map>, data.version as String)
                s.line = 'reading the Ceph status'
                results << Checks.storage(kube.get('/apis/ceph.rook.io/v1/cephclusters'))
                results << Checks.certificate(kube.certificateExpiry())
            }
            step(s, 2, 'reading the API server metrics') {
                results << Checks.apisInUse(Checks.deprecatedCalls(kube.text('/metrics')), data.target as String)
                s.line = 'decoding Helm releases'
                List<Map> releases = items(kube, '/api/v1/secrets?labelSelector=owner%3Dhelm%2Cstatus%3Ddeployed').collect { Checks.helmRelease(it) }
                results << Checks.helmCharts(releases)
                s.line = 'reading kubectl-applied objects'
                data.deployments = items(kube, '/apis/apps/v1/deployments').collect { it + [kind: 'Deployment'] }
                data.statefulsets = items(kube, '/apis/apps/v1/statefulsets').collect { it + [kind: 'StatefulSet'] }
                data.pdbs = items(kube, '/apis/policy/v1/poddisruptionbudgets').collect { it + [kind: 'PodDisruptionBudget'] }
                data.webhooks = items(kube, '/apis/admissionregistration.k8s.io/v1/validatingwebhookconfigurations').collect { it + [kind: 'ValidatingWebhookConfiguration'] } +
                                items(kube, '/apis/admissionregistration.k8s.io/v1/mutatingwebhookconfigurations').collect { it + [kind: 'MutatingWebhookConfiguration'] }
                List<Map> applied = data.deployments + data.statefulsets + data.pdbs + data.webhooks
                [['/apis/apps/v1/daemonsets', 'DaemonSet'], ['/apis/networking.k8s.io/v1/ingresses', 'Ingress'], ['/apis/batch/v1/cronjobs', 'CronJob'],
                 ['/apis/autoscaling/v2/horizontalpodautoscalers', 'HorizontalPodAutoscaler'], ['/apis/networking.k8s.io/v1/networkpolicies', 'NetworkPolicy'],
                 ['/apis/rbac.authorization.k8s.io/v1/roles', 'Role'], ['/apis/rbac.authorization.k8s.io/v1/rolebindings', 'RoleBinding'],
                 ['/apis/rbac.authorization.k8s.io/v1/clusterroles', 'ClusterRole'], ['/apis/rbac.authorization.k8s.io/v1/clusterrolebindings', 'ClusterRoleBinding'],
                 ['/apis/scheduling.k8s.io/v1/priorityclasses', 'PriorityClass'], ['/apis/storage.k8s.io/v1/storageclasses', 'StorageClass']].each { String path, String kind ->
                    applied += items(kube, path).collect { it + [kind: kind] }
                }
                results << Checks.appliedManifests(applied)
            }
            step(s, 3, 'reading disruption budgets and webhooks') {
                results << Checks.disruptionBudgets(data.pdbs as List<Map>)
                results << Checks.webhooks(data.webhooks as List<Map>) { String ns, String svc -> readyEndpoints(kube, ns, svc) }
                results << Checks.singleReplicas((data.deployments + data.statefulsets) as List<Map>)
                results << Checks.barePods(data.pods as List<Map>)
                results << Checks.unhealthyPods(data.pods as List<Map>)
            }
            step(s, 4, 'adding up resource requests per node') {
                results << Checks.capacity(data.nodes as List<Map>, data.pods as List<Map>)
            }
            List<String> order = ['block', 'warn', 'pass', 'info']
            s.result = [checkedAt: System.currentTimeMillis(), user: s.user, version: data.version, target: data.target,
                        nodes: (data.nodes as List).size(), verdict: Checks.verdict(results),
                        checks: results.sort { order.indexOf(it.status) }]
            s.line = 'done'
            log.info("HKS Upgrade Readiness: check of cluster ${s.clusterName} (${s.clusterId}) for ${data.target}: ${s.result.verdict}")
        } catch (Throwable t) {
            s.error = t.message ?: t.class.simpleName
            log.warn("HKS Upgrade Readiness: check of cluster ${s.clusterId} failed: ${t}")
        } finally {
            s.running = false
            s.finished = System.currentTimeMillis()
        }
    }

    private static void step(CheckState s, int i, String line, Closure work) {
        s.steps[i].status = 'running'
        s.line = line
        long t0 = System.currentTimeMillis()
        try {
            work.call()
            s.steps[i].status = 'done'
            s.steps[i].seconds = (int) ((System.currentTimeMillis() - t0) / 1000)
        } catch (Throwable t) {
            s.steps[i].status = 'failed'
            throw t
        }
    }

    static List<Map> items(KubeClient kube, String path) {
        Map r = kube.get(path)
        if (r.status == 404) return []
        if (r.status != 200) throw new IllegalStateException("reading ${path.replaceAll(/\?.*/, '')} failed: ${r.error}")
        (r.data?.items ?: []) as List<Map>
    }

    static int readyEndpoints(KubeClient kube, String ns, String svc) {
        Map r = kube.get("/apis/discovery.k8s.io/v1/namespaces/${ns}/endpointslices?labelSelector=kubernetes.io%2Fservice-name%3D${svc}")
        if (r.status != 200) return 0
        (r.data?.items ?: []).sum { Map slice -> (slice.endpoints ?: []).count { it.conditions?.ready != false } } as Integer ?: 0
    }
}

class CheckState {
    Long clusterId
    String clusterName
    String user
    volatile boolean running = true
    long started = System.currentTimeMillis()
    Long finished
    List<Map> steps = []
    volatile String line = 'starting'
    String error
    Map result
    Map previous

    Map toMap() {
        [running: running, steps: steps, line: line, error: error, elapsed: (int) (((finished ?: System.currentTimeMillis()) - started) / 1000)]
    }
}
