"""Tests for the rendered-manifest policy check (infrastructure/checks/k8s_policy.py).

One compliant namespace is built, then broken one rule at a time: each break must be reported.
The last test runs the check over both real overlays.
"""

from __future__ import annotations

import copy
import subprocess
from collections.abc import Callable
from pathlib import Path
from typing import Any

import k8s_policy as kp
import pytest
import yaml

ROOT = Path(__file__).resolve().parents[3]
Manifest = dict[str, Any]


def compliant() -> list[Manifest]:
    labels = {"app.kubernetes.io/name": "fraudshield-api"}
    return [
        {
            "kind": "Namespace",
            "metadata": {
                "name": "fraudshield",
                "labels": {"pod-security.kubernetes.io/enforce": "restricted"},
            },
        },
        {
            "kind": "NetworkPolicy",
            "metadata": {"name": "default-deny-all"},
            "spec": {"podSelector": {}, "policyTypes": ["Ingress", "Egress"]},
        },
        {
            "kind": "NetworkPolicy",
            "metadata": {"name": "api"},
            "spec": {"podSelector": {"matchLabels": labels}, "ingress": [{}]},
        },
        {
            "kind": "PodDisruptionBudget",
            "metadata": {"name": "api"},
            "spec": {"maxUnavailable": 1, "selector": {"matchLabels": labels}},
        },
        {
            "kind": "HorizontalPodAutoscaler",
            "metadata": {"name": "api"},
            "spec": {"scaleTargetRef": {"kind": "Deployment", "name": "fraudshield-api"}},
        },
        {
            "kind": "PersistentVolumeClaim",
            "metadata": {"name": "spool"},
            "spec": {"accessModes": ["ReadWriteMany"]},
        },
        {
            "kind": "Deployment",
            "metadata": {"name": "fraudshield-api"},
            "spec": {
                "template": {
                    "metadata": {"labels": labels},
                    "spec": {
                        "serviceAccountName": "fraudshield-api",
                        "automountServiceAccountToken": False,
                        "securityContext": {
                            "runAsNonRoot": True,
                            "runAsUser": 10001,
                            "seccompProfile": {"type": "RuntimeDefault"},
                        },
                        "volumes": [
                            {"name": "tmp", "emptyDir": {}},
                            {"name": "spool", "persistentVolumeClaim": {"claimName": "spool"}},
                        ],
                        "containers": [
                            {
                                "name": "api",
                                "image": "ghcr.io/mariusbayizere/fraudshield-api",
                                "resources": {
                                    "requests": {"cpu": "1", "memory": "1Gi"},
                                    "limits": {"memory": "2Gi"},
                                },
                                "livenessProbe": {},
                                "readinessProbe": {},
                                "securityContext": {
                                    "allowPrivilegeEscalation": False,
                                    "readOnlyRootFilesystem": True,
                                    "capabilities": {"drop": ["ALL"]},
                                },
                            }
                        ],
                    },
                }
            },
        },
    ]


def pod(manifests: list[Manifest]) -> Manifest:
    spec: Manifest = manifests[-1]["spec"]["template"]["spec"]
    return spec


def container(manifests: list[Manifest]) -> Manifest:
    first: Manifest = pod(manifests)["containers"][0]
    return first


def test_compliant_namespace_has_no_problems() -> None:
    assert kp.problems(compliant()) == []


