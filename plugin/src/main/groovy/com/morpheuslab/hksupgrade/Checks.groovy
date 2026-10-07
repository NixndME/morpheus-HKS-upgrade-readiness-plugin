package com.morpheuslab.hksupgrade

import groovy.json.JsonSlurper

import java.util.zip.GZIPInputStream

/**
 * The readiness checks. Each one takes what was read from the cluster and returns a result:
 * [id, title, status: block|warn|pass|info, summary, why, fix, columns, items].
 */
class Checks {

    /** API versions Kubernetes has removed, with the version that removed them and what to use instead. */
    static final List<Map> REMOVED_APIS = [
        [api: 'extensions/v1beta1', kinds: ['Deployment', 'DaemonSet', 'ReplicaSet', 'NetworkPolicy', 'PodSecurityPolicy'], removed: '1.16', use: 'apps/v1, networking.k8s.io/v1'],
        [api: 'apps/v1beta1', kinds: ['Deployment', 'StatefulSet', 'ReplicaSet'], removed: '1.16', use: 'apps/v1'],
        [api: 'apps/v1beta2', kinds: ['Deployment', 'StatefulSet', 'DaemonSet', 'ReplicaSet'], removed: '1.16', use: 'apps/v1'],
        [api: 'extensions/v1beta1', kinds: ['Ingress'], removed: '1.22', use: 'networking.k8s.io/v1'],
        [api: 'networking.k8s.io/v1beta1', kinds: ['Ingress', 'IngressClass'], removed: '1.22', use: 'networking.k8s.io/v1'],
        [api: 'admissionregistration.k8s.io/v1beta1', kinds: ['ValidatingWebhookConfiguration', 'MutatingWebhookConfiguration'], removed: '1.22', use: 'admissionregistration.k8s.io/v1'],
        [api: 'apiextensions.k8s.io/v1beta1', kinds: ['CustomResourceDefinition'], removed: '1.22', use: 'apiextensions.k8s.io/v1'],
        [api: 'apiregistration.k8s.io/v1beta1', kinds: ['APIService'], removed: '1.22', use: 'apiregistration.k8s.io/v1'],
        [api: 'rbac.authorization.k8s.io/v1beta1', kinds: ['Role', 'RoleBinding', 'ClusterRole', 'ClusterRoleBinding'], removed: '1.22', use: 'rbac.authorization.k8s.io/v1'],
        [api: 'certificates.k8s.io/v1beta1', kinds: ['CertificateSigningRequest'], removed: '1.22', use: 'certificates.k8s.io/v1'],
        [api: 'coordination.k8s.io/v1beta1', kinds: ['Lease'], removed: '1.22', use: 'coordination.k8s.io/v1'],
        [api: 'scheduling.k8s.io/v1beta1', kinds: ['PriorityClass'], removed: '1.22', use: 'scheduling.k8s.io/v1'],
        [api: 'storage.k8s.io/v1beta1', kinds: ['StorageClass', 'VolumeAttachment', 'CSIDriver', 'CSINode'], removed: '1.22', use: 'storage.k8s.io/v1'],
        [api: 'batch/v1beta1', kinds: ['CronJob'], removed: '1.25', use: 'batch/v1'],
        [api: 'discovery.k8s.io/v1beta1', kinds: ['EndpointSlice'], removed: '1.25', use: 'discovery.k8s.io/v1'],
        [api: 'events.k8s.io/v1beta1', kinds: ['Event'], removed: '1.25', use: 'events.k8s.io/v1'],
        [api: 'autoscaling/v2beta1', kinds: ['HorizontalPodAutoscaler'], removed: '1.25', use: 'autoscaling/v2'],
        [api: 'policy/v1beta1', kinds: ['PodDisruptionBudget'], removed: '1.25', use: 'policy/v1'],
        [api: 'policy/v1beta1', kinds: ['PodSecurityPolicy'], removed: '1.25', use: 'Pod Security Admission'],
        [api: 'node.k8s.io/v1beta1', kinds: ['RuntimeClass'], removed: '1.25', use: 'node.k8s.io/v1'],
        [api: 'flowcontrol.apiserver.k8s.io/v1beta1', kinds: ['FlowSchema', 'PriorityLevelConfiguration'], removed: '1.26', use: 'flowcontrol.apiserver.k8s.io/v1'],
        [api: 'autoscaling/v2beta2', kinds: ['HorizontalPodAutoscaler'], removed: '1.26', use: 'autoscaling/v2'],
        [api: 'storage.k8s.io/v1beta1', kinds: ['CSIStorageCapacity'], removed: '1.27', use: 'storage.k8s.io/v1'],
        [api: 'flowcontrol.apiserver.k8s.io/v1beta2', kinds: ['FlowSchema', 'PriorityLevelConfiguration'], removed: '1.29', use: 'flowcontrol.apiserver.k8s.io/v1'],
        [api: 'flowcontrol.apiserver.k8s.io/v1beta3', kinds: ['FlowSchema', 'PriorityLevelConfiguration'], removed: '1.32', use: 'flowcontrol.apiserver.k8s.io/v1'],
    ]

