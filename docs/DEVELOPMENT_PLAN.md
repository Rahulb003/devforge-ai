# Development plan

**Last updated:** 2026-10-03

The order the remaining work should be done in, and why that order. Current status per feature is in
`docs/PROGRESS.md`; the compliance view is `docs/REQUIREMENTS_MATRIX.md`.

---

## Where things stand

Done: the foundation, identity, multi-tenancy, projects, tasks with a board and sprints, the gateway,
the event backbone, and notifications. Four services carry behaviour; seven are two-file scaffolds.
**Roughly 25% of the specification, weighted by effort.**

The remaining 75% is mostly the AI platform and the seven service domains.

---

## Sequencing principles

These are the reasons the order below is what it is, rather than simply "the next phase number".

1. **Prove the foundation before building on it.** The event backbone was written for three phases
   before anything consumed it; when a consumer finally arrived it immediately exposed two producer
   defects. Eight more services are meant to communicate over that backbone, so proving it first was
   worth a commit of its own.
2. **A feature that cannot be reached by clicking is not finished.** Backend-only work accumulates
   silently and the integration cost is paid later, all at once.
3. **Security prerequisites come before the features that need them.** §37's sandbox gates the IDE and
   AI phases. Building them first would mean either a long wait for integration or, worse, shipping
   something that executes code on the application host.
4. **The thing most likely to be wrong goes first within a phase.** Usually the authorization model
   and the failure behaviour — both are expensive to retrofit and cheap to decide up front.

---

## Phase order

### Next: operational verification (small, unblocks everything)

The build is green but the deployment path has never run. One session's work, and it de-risks every
phase after it:

- Build the Docker images and bring up the Compose stack; smoke-test it.
- Run the CI pipeline for real.
- Stand up PostgreSQL and Kafka together and verify `FOR UPDATE SKIP LOCKED` under concurrent
  publishers — the largest untested dependency in the system (see `TEST_PLAN.md` Priority 1).

Doing this now rather than later matters because the `repackage` defect proved "it builds" and "it
runs" are different claims here.

### ~~Then: git-service (§7)~~ — DONE

Built as a **self-hosted** git backend on JGit: real repositories, browse, commit, branch and
diff, with 107 tests and a live run through the gateway. The two decisions it forced:

- **JGit, not a git binary.** Shelling out would build command lines from attacker-supplied
  branch names and paths.
- **Self-hosted first, provider integration later.** A GitHub integration needs credentials that
  are not available here, and faking its responses was not an option — so the capability that
  could be built honestly was built, and the other is still listed as missing.

Still outstanding in this domain: pull requests, push over HTTP/SSH, and third-party providers.

### The original plan for this phase, for reference

The first genuinely new domain, and the right one to take next because almost everything else depends
on it. AI features need code to read; review needs diffs; documentation needs a repository.

Decide before writing an endpoint:

- **Where repositories live.** Cloning into the service's filesystem makes it stateful and makes
  storage an operational problem; proxying a provider's API avoids both but limits what can be done
  offline.
- **How untrusted content is handled.** Filenames, branch names and commit messages are
  attacker-supplied. They must never be interpolated into a shell command or a filesystem path.
- **Rate and size limits**, before the first large repository arrives rather than after.

### Then: the §37 sandbox

Not a product feature, which is exactly why it is easy to defer and expensive to defer. Required:
isolation from the host, no ambient credentials, no network by default, and hard CPU, memory and
wall-clock limits. It gates the IDE and AI phases, so it comes before them.

An escape-attempt test suite is part of the deliverable, not a follow-up.

### Then: review-service (§8)

Static analysis and quality gates over real diffs from git-service. Deliberately before the AI
features: it establishes how findings are modelled, surfaced and dismissed, and AI-generated review
comments can then reuse that rather than inventing a parallel shape.

### Then: the AI platform (§6, §15, §16) — the largest phase

Bigger than everything built so far combined. Sub-order within it:

1. **Model access and cost control.** A ceiling per request and per tenant, enforced server-side.
2. **Explanation and generation**, suggestion-only, with no authority to act.
3. **RAG over a repository**, which needs git-service and chunking/embedding storage.
4. **Agents**, last, because an agent that can act needs every control above it to already exist.

Three rules that must hold from the first commit, not be added later:

- **Repository and model content is untrusted input.** A file that says "ignore your instructions"
  is a prompt-injection payload, not a comment.
- **AI never has authority.** No merging, deploying, granting access or deleting. A suggestion needs
  a human decision to take effect.
- **Model output is untrusted input to everything downstream of it.**

### Then, in roughly this order

- **documentation-service (§10)** — straightforward once git-service exists.
- **chat-service (§11)** — brings the first real-time channel. The notification bell should move onto
  it rather than keeping its own poll, and channel membership needs the same cross-user proof
  notifications have.
- **deployment-service (§13)** — the highest-risk domain, because it holds credentials into a
  customer's cloud account. Takes its scoping and secret-handling decisions from the threat model
  before any endpoint is written.
- **analytics-service (§14)** — a natural second Kafka consumer, and the first thing that needs
  historical event replay to work.
- **The IDE surface (§9)** — fronts git, AI and review; needs the sandbox.

### Throughout, not as a phase

- **project-service should stage the events its `EventTypes` constants already declare.** Eight are
  declared and none is produced, which is why notification-service has no project listener and why
  "you were added to a project" does not exist.
- Observability: tracing, dashboards, alerting.
- The `TEST_PLAN.md` gap list, in its stated priority order.
- Either implement `config-server` and `discovery-server` or **delete them**. They are empty Boot
  apps that Compose wires every service to through env vars nothing reads, which is actively
  misleading.

---

## Things deliberately not planned

- **RabbitMQ**, unless a genuine work-queue requirement appears. If one does — dispatching sandboxed
  execution jobs is the plausible candidate — the decision gets recorded in `EVENT_CATALOG.md` §1
  alongside the removal.
- **Enforcing email verification.** Currently a deliberate product decision; revisit as a product
  question, not a technical one.
- **Microservice splitting for its own sake.** Four services is already more boundaries than the
  current feature set strictly needs; the remaining domains justify their own, but nothing should be
  split further without a reason.

---

## How to leave the repository between sessions

1. Update `docs/PROGRESS.md` — status, test counts, and the numbered decision table.
2. Record any architectural decision with its *reasoning*, not just its outcome.
3. Mark anything unexecuted `UNVERIFIED`, and say what it would take to verify.
4. Leave the build green, and say so with the numbers.