BREAKS: list[tuple[str, Callable[[list[Manifest]], object], str]] = [
    ("root", lambda m: pod(m)["securityContext"].update(runAsNonRoot=False), "runAsNonRoot"),
    ("uid 0", lambda m: container(m)["securityContext"].update(runAsUser=0), "runAsUser"),
    ("seccomp", lambda m: pod(m)["securityContext"].pop("seccompProfile"), "seccompProfile"),
    ("privileged", lambda m: container(m)["securityContext"].update(privileged=True), "privileged"),
    ("escalation", lambda m: container(m)["securityContext"].pop("allowPrivilegeEscalation"),
     "allowPrivilegeEscalation"),
    ("caps kept", lambda m: container(m)["securityContext"]["capabilities"].update(drop=[]),
     "drop ALL"),
    ("caps added",
     lambda m: container(m)["securityContext"]["capabilities"].update(add=["NET_ADMIN"]),
     "may add only"),
    ("writable root", lambda m: container(m)["securityContext"].pop("readOnlyRootFilesystem"),
     "readOnlyRootFilesystem"),
    ("host network", lambda m: pod(m).update(hostNetwork=True), "hostNetwork"),
    ("hostPath", lambda m: pod(m)["volumes"].append({"name": "h", "hostPath": {"path": "/"}}),
     "hostPath"),
    ("token", lambda m: pod(m).pop("automountServiceAccountToken"), "automountServiceAccountToken"),
    ("default SA", lambda m: pod(m).pop("serviceAccountName"), "default service account"),
    ("no requests", lambda m: container(m)["resources"].pop("requests"), "requests"),
    ("no memory limit", lambda m: container(m)["resources"].pop("limits"), "memory limit"),
    ("no liveness", lambda m: container(m).pop("livenessProbe"), "livenessProbe"),
    ("no readiness", lambda m: container(m).pop("readinessProbe"), "readinessProbe"),
    ("own image tagged", lambda m: container(m).update(image=kp.OWN_IMAGE_PREFIX + "api:latest"),
     "must be untagged"),
    ("third-party by tag", lambda m: container(m).update(image="prom/prometheus:v3.14.0"),
     "pinned by digest"),
    ("PSA not enforced", lambda m: m[0]["metadata"]["labels"].clear(), "restricted"),
    ("no default deny", lambda m: m.pop(1), "default-deny"),
    ("default deny allows", lambda m: m[1]["spec"].update(ingress=[{}]), "default-deny"),
    ("no allow policy", lambda m: m.pop(2), "no NetworkPolicy"),
    ("no PDB", lambda m: m.pop(3), "PodDisruptionBudget"),
    ("no HPA", lambda m: m.pop(4), "HorizontalPodAutoscaler"),
    ("spool on emptyDir",
     lambda m: pod(m)["volumes"].__setitem__(1, {"name": "spool", "emptyDir": {}}),
     "not ephemeral"),
    ("spool claim RWO", lambda m: m[5]["spec"].update(accessModes=["ReadWriteOnce"]),
     "ReadWriteMany"),
]  # fmt: skip


@pytest.mark.parametrize(("case", "breaks", "fragment"), BREAKS, ids=[b[0] for b in BREAKS])
def test_each_break_is_reported(
    case: str, breaks: Callable[[list[Manifest]], object], fragment: str
) -> None:
    manifests = copy.deepcopy(compliant())
    breaks(manifests)
    found = kp.problems(manifests)
    assert any(fragment in problem for problem in found), (case, found)


def test_selector_expressions() -> None:
    labels = {"fraudshield.io/datastore": "redis"}
    expression = {"key": "fraudshield.io/datastore", "operator": "In", "values": ["redis"]}
    assert kp.selects({"matchExpressions": [expression]}, labels)
    assert not kp.selects({"matchExpressions": [dict(expression, values=["kafka"])]}, labels)
    assert not kp.selects({"matchExpressions": [{"key": "x", "operator": "Exists"}]}, labels)
    assert kp.selects({"matchExpressions": [{"key": "x", "operator": "DoesNotExist"}]}, labels)


@pytest.mark.parametrize("overlay", ["staging", "production"])
def test_overlays_meet_the_policy(overlay: str) -> None:
    kustomize = ROOT / "infrastructure/bin/kustomize"
    directory = ROOT / "infrastructure/k8s/overlays" / overlay
    rendered = subprocess.run(  # noqa: S603 - fixed pinned tool and repository path
        [str(kustomize), "build", str(directory)], capture_output=True, text=True, check=True
    ).stdout
    manifests = [doc for doc in yaml.safe_load_all(rendered) if doc]
    assert kp.problems(manifests) == []