    /** Namespaces HKS itself runs: Kubernetes, network, storage, ingress, logging, monitoring. */
    static final List<String> SYSTEM_NAMESPACES = ['kube-system', 'kube-public', 'kube-node-lease', 'calico-system', 'calico-apiserver',
                                                   'tigera-operator', 'rook-ceph', 'ingress-nginx', 'logging', 'monitoring']

    static boolean isSystem(String ns) { ns in SYSTEM_NAMESPACES }

    /** "1 pod" / "3 pods" */
    static String n(int count, String word, String plural = null) { "${count} ${count == 1 ? word : (plural ?: word + 's')}" }

    /** "31 workloads (5 of yours, 26 HKS system)" */
    static String split(List<List> rows, int nsColumn, String word) {
        int sys = rows.count { isSystem((it[nsColumn] as String).split('/')[0]) } as int
        String total = n(rows.size(), word)
        sys == 0 ? total : sys == rows.size() ? "${total}, all part of HKS itself" : "${total} (${rows.size() - sys} of yours, ${sys} part of HKS itself)"
    }

    /** Application rows first, then HKS system rows, with a column saying which. */
    static List<List> appsFirst(List<List> rows, int nsColumn) {
        rows.collect { it + [isSystem((it[nsColumn] as String).split('/')[0]) ? 'HKS system' : 'Application'] }
            .sort { a, b -> (a[-1] <=> b[-1]) ?: (a[nsColumn] <=> b[nsColumn]) }
    }

    static Map removedApi(String apiVersion, String kind) {
        REMOVED_APIS.find { it.api == apiVersion && kind in it.kinds }
    }

    // ---- versions ----

    /** "v1.35.9" -> [1, 35] */
    static List<Integer> minor(String v) {
        def m = (v ?: '') =~ /v?(\d+)\.(\d+)/
        m.find() ? [m.group(1) as int, m.group(2) as int] : null
    }

    /** a <= b for "1.36"-style versions. */
    static boolean atMost(String a, String b) {
        List<Integer> x = minor(a), y = minor(b)
        x && y && (x[0] < y[0] || (x[0] == y[0] && x[1] <= y[1]))
    }

    static String next(String v) {
        List<Integer> m = minor(v)
        m ? "${m[0]}.${m[1] + 1}" : null
    }

    /** Kubernetes versions of the HKS layouts Morpheus can provision, from codes like kubernetes-1.35-ubuntu-24.04-... */
    static List<String> layoutVersions(List<String> layoutCodes) {
        layoutCodes.collect { String c -> def m = (c ?: '') =~ /^kubernetes-(\d+)[._](\d+)-/; m.find() ? "${m.group(1)}.${m.group(2)}".toString() : null }
            .findAll().unique().sort { String v -> minor(v)[0] * 1000 + minor(v)[1] }
    }

