# Code execution sandbox (§37)

**Last updated:** 2026-10-27
**Status:** `IMPLEMENTED` — a container per run (backend 2 in section 5), every guarantee below
proven by an escape test against a real engine. **Off by default** in every deployment shape.

This document says what the sandbox must guarantee, how it is judged, and how the implementation
meets it. It was written before the code, so the controls arrived with the feature rather than
after it.

---

## 1. What exists

Until 2026-10-27 there was no container engine here, so this was designed and deliberately not
built: a sandbox that cannot isolate is worse than none, because people trust it. Once Docker was
available it was built to this contract.

| Piece | Where |
|---|---|
| Sandbox contract | `deployment-service` `sandbox/Sandbox.java` |
| Container-per-run backend | `sandbox/DockerSandbox.java` — every flag is annotated with the guarantee it serves |
| Escape tests, G1–G12 | `DockerSandboxEscapeTest` — 11 tests against a real engine; skipped where there is none, **failed** in CI if there is none |
| Execution API, audit, quota | `execution/` — `POST/GET /api/v1/organizations/{o}/projects/{p}/executions` |
| Pipelines | `pipeline/` — a repository's `.devforge/pipeline.yml`, each step in its own container with the repository's text files unpacked from a tar on stdin; the step script travels inside the archive, never on the docker command line |
| UI | Project board → **Run code**; repository → **Pipelines** |

**Why deployment-service and not a new execution-service** (section 3 names one): deployment-service
was an empty scaffold with its image, compose entry, manifest and gateway path already in place, and
build and pipeline steps (section 4) are the sandbox's main consumer. One service owning everything
that runs untrusted code keeps the container-engine access in one place.

**How it is configured.** `DEVFORGE_SANDBOX_ENABLED=true` and a docker CLI that reaches an engine.
Off, every run is refused with 503 and recorded as `REFUSED` (G12). Compose and the Kubernetes
manifests leave it off: turning it on means giving deployment-service an engine, and an engine that
can start containers is close to root on its host. Do that only on a host dedicated to it (backend 3
below describes the shape).

**Residual risk, stated rather than hidden:**

- A container shares the host kernel. A kernel exploit escapes it; a microVM (backend 1) would not
  share one. This is why backend 1 stays preferred.
- Docker's default seccomp profile applies; no custom one is loaded.
- Two bugs were found while building it, both now covered by tests: Python buffered its output, so a
  killed program lost everything it had printed; and the in-container time limit never fired
  because the program ran as PID 1, which ignores signals it has no handler for (fixed with
  `--init`).

---

## 2. What must be true before any execution feature ships

An implementation is acceptable only if **every** guarantee below holds, each proven by a test that
attempts to break it. A guarantee with no escape-attempt test is an assumption, not a control.

| # | Guarantee | Escape attempt that must fail |
|---|---|---|
| G1 | No network access by default | DNS lookup; outbound TCP to a listener on the host; connection to the platform's own API; connection to a cloud metadata endpoint (`169.254.169.254`) |
| G2 | No access to the host filesystem | read `/etc/passwd`, the application jar, another tenant's work directory; write outside the work directory; follow a symlink out of it |
| G3 | No ambient credentials | read the environment for tokens; read a mounted service-account file; reach a metadata service (G1) |
| G4 | Bounded CPU | a busy loop is killed at the limit |
| G5 | Bounded memory | an allocation loop is killed, and kills nothing else |
| G6 | Bounded wall-clock time | `sleep` beyond the limit is killed |
| G7 | Bounded output | a program writing endless stdout is truncated, not allowed to exhaust disk or memory |
| G8 | Bounded processes | a fork bomb cannot exhaust the host |
| G9 | No persistence between runs | a file written by one run is absent in the next |
| G10 | No access to other tenants' data | every path above, attempted across tenant boundaries |
| G11 | Termination is reliable | a process that ignores SIGTERM is still killed |
| G12 | Failure is closed | if the sandbox backend is unreachable, execution is **refused**, never run locally |

G12 is the one most easily lost in a refactor. The fallback for "no sandbox available" must be an
error, and the test for it must assert that nothing executed.

---

## 3. Shape of the contract

Execution is a request to a **separate system**, never an in-process call:

```
client → api-gateway → execution-service → [sandbox backend]
                            │
                            └── records the request, the limits applied, and the outcome
```

- **The request carries limits**, and the service clamps them to a per-tenant maximum. A client
  that asks for a 10-minute run gets the maximum, not what it asked for.
- **Every run is audited**: who, what, which limits, how it ended. An execution feature with no
  audit trail cannot be investigated after the fact.
- **A per-tenant quota** is enforced server-side. Without it, execution is a denial-of-service
  primitive against the platform's own cost.
- **Results are data, not instructions.** Output is untrusted: it is attacker-controlled text that
  must not be interpolated into a shell, a prompt, or a page without escaping.

## 4. What it unblocks, and what it does not

Blocked on this: running tests from the IDE, AI-generated test execution, build and pipeline steps
that run project code (§13), and anything that evaluates a snippet.

**Not** blocked on this, and deliberately built first: static analysis. Reading a repository and
matching patterns is not executing it, so review-service (§8) needs no sandbox — which is why it
comes earlier in `docs/DEVELOPMENT_PLAN.md` than the plan's original ordering implied.

Also not blocked: AI features that only *read* code. Those have a different hazard — prompt
injection from repository content — covered in `docs/THREAT_MODEL.md` §7.

## 5. Recommended backend when one becomes available

In preference order:

1. **A microVM per run** (Firecracker-class). Strongest boundary, and the per-run cost is
   acceptable because runs are short and bounded.
2. **A container per run** with no network namespace, a read-only root, a tmpfs work directory,
   all capabilities dropped, a seccomp profile, a non-root user, and cgroup CPU/memory/pid limits.
3. **A dedicated host pool**, isolated at the network level, treated as compromised by default.

In-process execution is not on this list at any priority.
