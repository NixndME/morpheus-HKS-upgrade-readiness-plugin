# HKS Upgrade Readiness plugin for Morpheus

Adds an **Upgrade** tab (Upgrade Readiness) to HKS clusters in Morpheus. Click **Check now** before you use
*Actions > Upgrade Cluster*, and see in a few seconds whether the cluster is ready for the next Kubernetes version.

The answer is one of: **Ready**, **Ready with warnings**, or **Not ready**, with the details and what to do for each check.

![HKS Upgrade Readiness](https://github.com/NixndME/morpheus-HKS-upgrade-readiness-plugin/releases/download/v0.1.7/hks-upgrade-readiness.gif)

Full walkthrough (6 min, install to report): [hks-upgrade-readiness-walkthrough.mp4](https://github.com/NixndME/morpheus-HKS-upgrade-readiness-plugin/releases/download/v0.1.7/hks-upgrade-readiness-walkthrough.mp4)

| Check | What it finds | Result |
|---|---|---|
| HKS versions in Morpheus | Which HKS version Morpheus offers next | Info |
| Control plane health | Failing API server health checks | Blocker |
| Nodes | Nodes not Ready, under pressure, on another version, or cordoned | Blocker / Warning |
| Ceph storage (Rook) | Ceph not HEALTH_OK | Blocker / Warning |
| API server certificate | Expired or expiring soon | Blocker / Warning |
| APIs removed in the next version | Clients still calling them (from the API server's own metric) | Blocker |
| Helm releases | Charts installed with removed API versions (helm upgrade would fail) | Warning |
| Applied manifests | YAML last applied with kubectl that uses removed API versions | Warning |
| Pod disruption budgets | Budgets that allow no eviction (node drain hangs) | Blocker |
| Admission webhooks | Webhooks that must answer but have no running backend | Blocker |
| Single-replica workloads | Apps that go down while their node is drained | Warning |
| Pods without a controller | Pods a drain deletes for good | Warning |
| Pods with problems | Crash looping, image pull errors, stuck Pending | Warning |
| Room to drain a node | Whether the other workers have room for a drained worker's pods | Warning |

Lists separate your applications from what HKS itself runs (kube-system, Calico, Rook/Ceph, ingress, logging,
monitoring), so you see first what you can fix.

The check only reads from the Kubernetes API with the access Morpheus already has. Nothing is installed in the
cluster and nothing is changed. Run it again after a fix; the tab shows what changed since the previous check.
**Export PDF** gives a printable report.

## Install

1. Download `morpheus-hks-upgrade-readiness-plugin.jar` from the releases page and upload it in
   *Administration > Integrations > Plugins*.
2. In *Administration > Roles* set **HKS Upgrade Readiness** to read for the roles that should use it.
   System Admin has it already.

Morpheus 9.0.2 only shows the cluster tabs that fit on one line. If you have several plugins with cluster tabs
and **Upgrade** does not show, open it directly: `/infrastructure/clusters/<id>#!hks-upgrade-tab`.

## Build

Needs JDK 17 and Gradle 8.

```bash
cd plugin
gradle clean shadowJar test
```

Tested with Morpheus 9.0.2 and HKS Kubernetes 1.35.