    /** The version to check against: the next one Morpheus offers, else the next minor version. */
    static String target(String current, List<String> offered) {
        offered.find { !atMost(it, "${minor(current)[0]}.${minor(current)[1]}") } ?: next(current)
    }

    static Map morpheusVersions(String current, List<String> offered, String target) {
        String now = minor(current) ? "${minor(current)[0]}.${minor(current)[1]}" : current
        List<String> newer = offered.findAll { !atMost(it, now) }
        if (newer) return result('morpheus', 'HKS versions in Morpheus', 'info', "Morpheus offers HKS ${newer.join(', ')}. This check is for ${target}.",
            'Kubernetes is upgraded one minor version at a time.', 'When the checks pass, use Actions > Upgrade Cluster.', [], [])
        result('morpheus', 'HKS versions in Morpheus', 'info', "Morpheus has no HKS version newer than ${now} yet. This check is for ${target}, the next Kubernetes version.",
            'The Upgrade Cluster action lists a new version once Morpheus ships it (appliance or plugin update).', 'Run this check again when the new version is available.', [], [])
    }

    // ---- control plane and nodes ----

    static Map controlPlane(Map readyz) {
        List<String> failed = (readyz?.body as String ?: '').readLines().findAll { it.startsWith('[-]') }.collect { it.substring(3).trim() }
        if (!readyz || readyz.status == 0) return result('control-plane', 'Control plane health', 'warn', 'Could not read the API server health.',
            '', 'Check that Morpheus can reach the cluster API.', ['Problem'], [[readyz?.error ?: 'no answer']])
        if (readyz.status == 200 && !failed) return result('control-plane', 'Control plane health', 'pass', 'All API server health checks pass.')
        result('control-plane', 'Control plane health', 'block', "${n(failed.size() ?: 1, 'API server health check')} failing.",
            'An upgrade restarts the control plane. Start from a healthy one, or it may not come back.',
            'Fix the failing checks first (kubectl get --raw "/readyz?verbose").', ['Failing check'], (failed ?: ["HTTP ${readyz.status}"]).collect { [it] })
    }

    static Map nodes(List<Map> nodes, String serverVersion) {
        List<List> bad = [], cordoned = [], skew = []
        List<Integer> server = minor(serverVersion)
        nodes.each { Map n ->
            String name = n.metadata?.name
            Map<String, String> cond = (n.status?.conditions ?: []).collectEntries { [(it.type): it.status] }
            if (cond.Ready != 'True') bad << [name, 'Not Ready']
            ['MemoryPressure', 'DiskPressure', 'PIDPressure'].each { if (cond[it] == 'True') bad << [name, it] }
            if (n.spec?.unschedulable) cordoned << [name, 'Cordoned (scheduling disabled)']
            String kubelet = n.status?.nodeInfo?.kubeletVersion
            List<Integer> k = minor(kubelet)
            if (server && k && k != server) skew << [name, "kubelet ${kubelet}, control plane ${serverVersion}"]
        }
        if (bad) return result('nodes', 'Nodes', 'block', "${n(bad.size(), 'node problem')}.",
            'Every node is drained and restarted during the upgrade. A node that is already unhealthy can stop it halfway.',
            'Fix or remove the unhealthy nodes before upgrading.', ['Node', 'Problem'], bad + cordoned + skew)
        if (skew) return result('nodes', 'Nodes', 'block', "${n(skew.size(), 'node')} ${skew.size() == 1 ? 'runs' : 'run'} a different Kubernetes version than the control plane.",
            'A previous upgrade did not finish. Kubernetes upgrades one minor version at a time, so finish that one first.',
            'Finish the previous upgrade so all nodes run the control plane version.', ['Node', 'Problem'], skew + cordoned)
        if (cordoned) return result('nodes', 'Nodes', 'warn', "${n(cordoned.size(), 'node')} ${cordoned.size() == 1 ? 'is' : 'are'} cordoned.",
            'Cordoned nodes take no new pods, so the cluster has less room while other nodes are drained.',
            'Uncordon them if the maintenance is over: kubectl uncordon <node>.', ['Node', 'Problem'], cordoned)
        result('nodes', 'Nodes', 'pass', "All ${nodes.size()} nodes are Ready and run ${serverVersion}.")
    }

