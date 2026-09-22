"""Policy checks over rendered Kubernetes manifests that schema validation cannot express.

kubeconform proves the manifests are well-formed; this proves they meet the M9 gate and the
project's own rules (build prompt D.3 M9, H.1, A.3 rule 10):

* **Pod Security "restricted"** for every pod template, checked offline so a violation fails in CI
  rather than at admission: non-root, no privilege escalation, all capabilities dropped (only
  NET_BIND_SERVICE may be added), RuntimeDefault or Localhost seccomp, no host namespaces or
  hostPath, only the volume types the profile allows. On top of the profile: read-only root
  filesystem, resource requests and a memory limit, liveness and readiness probes.
* **Images**: third-party images are pinned by digest; FraudShield images carry no tag in the
  manifests, because the deploy workflow pins them by digest.
* **Service account tokens** are not mounted except where a workload calls the Kubernetes API.
* **API spool** (ADR 0090): the ``spool`` volume of ``fraudshield-api`` is a PersistentVolumeClaim
  with ReadWriteMany access, never ``emptyDir`` or other storage deleted with the pod.
* **Namespace**: enforces the restricted profile; has a default-deny NetworkPolicy; every
  workload is selected by an allow policy, covered by a PodDisruptionBudget and, for the
  serving components in ``HPA_REQUIRED``, scaled by a HorizontalPodAutoscaler.

Usage: ``kustomize build <overlay> | uv run python infrastructure/checks/k8s_policy.py``
"""

from __future__ import annotations

import sys
from collections.abc import Iterator
from typing import Any

import yaml

Manifest = dict[str, Any]

OWN_IMAGE_PREFIX = "ghcr.io/mariusbayizere/fraudshield-"
WORKLOAD_KINDS = frozenset({"Deployment", "StatefulSet", "DaemonSet"})
# Serving components that must scale with load (M9 gate "HPA"); workers scale by Kafka partitions
# and single-instance monitoring components do not scale horizontally.
HPA_REQUIRED = frozenset(
    {"fraudshield-edge", "fraudshield-api", "fraudshield-ml", "fraudshield-frontend"}
)
TOKEN_ALLOWED = frozenset({"fraudshield-prometheus"})  # Kubernetes service discovery
ALLOWED_VOLUMES = frozenset(
    {"configMap", "csi", "downwardAPI", "emptyDir", "ephemeral", "persistentVolumeClaim",
     "projected", "secret"}
)  # fmt: skip
ALLOWED_ADDED_CAPABILITIES = frozenset({"NET_BIND_SERVICE"})
SECCOMP_TYPES = frozenset({"RuntimeDefault", "Localhost"})
SPOOL_OWNER, SPOOL_VOLUME = "fraudshield-api", "spool"


def name_of(manifest: Manifest) -> str:
    return f"{manifest['kind']}/{manifest['metadata']['name']}"


def pod_labels(workload: Manifest) -> dict[str, str]:
    return dict(workload["spec"]["template"]["metadata"].get("labels", {}))


def selects(selector: Manifest, labels: dict[str, str]) -> bool:
    """matchLabels/matchExpressions semantics for the operators used in these manifests."""
    if not all(labels.get(key) == value for key, value in selector.get("matchLabels", {}).items()):
        return False
    for expression in selector.get("matchExpressions", []):
        present = expression["key"] in labels
        value = labels.get(expression["key"])
        operator = expression["operator"]
        if operator == "In" and value not in expression["values"]:
            return False
        if operator == "NotIn" and value in expression["values"]:
            return False
        if operator == "Exists" and not present:
            return False
        if operator == "DoesNotExist" and present:
            return False
    return True


# --- pod template ----------------------------------------------------------------------------


def pod_problems(workload: Manifest) -> Iterator[str]:
    where = name_of(workload)
    spec = workload["spec"]["template"]["spec"]
    pod_context = spec.get("securityContext", {})
    for key in ("hostNetwork", "hostPID", "hostIPC"):
        if spec.get(key):
            yield f"{where}: {key} is not allowed (restricted)"
    for volume in spec.get("volumes", []):
        kinds = set(volume) - {"name"}
        if not kinds <= ALLOWED_VOLUMES:
            yield f"{where}: volume {volume['name']} of type {sorted(kinds)} is not allowed"
    account = spec.get("serviceAccountName", "default")
    if account == "default":
        yield f"{where}: uses the default service account"
    if account not in TOKEN_ALLOWED and spec.get("automountServiceAccountToken") is not False:
        yield f"{where}: automountServiceAccountToken must be false"
    for container in spec.get("containers", []) + spec.get("initContainers", []):
        yield from container_problems(where, container, pod_context)


