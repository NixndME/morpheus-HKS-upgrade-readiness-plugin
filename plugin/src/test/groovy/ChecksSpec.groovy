import com.morpheuslab.hksupgrade.Checks
import groovy.json.JsonOutput
import spock.lang.Specification

import java.util.zip.GZIPOutputStream

class ChecksSpec extends Specification {

    static Map node(String name, Map extra = [:]) {
        [metadata: [name: name, labels: extra.cp ? ['node-role.kubernetes.io/control-plane': ''] : [:]],
         spec: [unschedulable: extra.cordoned ?: false],
         status: [conditions: [[type: 'Ready', status: extra.ready == false ? 'False' : 'True'], [type: 'DiskPressure', status: 'False']],
                  nodeInfo: [kubeletVersion: extra.kubelet ?: 'v1.35.9'], allocatable: [cpu: extra.cpu ?: '4', memory: extra.mem ?: '8Gi']]]
    }

    static Map pod(String ns, String name, String nodeName, Map extra = [:]) {
        [metadata: [namespace: ns, name: name, ownerReferences: extra.bare ? null : [[kind: extra.owner ?: 'ReplicaSet']], creationTimestamp: '2026-01-01T00:00:00Z'],
         spec: [nodeName: nodeName, containers: [[resources: [requests: [cpu: extra.cpu ?: '100m', memory: extra.mem ?: '128Mi']]]]],
         status: [phase: extra.phase ?: 'Running', containerStatuses: extra.waiting ? [[state: [waiting: [reason: extra.waiting]]]] : []]]
    }

    def 'versions'() {
        expect:
        Checks.next('v1.35.9') == '1.36'
        Checks.atMost('1.36', '1.36') && Checks.atMost('1.25', '1.36') && !Checks.atMost('1.37', '1.36')
    }

    def 'the target is the next HKS version Morpheus offers'() {
        given:
        List<String> offered = Checks.layoutVersions(['kubernetes-1.35-ubuntu-24.04-morpheus-amd64', 'kubernetes-1_34-ubuntu-24_04-xen-amd64-single',
                                                      'kubernetes-fusion-ubuntu-16.04-single', 'kubernetes-1.36-ubuntu-24.04-morpheus-amd64', 'kubernetes-1.37-x'])
        expect:
        offered == ['1.34', '1.35', '1.36', '1.37']
        Checks.target('v1.35.9', offered) == '1.36'
        Checks.target('v1.35.9', ['1.34', '1.35']) == '1.36'
        Checks.morpheusVersions('v1.35.9', ['1.34', '1.35'], '1.36').summary.startsWith('Morpheus has no HKS version newer than 1.35 yet')
        Checks.morpheusVersions('v1.35.9', offered, '1.36').summary == 'Morpheus offers HKS 1.36, 1.37. This check is for 1.36.'
    }

    def 'deprecated API calls come from the API server metric'() {
        given:
        String metrics = '''# HELP x
apiserver_requested_deprecated_apis{group="",removed_release="",resource="endpoints",subresource="",version="v1"} 1
apiserver_requested_deprecated_apis{group="example.io",removed_release="1.36",resource="widgets",subresource="",version="v1beta1"} 1
apiserver_requested_deprecated_apis{group="other.io",removed_release="1.38",resource="things",subresource="",version="v1alpha1"} 1'''
        when:
        List calls = Checks.deprecatedCalls(metrics)
        Map r = Checks.apisInUse(calls, '1.36')
        then:
        calls.size() == 3
        r.status == 'block'
        r.items == [['example.io/v1beta1', 'widgets', '1.36', 'Stops working after this upgrade'], ['other.io/v1alpha1', 'things', '1.38', 'Removed in a later version']]
        Checks.apisInUse(calls.findAll { it.removed != '1.36' }, '1.36').status == 'warn'
        Checks.apisInUse([calls[0]], '1.36').status == 'pass'
    }

    def 'Helm releases with removed API versions are found'() {
        given:
        String manifest = '''---
# Source: x/templates/psp.yaml
apiVersion: policy/v1beta1
kind: PodSecurityPolicy
metadata:
  name: old-psp
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web
'''
        Map release = [name: 'legacy', namespace: 'demo', chart: [metadata: [name: 'legacy', version: '1.0.0']], manifest: manifest]
        def bytes = new ByteArrayOutputStream()
        new GZIPOutputStream(bytes).withStream { it.write(JsonOutput.toJson(release).getBytes('UTF-8')) }
        Map secret = [data: [release: bytes.toByteArray().encodeBase64().toString().getBytes('UTF-8').encodeBase64().toString()]]
        when:
        Map decoded = Checks.helmRelease(secret)
        Map r = Checks.helmCharts([decoded])
        then:
        decoded.chart == 'legacy-1.0.0'
        r.status == 'warn'
        r.items == [['demo/legacy', 'legacy-1.0.0', 'PodSecurityPolicy old-psp', 'policy/v1beta1', '1.25', 'Pod Security Admission']]
    }

    def 'kubectl last-applied YAML with a removed API version is found'() {
        given:
        Map old = [kind: 'CronJob', metadata: [namespace: 'demo', name: 'nightly',
            annotations: ['kubectl.kubernetes.io/last-applied-configuration': '{"apiVersion":"batch/v1beta1","kind":"CronJob"}']]]
        Map ok = [kind: 'Deployment', metadata: [namespace: 'demo', name: 'web',
            annotations: ['kubectl.kubernetes.io/last-applied-configuration': '{"apiVersion":"apps/v1","kind":"Deployment"}']]]
        expect:
        Checks.appliedManifests([old, ok]).items == [['CronJob', 'demo/nightly', 'batch/v1beta1', '1.25', 'batch/v1']]
        Checks.appliedManifests([ok]).status == 'pass'
    }