    static Map storage(Map cephList) {
        List<Map> clusters = (cephList?.status == 200 ? cephList.data?.items : null) ?: []
        if (!clusters) return result('storage', 'Ceph storage (Rook)', 'info', 'No Rook Ceph cluster found.')
        List<List> rows = clusters.collect { Map c -> ["${c.metadata?.namespace}/${c.metadata?.name}", c.status?.ceph?.health ?: 'unknown', c.status?.ceph?.details?.keySet()?.join(', ') ?: ''] }
        String worst = rows*.get(1).contains('HEALTH_ERR') || rows*.get(1).contains('unknown') ? 'block' : rows*.get(1).contains('HEALTH_WARN') ? 'warn' : 'pass'
        String why = 'Draining a node stops its Ceph daemons. Ceph must be healthy so it can keep the data available while that happens.'
        if (worst == 'pass') return result('storage', 'Ceph storage (Rook)', 'pass', 'Ceph reports HEALTH_OK.')
        result('storage', 'Ceph storage (Rook)', worst, "Ceph reports ${rows*.get(1).unique().join(', ')}.", why,
            'Bring Ceph back to HEALTH_OK first (ceph status in the rook-ceph tools pod).', ['Ceph cluster', 'Health', 'Details'], rows)
    }

    static Map certificate(Date expiry, Date now = new Date()) {
        if (!expiry) return result('certificate', 'API server certificate', 'info', 'The certificate could not be read.')
        int days = (int) ((expiry.time - now.time) / 86_400_000L)
        String when = new java.text.SimpleDateFormat('yyyy-MM-dd').format(expiry)
        if (days < 0) return result('certificate', 'API server certificate', 'block', "Expired on ${when}.", 'Nothing can talk to the cluster with an expired certificate.',
            'Renew the control plane certificates: kubeadm certs renew all.', [], [])
        if (days < 30) return result('certificate', 'API server certificate', 'warn', "Expires in ${days} days (${when}).",
            'The upgrade renews the control plane certificates, so upgrading soon also fixes this.', 'Upgrade, or run kubeadm certs renew all.', [], [])
        result('certificate', 'API server certificate', 'pass', "Valid for ${days} more days (until ${when}). The upgrade renews it.")
    }

    // ---- APIs ----