def container_problems(where: str, container: Manifest, pod_context: Manifest) -> Iterator[str]:
    here = f"{where} container {container['name']}"
    context = container.get("securityContext", {})

    def effective(key: str) -> Any:
        return context.get(key, pod_context.get(key))

    if effective("runAsNonRoot") is not True:
        yield f"{here}: runAsNonRoot must be true"
    if effective("runAsUser") == 0:
        yield f"{here}: runAsUser must not be 0"
    if (effective("seccompProfile") or {}).get("type") not in SECCOMP_TYPES:
        yield f"{here}: seccompProfile must be RuntimeDefault or Localhost"
    if context.get("privileged"):
        yield f"{here}: privileged is not allowed"
    if context.get("allowPrivilegeEscalation") is not False:
        yield f"{here}: allowPrivilegeEscalation must be false"
    capabilities = context.get("capabilities", {})
    if "ALL" not in capabilities.get("drop", []):
        yield f"{here}: capabilities must drop ALL"
    if not set(capabilities.get("add", [])) <= ALLOWED_ADDED_CAPABILITIES:
        yield f"{here}: may add only {sorted(ALLOWED_ADDED_CAPABILITIES)}"
    if context.get("readOnlyRootFilesystem") is not True:
        yield f"{here}: readOnlyRootFilesystem must be true"
    resources = container.get("resources", {})
    if not {"cpu", "memory"} <= set(resources.get("requests", {})):
        yield f"{here}: needs cpu and memory requests"
    if "memory" not in resources.get("limits", {}):
        yield f"{here}: needs a memory limit"
    for probe in ("livenessProbe", "readinessProbe"):
        if probe not in container:
            yield f"{here}: needs a {probe}"
    yield from image_problems(here, container["image"])


def image_problems(here: str, image: str) -> Iterator[str]:
    if image.startswith(OWN_IMAGE_PREFIX):
        if ":" in image or "@" in image:
            yield f"{here}: FraudShield image {image} must be untagged; deploy pins the digest"
    elif "@sha256:" not in image:
        yield f"{here}: third-party image {image} must be pinned by digest"


# --- namespace-wide --------------------------------------------------------------------------


def namespace_problems(manifests: list[Manifest]) -> Iterator[str]:
    by_kind: dict[str, list[Manifest]] = {}
    for manifest in manifests:
        by_kind.setdefault(manifest["kind"], []).append(manifest)
    namespaces = by_kind.get("Namespace", [])
    if len(namespaces) != 1:
        yield f"expected exactly one Namespace, found {len(namespaces)}"
    for namespace in namespaces:
        labels = namespace["metadata"].get("labels", {})
        if labels.get("pod-security.kubernetes.io/enforce") != "restricted":
            yield f"{name_of(namespace)}: must enforce the restricted Pod Security profile"
    policies = by_kind.get("NetworkPolicy", [])
    if not any(
        p["spec"]["podSelector"] == {}
        and set(p["spec"].get("policyTypes", [])) == {"Ingress", "Egress"}
        and not p["spec"].get("ingress")
        and not p["spec"].get("egress")
        for p in policies
    ):
        yield "no default-deny NetworkPolicy for ingress and egress"
    allow_policies = [p for p in policies if p["spec"]["podSelector"] != {}]
    budgets = by_kind.get("PodDisruptionBudget", [])
    scaled = {
        hpa["spec"]["scaleTargetRef"]["name"] for hpa in by_kind.get("HorizontalPodAutoscaler", [])
    }
    for workload in (m for m in manifests if m["kind"] in WORKLOAD_KINDS):
        labels, name = pod_labels(workload), workload["metadata"]["name"]
        if not any(selects(p["spec"]["podSelector"], labels) for p in allow_policies):
            yield f"{name_of(workload)}: no NetworkPolicy allows any traffic for its pods"
        if not any(selects(b["spec"]["selector"], labels) for b in budgets):
            yield f"{name_of(workload)}: no PodDisruptionBudget covers its pods"
        if name in HPA_REQUIRED and name not in scaled:
            yield f"{name_of(workload)}: needs a HorizontalPodAutoscaler"


def spool_problems(manifests: list[Manifest]) -> Iterator[str]:
    api = [
        m for m in manifests if m["kind"] in WORKLOAD_KINDS and m["metadata"]["name"] == SPOOL_OWNER
    ]
    claims = {m["metadata"]["name"]: m for m in manifests if m["kind"] == "PersistentVolumeClaim"}
    for workload in api:
        volumes = {v["name"]: v for v in workload["spec"]["template"]["spec"].get("volumes", [])}
        spool = volumes.get(SPOOL_VOLUME)
        if spool is None or "persistentVolumeClaim" not in spool:
            yield f"{name_of(workload)}: spool must be a PVC, not ephemeral (ADR 0090)"
            continue
        claim = claims.get(spool["persistentVolumeClaim"]["claimName"])
        if claim is None or "ReadWriteMany" not in claim["spec"].get("accessModes", []):
            yield f"{name_of(workload)}: spool claim must exist with ReadWriteMany (ADR 0090)"


def problems(manifests: list[Manifest]) -> list[str]:
    found = list(namespace_problems(manifests)) + list(spool_problems(manifests))
    for manifest in manifests:
        if manifest["kind"] in WORKLOAD_KINDS:
            found.extend(pod_problems(manifest))
    return found


def main() -> int:
    manifests = [doc for doc in yaml.safe_load_all(sys.stdin) if doc]
    found = problems(manifests)
    for problem in found:
        print(problem, file=sys.stderr)
    workloads = sum(m["kind"] in WORKLOAD_KINDS for m in manifests)
    print(f"k8s-policy: {len(manifests)} objects, {workloads} workloads, {len(found)} problem(s)")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