    def 'node problems block, cordoned nodes warn'() {
        expect:
        Checks.nodes([node('a'), node('b', [ready: false])], 'v1.35.9').status == 'block'
        Checks.nodes([node('a'), node('b', [kubelet: 'v1.34.2'])], 'v1.35.9').status == 'block'
        Checks.nodes([node('a'), node('b', [cordoned: true])], 'v1.35.9').status == 'warn'
        Checks.nodes([node('a'), node('b')], 'v1.35.9').status == 'pass'
    }

    def 'drain blockers: budgets, webhooks, bare pods'() {
        given:
        Map stuck = [metadata: [namespace: 'db', name: 'pg'], spec: [minAvailable: 1], status: [disruptionsAllowed: 0, expectedPods: 1, currentHealthy: 1]]
        Map fine = [metadata: [namespace: 'web', name: 'w'], spec: [maxUnavailable: 1], status: [disruptionsAllowed: 1, expectedPods: 3, currentHealthy: 3]]
        Map hook = [kind: 'ValidatingWebhookConfiguration', webhooks: [[name: 'v.example.io', failurePolicy: 'Fail', clientConfig: [service: [namespace: 'x', name: 'gone']]]]]
        expect:
        Checks.disruptionBudgets([stuck, fine]).items == [['db/pg', '1', '', '1 of 1 healthy']]
        Checks.disruptionBudgets([fine]).status == 'pass'
        Checks.webhooks([hook]) { ns, svc -> 0 }.status == 'block'
        Checks.webhooks([hook]) { ns, svc -> 2 }.status == 'pass'
        Checks.barePods([pod('a', 'lonely', 'w1', [bare: true]), pod('a', 'managed', 'w1')]).items == [['a/lonely', 'w1', 'Application']]
    }

    def 'your apps come before HKS system components'() {
        when:
        Map r = Checks.singleReplicas([[kind: 'Deployment', metadata: [namespace: 'monitoring', name: 'grafana'], spec: [replicas: 1]],
                                       [kind: 'Deployment', metadata: [namespace: 'shop', name: 'cart'], spec: [replicas: 1]]])
        then:
        r.summary == '2 workloads (1 of yours, 1 part of HKS itself) run one pod only.'
        r.items*.get(0) == ['shop/cart', 'monitoring/grafana']
        r.items*.get(2) == ['Application', 'HKS system']
    }

    def 'failing pods and single replicas warn'() {
        expect:
        Checks.unhealthyPods([pod('a', 'crash', 'w1', [waiting: 'CrashLoopBackOff']), pod('a', 'ok', 'w1')]).items == [['a/crash', 'CrashLoopBackOff', 'Application']]
        Checks.unhealthyPods([[metadata: [namespace: 'a', name: 'between-restarts'], status: [phase: 'Running',
            containerStatuses: [[ready: false, restartCount: 4, state: [terminated: [exitCode: 1]]]]]]]).items == [['a/between-restarts', 'Restarting (4 restarts, last exit code 1)', 'Application']]
        Checks.singleReplicas([[kind: 'Deployment', metadata: [namespace: 'a', name: 'one'], spec: [replicas: 1]],
                               [kind: 'Deployment', metadata: [namespace: 'a', name: 'two'], spec: [replicas: 2]]]).items == [['a/one', 'Deployment', 'Application']]
    }

    def 'capacity: can the other workers take a drained worker\'s pods'() {
        given:
        List nodes = [node('cp', [cp: true]), node('w1', [cpu: '2', mem: '4Gi']), node('w2', [cpu: '2', mem: '4Gi'])]
        when:
        Map roomy = Checks.capacity(nodes, [pod('a', 'p1', 'w1', [cpu: '500m', mem: '1Gi']), pod('a', 'p2', 'w2', [cpu: '500m', mem: '1Gi'])])
        Map tight = Checks.capacity(nodes, [pod('a', 'p1', 'w1', [cpu: '1500m', mem: '1Gi']), pod('a', 'p2', 'w2', [cpu: '1500m', mem: '1Gi'])])
        Map daemons = Checks.capacity(nodes, [pod('a', 'd1', 'w1', [cpu: '1500m', owner: 'DaemonSet']), pod('a', 'd2', 'w2', [cpu: '1500m', owner: 'DaemonSet'])])
        then:
        roomy.status == 'pass'
        tight.status == 'warn'
        tight.items[0] == ['w1', '1500m CPU, 1.0 GiB memory', '500m CPU, 3.0 GiB memory', 'No']
        daemons.status == 'pass'
        Checks.capacity([node('w1')], []).status == 'warn'
    }

    def 'verdict and certificate'() {
        expect:
        Checks.verdict([[status: 'pass'], [status: 'warn']]) == 'warn'
        Checks.verdict([[status: 'block'], [status: 'warn']]) == 'block'
        Checks.certificate(new Date(System.currentTimeMillis() + 200L * 86_400_000L)).status == 'pass'
        Checks.certificate(new Date(System.currentTimeMillis() + 10L * 86_400_000L)).status == 'warn'
        Checks.certificate(new Date(System.currentTimeMillis() - 86_400_000L)).status == 'block'
    }

    def 'control plane health from readyz'() {
        expect:
        Checks.controlPlane([status: 200, body: '[+]ping ok\nreadyz check passed']).status == 'pass'
        Checks.controlPlane([status: 500, body: '[+]ping ok\n[-]etcd failed: reason withheld\nreadyz check failed']).items == [['etcd failed: reason withheld']]
    }
}
