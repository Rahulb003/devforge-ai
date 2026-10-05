# Code execution sandbox (§37)

**Last updated:** 2026-10-03
**Status:** `MISSING` — designed, not built. **Nothing in DevForge executes developer code today**,
which is the correct state until this exists.

This document says what a sandbox must guarantee, how an implementation will be judged, and why
one has not been built yet. It is here so the controls arrive with the feature rather than after it.

---

## 1. Why this is not implemented

Real isolation is an operating-system facility: containers with seccomp and dropped capabilities, a
microVM, or a separate machine. None is available in this environment — there is no Docker daemon,
no Podman, and no hypervisor (checked, not assumed).

A sandbox that cannot isolate is worse than no sandbox, because people trust it. Implementing
something that *looks* like one — a thread with a timeout, a `ProcessBuilder` with a scrubbed
environment, a bytecode verifier — would be the clearest possible case of the fake functionality
this project forbids. Java's `SecurityManager`, the historical answer, is deprecated for removal
and was never a security boundary against hostile code anyway.

So the honest position is: the contract and its verification suite are specified here, and the
feature stays marked `MISSING` until a backend exists that can satisfy them.

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