    /** Deprecated API calls the API server has served since it started, from its own metric. */
    static List<Map> deprecatedCalls(String metrics) {
        (metrics ?: '').readLines().findAll { it.startsWith('apiserver_requested_deprecated_apis{') }.collect { String line ->
            Map<String, String> l = (line =~ /(\w+)="([^"]*)"/).collect { it }.collectEntries { [(it[1]): it[2]] }
            [api: (l.group ? "${l.group}/${l.version}" : l.version) as String, resource: l.resource, removed: l.removed_release]
        }
    }

    static Map apisInUse(List<Map> calls, String target) {
        List<Map> breaking = calls.findAll { it.removed && atMost(it.removed as String, target) }
        List<Map> later = calls.findAll { it.removed && !atMost(it.removed as String, target) }
        List<List> rows = breaking.collect { [it.api, it.resource, it.removed, 'Stops working after this upgrade'] } +
                          later.collect { [it.api, it.resource, it.removed, 'Removed in a later version'] }
        String why = 'Something in or outside the cluster still calls these API versions. After the upgrade those calls fail.'
        String fix = 'Find the client (controller, CI job, kubectl scripts) and move it to the newer API version.'
        if (breaking) return result('apis-in-use', "APIs removed in ${target}", 'block', "${n(breaking.size(), 'removed API')} ${breaking.size() == 1 ? 'is' : 'are'} still being called.", why, fix,
            ['API version', 'Resource', 'Removed in', 'Effect'], rows)
        if (later) return result('apis-in-use', "APIs removed in ${target}", 'warn', "None removed in ${target}, but ${n(later.size(), 'API')} in use ${later.size() == 1 ? 'goes' : 'go'} away in a later version.", why, fix,
            ['API version', 'Resource', 'Removed in', 'Effect'], rows)
        result('apis-in-use', "APIs removed in ${target}", 'pass', "Nothing has called an API version removed in ${target} since the API server started.")
    }

    /** Decode a Helm 3 release secret (base64 of gzip of JSON) into [name, namespace, chart, manifest]. */
    static Map helmRelease(Map secret) {
        try {
            String outer = secret.data?.release as String
            byte[] inner = new String(outer.decodeBase64(), 'UTF-8').decodeBase64()
            String json = inner.length > 2 && inner[0] == (byte) 0x1f && inner[1] == (byte) 0x8b ?
                new GZIPInputStream(new ByteArrayInputStream(inner)).getText('UTF-8') : new String(inner, 'UTF-8')
            Map r = new JsonSlurper().parseText(json) as Map
            [name: r.name, namespace: r.namespace, chart: "${r.chart?.metadata?.name}-${r.chart?.metadata?.version}", manifest: r.manifest ?: '']
        } catch (Exception ignored) {
            null
        }
    }

    /** apiVersion and kind of each document in a rendered manifest. */
    static List<Map> manifestObjects(String manifest) {
        (manifest ?: '').split(/(?m)^---.*$/).collect { String doc ->
            def a = doc =~ /(?m)^apiVersion:\s*["']?([^\s"']+)/
            def k = doc =~ /(?m)^kind:\s*["']?([^\s"']+)/
            def n = doc =~ /(?m)^  name:\s*["']?([^\s"']+)/
            a.find() && k.find() ? [api: a.group(1), kind: k.group(1), name: n.find() ? n.group(1) : ''] : null
        }.findAll()
    }

    static Map helmCharts(List<Map> releases) {
        List<List> rows = []
        releases.findAll().each { Map r ->
            manifestObjects(r.manifest as String).each { Map o ->
                Map gone = removedApi(o.api as String, o.kind as String)
                if (gone) rows << ["${r.namespace}/${r.name}", r.chart, "${o.kind} ${o.name}", o.api, gone.removed, gone.use]
            }
        }
        if (!releases) return result('helm', 'Helm releases', 'info', 'No Helm releases in the cluster.')
        if (rows) return result('helm', 'Helm releases', 'warn', "${n(rows*.get(0).unique().size(), 'Helm release')} ${rows*.get(0).unique().size() == 1 ? 'was' : 'were'} installed with API versions Kubernetes no longer has.",
            'The objects themselves are fine, but helm upgrade and helm rollback of these releases fail until the chart is updated.',
            'Upgrade the chart to a version that uses the new APIs, or use the helm mapkubeapis plugin.',
            ['Release', 'Chart', 'Object', 'API version', 'Removed in', 'Use instead'], rows)
        result('helm', 'Helm releases', 'pass', releases.size() == 1 ? 'The Helm release uses current API versions.' : "All ${releases.size()} Helm releases use current API versions.")
    }

    /** Objects last applied with kubectl from a manifest that uses a removed API version. */
    static Map appliedManifests(List<Map> objects) {
        List<List> rows = []
        objects.each { Map o ->
            String last = o.metadata?.annotations?.get('kubectl.kubernetes.io/last-applied-configuration')
            def a = last ? last =~ /"apiVersion"\s*:\s*"([^"]+)"/ : null
            Map gone = a?.find() ? removedApi(a.group(1), o.kind as String) : null
            if (gone) rows << [o.kind, "${o.metadata?.namespace ? o.metadata.namespace + '/' : ''}${o.metadata?.name}", a.group(1), gone.removed, gone.use]
        }
        if (rows) return result('manifests', 'Applied manifests', 'warn', "${n(rows.size(), 'object')} ${rows.size() == 1 ? 'was' : 'were'} last applied from YAML with a removed API version.",
            'The next kubectl apply of that YAML fails. The YAML is usually in Git or a CI pipeline.',
            'Update the apiVersion in the source YAML.', ['Kind', 'Object', 'API version in YAML', 'Removed in', 'Use instead'], rows)
        result('manifests', 'Applied manifests', 'pass', "No kubectl-applied object uses a removed API version (${objects.size()} checked).")
    }

    // ---- node drains ----

    static Map disruptionBudgets(List<Map> pdbs) {
        List<List> rows = pdbs.findAll { Map p ->
            int allowed = (p.status?.disruptionsAllowed ?: 0) as int
            int expected = (p.status?.expectedPods ?: 0) as int
            expected > 0 && allowed == 0
        }.collect { Map p -> ["${p.metadata?.namespace}/${p.metadata?.name}", p.spec?.minAvailable ?: '', p.spec?.maxUnavailable ?: '',
                               "${p.status?.currentHealthy ?: 0} of ${p.status?.expectedPods ?: 0} healthy"] }
        if (rows) return result('pdb', 'Pod disruption budgets', 'block', "${n(rows.size(), 'budget')} ${rows.size() == 1 ? 'allows' : 'allow'} no pod to be evicted.",
            'Draining a node waits for these budgets. With 0 disruptions allowed, the drain and the upgrade hang.',
            'Scale the workload up, or relax minAvailable / maxUnavailable for the upgrade.',
            ['Budget', 'minAvailable', 'maxUnavailable', 'Pods'], rows)
        result('pdb', 'Pod disruption budgets', 'pass', pdbs ? "All ${n(pdbs.size(), 'budget')} allow at least one eviction." : 'No pod disruption budgets.')
    }

    static Map singleReplicas(List<Map> workloads) {
        List<List> rows = workloads.findAll { (it.spec?.replicas ?: 0) == 1 }
            .collect { ["${it.metadata?.namespace}/${it.metadata?.name}", it.kind] }
        if (rows) return result('replicas', 'Single-replica workloads', 'warn', "${split(rows, 0, 'workload')} ${rows.size() == 1 ? 'runs' : 'run'} one pod only.",
            'When its node is drained the only pod stops, so the app is down until it starts on another node.',
            'Run at least 2 replicas for your apps that must stay up, or plan the short outage. HKS system components are handled by the HKS upgrade.',
            ['Workload', 'Kind', 'Part of'], appsFirst(rows, 0))
        result('replicas', 'Single-replica workloads', 'pass', 'Every Deployment and StatefulSet runs more than one pod.')
    }

    static boolean isMirror(Map pod) { pod.metadata?.annotations?.containsKey('kubernetes.io/config.mirror') }

    static Map barePods(List<Map> pods) {
        List<List> rows = pods.findAll { Map p -> !p.metadata?.ownerReferences && !isMirror(p) && p.status?.phase in ['Running', 'Pending'] }
            .collect { ["${it.metadata?.namespace}/${it.metadata?.name}", it.spec?.nodeName ?: ''] }
        if (rows) return result('bare-pods', 'Pods without a controller', 'warn', "${split(rows, 0, 'pod')} ${rows.size() == 1 ? 'is' : 'are'} not managed by a Deployment, StatefulSet, DaemonSet or Job.",
            'Nothing recreates these pods. Draining their node deletes them for good (kubectl drain needs --force).',
            'Move them into a Deployment, or recreate them after the upgrade.', ['Pod', 'Node', 'Part of'], appsFirst(rows, 0))
        result('bare-pods', 'Pods without a controller', 'pass', 'Every pod is managed by a controller.')
    }

    static Map unhealthyPods(List<Map> pods, long now = System.currentTimeMillis()) {
        List<List> rows = []
        pods.each { Map p ->
            String name = "${p.metadata?.namespace}/${p.metadata?.name}"
            Map waiting = (p.status?.containerStatuses ?: []).collect { it.state?.waiting }.find { it?.reason in ['CrashLoopBackOff', 'ImagePullBackOff', 'ErrImagePull', 'CreateContainerConfigError'] }
            if (waiting) { rows << [name, waiting.reason]; return }
            Map crashing = (p.status?.containerStatuses ?: []).find { !it.ready && ((it.restartCount ?: 0) >= 3 || (it.state?.terminated?.exitCode ?: 0) != 0) }
            if (crashing && p.status?.phase == 'Running') {
                rows << [name, "Restarting (${crashing.restartCount} restarts, last exit code ${crashing.state?.terminated?.exitCode ?: crashing.lastState?.terminated?.exitCode ?: '?'})"]
                return
            }
            if (p.status?.phase == 'Pending') {
                Date created = parseTime(p.metadata?.creationTimestamp as String)
                if (created && now - created.time > 10 * 60_000L) rows << [name, 'Pending for more than 10 minutes']
            }
        }
        if (rows) return result('pods', 'Pods with problems', 'warn', "${split(rows, 0, 'pod')} ${rows.size() == 1 ? 'is' : 'are'} failing right now.",
            'These problems exist before the upgrade. Fix them first, so they are not mistaken for upgrade problems afterwards.',
            'kubectl describe pod and kubectl logs show the reason.', ['Pod', 'Problem', 'Part of'], appsFirst(rows, 0))
        result('pods', 'Pods with problems', 'pass', 'No pod is crash looping, failing to pull its image or stuck in Pending.')
    }

    static Map webhooks(List<Map> configs, Closure<Integer> readyEndpoints) {
        List<List> rows = []
        int failClosed = 0
        configs.each { Map cfg ->
            (cfg.webhooks ?: []).each { Map h ->
                Map svc = h.clientConfig?.service
                if (h.failurePolicy != 'Fail' || !svc) return
                failClosed++
                int ready = readyEndpoints(svc.namespace as String, svc.name as String)
                if (ready == 0) rows << [cfg.kind, h.name, "${svc.namespace}/${svc.name}"]
            }
        }
        if (rows) return result('webhooks', 'Admission webhooks', 'block', "${n(rows.size(), 'webhook')} must answer but ${rows.size() == 1 ? 'has' : 'have'} no running backend.",
            'With failurePolicy Fail and nothing behind the service, the API server refuses the objects they cover, so pods cannot be recreated on other nodes.',
            'Start the webhook\'s service again, or delete the webhook configuration if the app is gone.', ['Type', 'Webhook', 'Service'], rows)
        result('webhooks', 'Admission webhooks', 'pass', failClosed == 1 ? 'The webhook that must answer has a running backend.' : failClosed ? "All ${failClosed} webhooks that must answer have a running backend." : 'No webhook can block the upgrade.')
    }

    // ---- capacity ----

    static long cpu(String q) {
        if (!q) return 0L
        if (q.endsWith('m')) return q[0..-2] as long
        (long) ((q as double) * 1000)
    }

    static long mem(String q) {
        if (!q) return 0L
        def m = q =~ /^([0-9.]+)([A-Za-z]*)$/
        if (!m.matches()) return 0L
        Map<String, Double> unit = [Ki: 1024d, Mi: 1048576d, Gi: 1073741824d, Ti: 1099511627776d, k: 1000d, M: 1e6d, G: 1e9d, T: 1e12d, '': 1d]
        (long) ((m.group(1) as double) * (unit[m.group(2)] ?: 1d))
    }

    static boolean isControlPlane(Map node) {
        Map labels = node.metadata?.labels ?: [:]
        labels.containsKey('node-role.kubernetes.io/control-plane') || labels.containsKey('node-role.kubernetes.io/master')
    }

    /** Can the other workers take the pods of the busiest worker while it is drained? */
    static Map capacity(List<Map> nodes, List<Map> pods) {
        List<Map> workers = nodes.findAll { !isControlPlane(it) && !it.spec?.unschedulable }
        if (workers.size() < 2) return result('capacity', 'Room to drain a node', 'warn', "Only ${n(workers.size(), 'schedulable worker node')}.",
            'With one worker, draining it stops every app until it is back.', 'Add a worker node before upgrading if the apps must stay up.', [], [])
        Map<String, Map> use = workers.collectEntries { [(it.metadata.name): [cpu: 0L, mem: 0L, moving: [cpu: 0L, mem: 0L]]] }
        pods.findAll { it.status?.phase in ['Running', 'Pending'] && use[it.spec?.nodeName] }.each { Map p ->
            long c = 0L, m = 0L
            (p.spec?.containers ?: []).each { c += cpu(it.resources?.requests?.cpu as String); m += mem(it.resources?.requests?.memory as String) }
            Map u = use[p.spec.nodeName]
            u.cpu += c; u.mem += m
            boolean daemon = (p.metadata?.ownerReferences ?: []).any { it.kind == 'DaemonSet' }
            if (!daemon && !isMirror(p)) { u.moving.cpu += c; u.moving.mem += m }
        }
        List<List> rows = workers.collect { Map w ->
            String name = w.metadata.name
            long freeCpu = 0L, freeMem = 0L
            workers.findAll { it.metadata.name != name }.each { Map o ->
                freeCpu += Math.max(0L, cpu(o.status?.allocatable?.cpu as String) - use[o.metadata.name].cpu)
                freeMem += Math.max(0L, mem(o.status?.allocatable?.memory as String) - use[o.metadata.name].mem)
            }
            Map mv = use[name].moving
            boolean fits = mv.cpu <= freeCpu && mv.mem <= freeMem
            [name, "${mv.cpu}m CPU, ${gib(mv.mem)} memory", "${freeCpu}m CPU, ${gib(freeMem)} memory", fits ? 'Yes' : 'No']
        }
        List<List> tight = rows.findAll { it[3] == 'No' }
        String why = 'During the upgrade each worker is drained in turn. Its pods need room on the other workers (by their resource requests).'
        if (tight) return result('capacity', 'Room to drain a node', 'warn', "${n(tight.size(), 'worker')} cannot be drained without some pods staying Pending.", why,
            'Add a worker node, or lower resource requests, before upgrading.', ['Worker', 'Pods to move (requests)', 'Free on other workers', 'Fits'], rows)
        result('capacity', 'Room to drain a node', 'pass', "Any one of the ${workers.size()} workers can be drained; the others have room for its pods.", why, '',
            ['Worker', 'Pods to move (requests)', 'Free on other workers', 'Fits'], rows)
    }

    static String gib(long bytes) { String.format('%.1f GiB', bytes / 1073741824d) }

    // ---- helpers ----

    static Date parseTime(String t) {
        try { t ? Date.from(java.time.Instant.parse(t)) : null } catch (Exception ignored) { null }
    }

    static Map result(String id, String title, String status, String summary, String why = '', String fix = '', List<String> columns = [], List<List> items = []) {
        [id: id, title: title, status: status, summary: summary, why: why, fix: fix, columns: columns, items: items.collect { it.collect { c -> c as String } }]
    }

    /** Overall answer: not ready if anything blocks, ready with warnings, or ready. */
    static String verdict(List<Map> results) {
        results.any { it.status == 'block' } ? 'block' : results.any { it.status == 'warn' } ? 'warn' : 'pass'
    }
}
