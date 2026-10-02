# SARSA REST Bug Hunter

**Autonomous discovery of deep, stateful API bugs using reinforcement learning (pure Java, no ML libraries).**

A small on-policy RL agent drives a REST API through a hand-written adapter, with a
5xx status code as its only feedback. No OpenAPI spec, no recorded traffic, no LLM —
and crucially, **no defect knowledge in the state or the reward**: nothing tells the
agent that negative values are interesting, or that `PATCH` and `PUT` behave
differently. It has to work out on its own that some bugs only exist at the end of a
precise sequence of dependent calls.

> **Branch status:** this branch (`2-hidden-bugs`) carries two newly planted defects,
> the measurement scaffolding for them, and three reward/environment fixes. Below: the
> setup, two separate difficulty measurements, and a controlled learning-vs-random
> comparison over ~300 trials.
>
> Headline, stated honestly — over a full 50,000-episode run at `ε=0.3` the agent finds
> **Defect 1 in 91.1% of runs (n=45) and Defect 2 in 17.8%**, against **41.7% and 0%**
> for random search at the same episode budget (D1 p = 0.009). At a matched *request*
> budget the two are a wash, for a reason that is a property of the harness rather
> than of the agent. Sweeps over network size (8/16/32 units), exploration rate and
> learning rate show capacity buying **efficiency, not capability** — a 2.4×
> request-cost spread with no detectable change in defect discovery. See
> [Results](#results).

---

## Setting the scene

### The target

A deliberately ordinary Spring Boot CRUD API with four resources. They form a chain,
where each one can only be created if its parent already exists:

```
Item  ──▶  Price  ──▶  Discount  ──▶  Points
         needs        needs          needs
        item id      price id     discount id
```

Each resource exposes the usual verbs: `GET`, `GET` all, `POST`, `PUT`, `PATCH`,
`DELETE`. Nothing exotic. The interesting part is that **values propagate down the
chain by reference**: a Discount holds the actual stored Price object, and a Point
holds the actual stored Discount object. Change a price after the fact and everything
downstream sees the new value.

### The two hidden defects

Both live in `DELETE /api/points/{id}`, and neither can be triggered by a single
request. They need the chain to be assembled *and* poisoned first.

| | Condition | Crash |
|---|---|---|
| **Defect 1** | the Price linked (via Discount) to this Point is negative | `IllegalStateException` |
| **Defect 2** | that Price is negative **and** the Discount is negative | `ArithmeticException` |

Defect 2's condition is a strict subset of Defect 1's, so it is tested first —
otherwise it would be unreachable dead code.

### Why they are hard to reach

The obvious route is blocked. You cannot simply post a negative value:

- `POST /prices` rejects `price < 0` with a 500 guard — nothing gets stored.
- `POST /discounts` rejects `discount < 0` the same way.

So the negative value has to be written *after* creation, and only `PATCH` reliably
does that. `PATCH` mutates the stored object in place, so every existing link sees
the change; `PUT` **replaces** the stored object, leaving anything created earlier
pointing at the old one.

That distinction decides each step separately:

- **The negative price** can be planted by `PATCH /prices` at any time, or by
  `PUT /prices` — but only if the Discount is created *afterwards*, since `POST` links
  whatever object is in the store at that moment.
- **The negative discount** has exactly one route: `PATCH /discounts`. `PUT` is a dead
  end both ways, because its replacement object carries the client's `{"id":N}` price
  stub (value `0.0`, so the price is no longer negative), and an existing Point still
  references the discount object the `PUT` discarded.

The agent has to discover all of that without being told any of it exists.

The shortest route to Defect 2 is seven successful requests in strict order:

```
POST   /items                 create the root
POST   /prices                link to the item          (must be >= 0 to be accepted)
PATCH  /prices/{id}           drive the price negative  (in place, so links see it)
POST   /discounts             link to the price         (must be >= 0 to be accepted)
PATCH  /discounts/{id}        drive the discount negative
POST   /points                link to the discount
DELETE /points/{id}           boom
```

Defect 1 is the same path with the `PATCH /discounts` step removed — six requests.

---

## Foundation numbers

The agent does not pick HTTP requests directly. It turns **dials** — method,
endpoint, target field, mutation strategy, intensity — and then spends an action
on `EXECUTE` to actually fire the configured request. That is what gives it a
small, reusable action space instead of a combinatorial one.

| | |
|---|---|
| Actions | **32** (6 method + 4 endpoint + 11 field + 8 strategy + 2 intensity + 1 `EXECUTE`) |
| State features | **15**, all normalised to [0, 1] |
| Network | 15 → tanh(32) → 32 Q-values, single hidden layer |
| Steps per episode | **35** |
| Distinct request shapes reachable | **1,068** |
| Raw action sequences per episode | 32³⁵ ≈ **4.8 × 10⁵²** |
| Minimum actions to reach Defect 1 | **17** of the 35, in order |
| Minimum actions to reach Defect 2 | **21** of the 35, in order |

### How hard is "hard"? — two different questions

There are two difficulty numbers here and they answer different questions. Mixing
them up is the easiest way to overstate a result, so both are stated separately.

#### 1. Within a single episode, from an empty server

How unlikely is it to assemble the whole chain inside one 35-step episode on a clean
server? Measured — not hand-estimated — with a faithful in-memory model of the four
controllers (including the object-reference semantics that separate `PATCH` from
`PUT`), the payload generator, the action space and the action mask, sampled by
step-aware multilevel splitting over 7 seeds × 12M trials per level:

| | Chance per 35-step episode | Episodes to expect one hit |
|---|---|---|
| **Defect 1** | 1.4 × 10⁻¹⁵ | 1 in 7.2 × 10¹⁴ (range 4.5e14 – 1.1e15) |
| **Defect 2** | 2.3 × 10⁻¹⁹ | 1 in 4.3 × 10¹⁸ (range 2.7e18 – 9.7e18) |

**One extra dependency makes the single-episode construction ~6,000× less likely.**
Almost all of that comes from the `PATCH /discounts` step — the one step with no
alternative route. It has to be the right method, on the right endpoint, with
`STRATEGY_NEGATIVE` selected, on a field that actually emits a negative number: one
narrow tuple out of 1,068 request shapes, landing inside a window where the rest of
the chain is already standing.

**This is not the task the harness sets, and it must not be quoted as a speed-up.**
`executeEpisode()` clears the four local id handles but does not reset the server.
`items`, `prices` and `discounts` are static maps; `points` is an instance field on a
singleton controller. All four persist for the entire run. `GET_ALL` re-acquires
existing objects, and `PATCH` mutates stored objects in place — so a negative price
written in episode 300 is still negative in episode 40,000, and any Discount later
created on it is poisoned too. Chain assembly amortises across the run. The number
above describes one episode on a clean server; the agent is given 50,000 episodes on
an accumulating one.

Persistent state is a legitimate design choice for API testing. The mismatch is in
the comparison, not in allowing persistence.

#### 2. The actual baseline: random policy, same harness

So the baseline that matters was measured on the real system, not modelled.
**40 independent trials**, each a fresh API JVM (resetting the maps *and* the id
counters), `EPSILON = 1.0` (uniform over the valid mask), 50,000 episodes:

| | Random policy, 50k episodes, n=40 |
|---|---|
| **Defect 1 found** | **5 / 40 = 12.5%** — at episodes 3,103 · 3,237 · 16,138 · 28,486 · 41,382 |
| **Defect 2 found** | **1 / 40 = 2.5%** — at episode 2,220 |
| Both found | 1 / 40 |
| Trivial `DELETE+POINTS` 500 | 40 / 40, median episode 5,394 |
| Requests used | ~32,000 per trial |

Random search found Defect 1 as early as episode 3,103, so a single trained run
reporting a discovery in the low thousands of episodes is indistinguishable from
chance. Claims need multiple seeds against this floor.

For reference, the modelled prediction for this setup was 12% / 3% / 1.7%, so the model
and the real system agree closely — but the right-hand column is the evidence.

> **These numbers are for the original code, before the windowed server reset.** On the
> current code the same random policy at 50,000 episodes finds Defect 1 in **41.7%** of
> runs — the reset *helps* random search, because clearing the store means `GET_ALL`
> returns a freshly created object each window instead of the same never-poisoned
> object #1 for the whole run. The current-code figure is the one every arm in
> [Results](#results) is measured against; this section is kept because it is what the
> single-episode model was validated on.

### One mechanic worth knowing before interpreting any run

**The agent can un-arm its own target.** A later `PATCH` with a valid or boundary
strategy writes a non-negative value back over the poisoned one. This lowers
*instantaneous* readiness and future hit rate; it cannot lower cumulative discoveries,
since a defect already found stays found.

(A second mechanic — the trivial `DELETE /points` 500 absorbing the novelty reward that
both hidden defects would otherwise earn — was real and is now fixed; see
[how the reward got here](#how-the-reward-got-here).)

### What the state vector cannot see

The 15 features are a hand-designed sufficient statistic for one dimension of the task
and silent about another, and the planted defects live in the silent one.

- **Resource existence** — covered. The four `hasValid*Id` flags summarise the creation
  ladder, which is why a 5-step creation chain is learnable at all.
- **Mutation history** — not covered. Nothing records what has been done *to* those
  resources. `resetMethodOnly()` clears `httpType`, `stepsSinceExecute` and
  `isReadyToExecute` after every request, while `lastMethod` and `lastStatusCall`
  survive — so the agent carries exactly **one execute of memory** ("the last thing I
  did was a PATCH"), with **no endpoint attribution**, since the `endpoint` feature is
  the current dial and gets overwritten by the next `INSPECT_*`.

Walk the Defect 2 chain by what the agent can observe:

| Required action | id-flag state |
|---|---|
| `POST /prices` | (1,0,0,0) |
| **`PATCH /prices` NEGATIVE** | (1,1,0,0) |
| `POST /discounts` | (1,1,0,0) ← same observation |
| **`PATCH /discounts` NEGATIVE** | (1,1,1,0) |
| `POST /points` | (1,1,1,0) ← same observation |
| `DELETE /points` | (1,1,1,1) |

Two rungs each require two *different* executes at an identical observation. A
deterministic policy over that state can only choose one action per state, so the
second has to arrive from exploration noise rather than from the policy.

That accounts for the measured asymmetry. **Defect 1 needs one inserted mutation** —
one-deep memory is marginally enough and `ε=0.3` supplies the insertion often, giving
90% discovery and up to 9 simultaneously armed chains. **Defect 2 needs two insertions
on two different rungs plus memory of both** — two-deep memory does not exist, and it
is never armed in any sample. This is reasoning from the code and the arming data
rather than a measurement, but it is falsifiable: adding mutation memory should move
Defect 2 and nothing else should.

This is the project's actual result, and it is a stronger claim than "no domain
knowledge" ever was: **state design is the binding constraint on reachable bug depth.**
Where a sufficient statistic was supplied the agent learns the chain and beats random
search at matched episodes (p = 0.009); where none was supplied it cannot, with
exploration rate, learning rate, capacity, reward-key granularity, reward starvation
and shape coverage all independently ruled out by measurement. Representation is the
surviving hypothesis plus a mechanism — not yet a proven cause.

### Honest scope of the design

- **The reward and state contain no defect knowledge.** No sign-of-value feature,
  nothing distinguishing `PATCH` from `PUT`, nothing about reference aliasing. The
  hard part of both defects is unassisted. That claim is accurate and worth making.
- **Domain knowledge does live in the API adapter.** The generator knows the
  endpoints, the JSON field names, and which parent id belongs in which child payload
  (`getItemIdJson`, `getPriceIdJson`, `getDiscountIdJson`). The `hasValid*Id` flags
  are a compact record of ids the agent obtained from real responses — ordinary
  engineering, not an oracle — but they do make the scaffolding steps cheaper. So
  *"learned request selection over a hand-designed API adapter"* is accurate, and
  "no domain knowledge" is not.
- **The curriculum is deliberate.** State and action space grew with endpoint scope
  across branches, and chain depth was increased on purpose. That is curriculum
  learning, declared rather than hidden.

### Known harness defects

Found while measuring the above; both reduce useful exploration:

- **`PATCH /api/points` could never do anything — fixed.** The controller read
  `updates.get("point")` while the model field and every generated payload use
  `"points"`, so the branch was unreachable and that method/endpoint cell of the action
  space was inert. Corrected to `"points"`. This is a change to the system under test;
  it affects neither hidden defect, since neither needs `PATCH /points`.
- **`Intensity.MODERATE` is unreachable — left as is.** `INTENSIFY` selects
  `AGGRESSIVE`, `CONSERVE` selects `MILD`, and no action selects the middle. No
  exploration budget is wasted (there are only two intensity actions, both usable), but
  the generator's MODERATE branches are dead code. Adding a third action would change
  the action count and invalidate comparison with the runs above, so it is deferred.

### Reproducing the experiments

One trial = one fresh API JVM + one agent run at a given epsilon, so trials are
independent in both server state and id counters:

```bash
# stream.sh <port> <firstSeed> <episodes> <nTrials> <epsilon> <outFile>
scratchpad/rand/stream.sh 8080 1001 50000 9 0.3 eps03.txt
```

Streams run in parallel on separate ports — `-Dserver.port=N` on the API and
`-Dsarsa.baseUrl=http://localhost:N/api/` on the agent. Six streams (12 JVMs) saturate
a 12-core machine. For a sweep, give every arm the **same seed set** so the arms are
paired:

```bash
for port_eps in "8080 0.1" "8082 0.2" "8084 0.3"; do
  set -- $port_eps
  scratchpad/rand/stream.sh $1 1001 50000 18 $2 "eps$2.txt" &
done; wait
```

Random-policy baselines are the same script at `epsilon 1.0`. A random arm run long
enough doubles as every shorter budget at once: its first-fire episodes give an
empirical CDF, so the rate at any smaller episode or request budget can be read off
without new trials.

---

## Results

Every arm is the same protocol: a fresh API JVM per trial (resetting the maps *and* the
id counters), one seed per trial, nothing shared between trials. Sweeps use **paired
seeds** — the same seed set in every arm — so comparisons are not confounded by seed
luck. Trials run in parallel against separate API JVMs via `-Dserver.port` and
`-Dsarsa.baseUrl`.

### Headline: full 50,000-episode run

`EPISODES = 50,000`, `GAMMA = 0.99`, `ALPHA = 0.01`, 16 hidden units (see
[capacity](#network-capacity-16-vs-32-hidden-units)), server reset
every 10k, **n = 18 per arm** on paired seeds 1001–1018.

| `ε` | n | Defect 1 | D1 median episode | Defect 2 | Requests/trial | Shapes /1068 | Bug paths |
|---|---|---|---|---|---|---|---|
| 0.1 | 18 | 13/18 — 72.2% | 4,295 | 1/18 — 5.6% | 427,428 | 1053 (min 849) | 225 |
| 0.2 | 18 | 14/18 — 77.8% | 4,571 | 1/18 — 5.6% | 312,593 | 1067 (min 1009) | 231 |
| **0.3** | 18 | **16/18 — 88.9%** | 4,822 | 6/18 — 33.3% † | **233,894** | **1068** (min 1057) | 232 |

Pooled over all three arms: **Defect 1 in 43/54 runs (79.6%), Defect 2 in 8/54
(14.8%)**. † The `ε=0.3` Defect 2 figure here is the original n=18 arm; extended to
n=45 it is 17.8% — see [what was retracted](#what-was-retracted). Defect 2 first-fire episodes at `ε=0.3`: 685 · 7,273 · 9,428 · 10,107 ·
30,241 · 40,530.

`ε=0.3` wins on every axis at once — both defects, shape coverage, *and* request cost.
Cost **falls** as epsilon rises (427k → 313k → 234k requests), because a more
exploratory policy spends fewer steps on greedy re-execution, so there is no trade-off
being made across this range.

**`ε=0.3` is the optimum, not a lower bound.** An `ε=0.35` arm was run later (32
hidden units, n=36) and was worse or flat on everything: D1 94.4% vs 100%, median
discovery 5,318 vs 4,024, cost 161k vs 168k (0.96×), D2 5.6% vs 11.1%. The monotonic
trend across 0.1→0.3 does not continue past it.

### Hyperparameter sweeps — capacity, exploration rate, learning rate

Same protocol throughout: 50,000 episodes, `GAMMA = 0.99`, reset every 10k, one fresh
API JVM per trial, paired seeds within each sweep. Defaults are `ε=0.3`, `α=0.01`,
16 hidden units; each row varies one thing.

| Config | n | Defect 1 | D1 median ep | Defect 2 (95% CI) | Requests/trial | Shapes /1068 |
|---|---|---|---|---|---|---|
| **8** hidden units | 27 | 25/27 — 92.6% | 6,198 | 4/27 — 14.8% (6–33%) | 406,507 | 1068 (min 1042) |
| **16** hidden units | 45 | 41/45 — 91.1% | 5,455 | 8/45 — 17.8% (9–31%) | 239,846 | 1068 (min 1057) |
| **32** hidden units | 18 | 18/18 — 100% | 4,024 | 2/18 — 11.1% (3–33%) | **168,052** | 1068 (min 1065) |
| 32 units, `ε=0.35` | 36 | 34/36 — 94.4% | 5,318 | 2/36 — 5.6% (2–18%) | 161,227 | 1068 (min 1061) |
| 16 units, `α=0.003` | 27 | 25/27 — 92.6% | 7,673 | 2/27 — 7.4% (2–23%) | 338,703 | 1067 (min 1056) |
| 16 units, `α=0.03` | 27 | 21/27 — 77.8% | **3,206** | 3/27 — 11.1% (4–28%) | 161,686 | 1067 (min 1052) |

#### What survives

**Capacity buys efficiency, not capability.** Request cost falls monotonically —
406,507 → 239,846 → 168,052 across 8 → 16 → 32 units, a **2.4× spread** — while Defect
1 stays at 91–100% (no pairwise difference significant) and shape coverage is saturated
at 1,068 in every single arm. Eight units find both defects at rates indistinguishable
from 32; they just cost 2.4× more requests to do it.

That is a direct confirmation of the original design claim that the *state
representation*, not network size, does the heavy lifting here — and it puts a number
on it.

**Learning rate trades reliability for speed and cost**, monotonically:

| As `ALPHA` rises 0.003 → 0.01 → 0.03 | |
|---|---|
| D1 reliability | **falls** — 92.6% → 91.1% → 77.8% |
| D1 discovery speed | **rises** — median episode 7,673 → 5,455 → 3,206 |
| Request cost | **falls** — 339k → 240k → 162k |

**`ε=0.3` is the optimum of its sweep**, not a lower bound: `ε=0.35` was flat or worse
on everything.

#### What was retracted

Two earlier conclusions on this branch did **not** survive a larger sample, and both
failed the same way.

The Defect 2 rate for the default config was first measured at **6/18 = 33.3%**.
Extending that arm to n = 45 brought it to **8/45 = 17.8%** — the original figure was a
small-sample overestimate. Every comparison anchored to it collapsed:

| Claim made earlier | p at n=18 | p at n=45 |
|---|---|---|
| 32 units find Defect 2 less often | 0.012 | **0.134** |
| `α=0.003` finds Defect 2 less often | 0.045 | **0.302** |
| `α=0.03` finds Defect 2 less often | 0.126 | **0.519** |

**No configuration tested moves the Defect 2 rate detectably.** All six arms sit in a
5.6%–17.8% band with overlapping confidence intervals. The "capacity costs depth" and
"`α=0.01` is an interior optimum for the rare defect" readings are both withdrawn, as
is the broader narrative built on them — that configurations converging harder onto
what already pays find the rare defect less often. That pattern is well supported for
*coverage* (the `ε=0.01` collapse to 22 of 1,068 shapes is a large effect at n=40) but
there is no evidence for it in Defect 2 discovery.

The methodological lesson is worth keeping: at n = 18 a ~15% event cannot resolve a gap
below roughly 39 percentage points. Three plausible, mechanistically explainable
findings were manufactured by one undersized arm.

#### What this implies for Defect 2

Defect 2's flatness across capacity, exploration rate and learning rate says it is
**not hyperparameter-limited**. Something structural caps it — most plausibly the
partial observability described under
[what the state vector cannot see](#what-the-state-vector-cannot-see): "this Point's
chain is poisoned" is not representable, so the extra `PATCH /discounts` step cannot be
conditioned on. The next lever for Defect 2 is the state representation or the reward
key, not a knob.

### Diagnosing Defect 2 — why it is not a hyperparameter problem

Sweeping epsilon, learning rate and capacity moved Defect 2 nowhere, so the next step
was to measure *where* it fails rather than guess at another knob. Two measurements,
both read from data a client already has.

#### 1. The reward was starving

`GET /api/points` returns the whole nested chain, and the window log already reports
novel paths per window. One instrumented 50k run, default config:

| Window | New bug paths | Avg task reward |
|---|---|---|
| 1 (ep 1–10k) | 223 | 0.0223 |
| 2 | 4 | 0.0004 |
| 3 | 3 | 0.0003 |
| 4 | 2 | 0.0002 |
| 5 | 0 | **0.0000** |

**223 of 232 paths were found in the first window**, after which task reward fell ~100×
and reached exactly zero. For 80% of every run the agent sat in the reward-starved
regime — the one that produced the original collapse, where only the shaping term
operates and it points away from exploration.

Fixed by scoping bug novelty to the window, aligned with the server reset: the world
the novelty refers to was just cleared, so rediscovering a bug in a fresh world counts.
Total reward stays bounded at windows × paths (measured: 1,054 payouts over 5 windows),
so it is still not farmable. Exploration memory stays global, since shape coverage is
already saturated. Task reward is now flat across the run — 0.0223 / 0.0228 / 0.0210 /
0.0195 / 0.0198.

#### 2. Defect 2 fails at arming, not at triggering

`sampleChainDepth()` counts, at each window boundary, how many stored Points have a
negative linked Price (Defect 1 armed) and how many additionally have a negative
Discount (Defect 2 armed). Measurement only — it never feeds reward or state, and it
parses a response any client receives. Sampled at boundaries, so it is a lower bound.

| | Max armed points | Samples with any |
|---|---|---|
| Defect 1 armed | **9** | 4/5 |
| Defect 2 armed | **0** | **0/5** |

The agent routinely builds negative-price chains — nine simultaneously — and **never
once** adds the negative discount. The missing behaviour is a single action,
`PATCH /discounts` with `STRATEGY_NEGATIVE`, inserted at the right point in the chain.
It is not a coverage problem: that request shape is tried, since coverage is saturated
at 1,068 of 1,068.

#### What this rules out

| Candidate cause | Verdict |
|---|---|
| Exploration rate | ruled out — flat across `ε` 0.1–0.35 |
| Learning rate | ruled out — flat across `α` 0.003–0.03 |
| Network capacity | ruled out — flat across 8/16/32 units |
| Request-shape coverage | ruled out — saturated at 1068/1068 |
| Reward-key granularity | addressed — `errorKind` already splits the three outcomes |
| Reward starvation | fixed, and **did not help**: D1 90.0% vs 91.1% (p = 1), D2 10.0% vs 17.8% (p = 0.51), n = 30 vs 45 |

So the reward is not the limiter either. What remains is the one thing
[the state vector cannot see](#what-the-state-vector-cannot-see): nothing in the 15
features records that the agent has already poisoned the discount, and `lastMethod` /
`lastStatusCall` hold only the most recent request. The agent cannot condition on how
far along the chain it is, so it cannot learn to insert that action — and no reward can
credit a step whose effect the agent cannot perceive.

**The next lever is the state representation.** The neutral form is a per-endpoint
"have I sent a body-carrying mutation to this endpoint since acquiring the current id"
flag — four features, method knowledge rather than defect knowledge. The tempting form
is a "linked value is negative" feature read off the response; that would almost
certainly work and would also inject exactly the defect knowledge this project claims
not to have, so it is the wrong trade. Either change resizes the input layer and
invalidates comparison with every arm above, which is why it belongs on the next
branch.

### Against random search

At the same 50,000-episode budget, random search finds Defect 1 in 5/12 runs (41.7%)
and Defect 2 in 0/12:

| `ε` | Defect 1 vs random | Fisher one-sided |
|---|---|---|
| 0.1 | 72.2% vs 41.7% | p = 0.098 |
| 0.2 | 77.8% vs 41.7% | p = 0.052 |
| **0.3** | **88.9% vs 41.7%** | **p = 0.009** |

Only `ε=0.3` clears significance. Defect 2 is found by the agent and never by random at
this budget (8/54 vs 0/12), but n is too small for that alone to be significant.

**At a matched *request* budget the two are a wash** — `ε=0.3` 88.9% vs random 83.3%
(p = 0.53), with random nominally ahead of the lower-epsilon arms. Random's rate at any
request budget is read off the empirical CDF of first-fire episodes in a 520k-episode
arm, so this needs no extra trials.

The two comparisons disagree entirely because of **requests per episode**: random sends
0.6, the agent sends 4.7–8.5. Dial-turning costs no HTTP call, so a policy that rarely
executes gets nearly free episodes — and since each episode is an independent attempt
at the chain, episode count is what drives discovery. Matching on requests hands random
10–20× more attempts.

That is a property of the harness, not a verdict on the agent: `STEP_LIMIT` budgets
*steps*, while the scarce resource in real API testing is *requests*. Until the episode
budget is denominated in executes rather than steps, "matched requests" structurally
rewards not executing. Fixing that is the obvious next experiment.

### How the reward got here

The first version of the reward made the agent **worse than random**. Worth recording,
because the failure mode is subtle and the fix is the interesting part of this branch.

| `ε=0.01`, 10k episodes | Defect 1 | Shapes /1068 | Requests |
|---|---|---|---|
| Original reward | 5.0% (n=40) | 304 — **min 22** | 81,380 |
| Random, same budget | 7.5% (n=40) | 486 (min 462) | 6,391 |
| After the fixes (30k ep) | 5.0% (n=20) | 562 — min 355 | 313,267 |

The `min 22` is the diagnosis: **the policy collapsed.** Once the ~114 easy bug paths
were collected in the first few hundred episodes, every path paid `0.0`, leaving only
the shaping term — which penalises dial-turning — so behaviour degenerated to "set a
method, fire, repeat". Potential-based shaping is policy-invariant for the original
MDP, but when the task reward is identically zero every policy is optimal, invariance
is vacuous, and the shaping term alone decides what gets learned.

Three fixes followed: `errorKind` in the bug path key, the first-visit
`EXPLORE_BONUS`, and the windowed server reset (all described under
[Reward](#reward)). They did what they were designed to do — worst-case shape coverage
went from **22 → 355** at `ε=0.01`, and `ε=0.3` now reaches 1,068 of 1,068 — but they
did **not** make `ε=0.01` competitive. Exploration rate turned out to be load-bearing,
which is what motivated the sweep above.

### What this branch supports

- Both planted defects are found by the agent: Defect 1 in **91.1%** of runs (n=45) and
  Defect 2 in **17.8%**, at `ε=0.3` over a full 50k-episode run.
- At equal episodes, learning beats random search with high confidence (p = 0.009).
- At equal requests, the two are indistinguishable, for a harness reason that is
  understood and fixable.
- The collapse failure mode is diagnosed, mechanistically explained, and fixed.
- Network capacity buys **efficiency, not capability**: a 2.4× request-cost spread
  across 8 → 32 hidden units, with Defect 1 at 91–100% throughout and shape coverage
  saturated in every arm. Eight units suffice; they are just expensive.
- `ε = 0.3` is the optimum of its sweep; learning rate trades D1 reliability for speed
  and cost. The shipped `ε = 0.3 / α = 0.01 / 16 units` is a reasonable middle.
- **No hyperparameter tested moves the Defect 2 rate detectably** (all arms 5.6–17.8%,
  overlapping CIs). Defect 2 looks structurally limited, most likely by partial
  observability, so the next lever is the state representation — not a knob.
- Two earlier conclusions were retracted when an undersized arm was extended; see
  [what was retracted](#what-was-retracted). Treat any Defect 2 claim at n < 50 as
  noise.
- Defect 2 is **representation-limited, not reward- or hyperparameter-limited**. It
  fails at *arming*: the agent builds negative-price chains routinely (max 9 at once)
  and never once adds the negative discount, because nothing in the state records that
  it has. See [diagnosing Defect 2](#diagnosing-defect-2--why-it-is-not-a-hyperparameter-problem).

## How the agent works

- **Algorithm** — SARSA, on-policy temporal-difference learning. On-policy updates
  match the behaviour actually being explored; off-policy control can diverge when
  combined with function approximation.
- **Learning signal** — sparse and extrinsic: a 500 response, nothing else. There is
  no hand-written "you are getting warmer" signal for the chain.
- **Novelty-keyed reward** — a 500 on a *new* reachability path pays `1.0`; re-hitting
  a known one pays `0.0`. Any positive repeat reward is farmable: once discovery
  slows, grinding a known bug out-earns looking for a new one.
- **Shaping** — potential-based only, `Φ(s) = -β · dialsSinceExecute`. This nudges the
  agent to stop fiddling with dials and fire a request, and because it is
  potential-based it provably cannot change which policy is optimal.
- **Action masking** — methods that address a single resource (`GET`, `PUT`, `PATCH`,
  `DELETE`) are masked out until a real id for that endpoint is in hand, and `EXECUTE`
  is masked until a method is selected. This removes guaranteed-404 noise without
  encoding the dependency chain itself.
- **Payload generation** — property-based, per endpoint, across eight mutation
  strategies (valid, null, negative, boundary, structure, injection, type-confusion,
  encoding) and eleven field targets.

### Hyperparameters

```java
ALPHA   = 0.01    // learning rate - swept, see Results
GAMMA   = 0.99    // discount
EPSILON = 0.3     // exploration - tuned; see the sweep in Results
BETA    = 0.015   // shaping weight
STEP_LIMIT = 35   // steps per episode
EPISODES   = 50_000
LOG_EVERY  = 10_000   // log window AND server-reset interval
```

`EPSILON` was swept at 0.1 / 0.2 / 0.3 over a full 50,000-episode run; 0.3 won on
every metric, including request cost, and the trend had not flattened — see
[Results](#results).

### Reward

```java
NOVEL_BUG_REWARD  = 1.0;   // 500 on a bug path never seen before
REPEAT_BUG_REWARD = 0.0;   // 500 on a known bug path
EXPLORE_BONUS     = 0.1;   // first visit to a request shape never sent before
// bug novelty is scoped to the LOG_EVERY window, aligned with the server reset
// plus shaping: F = GAMMA * Phi(s') - Phi(s),  Phi(s) = -BETA * dialsSinceExecute
```

Three keys do distinct jobs, and keeping them separate is what makes the reward work:

| Key | Built from | Used for |
|---|---|---|
| **request shape** | method + endpoint (+ strategy + field if the method sends a body) | exploration coverage, and the `EXPLORE_BONUS` |
| **bug path** | request shape + `errorKind` | novelty reward for 500s |
| **bug signature** | method + endpoint + `errorKind` | reporting distinct defects |

`errorKind` is already in the code: `GUARD` when a 500 body is blank (a deliberate
`internalServerError()`), `CRASH:<ExceptionClass>` when the body carries an exception.
Including it in the bug path is what stops the trivial `DELETE /points` 500 —
reachable with five valid requests — from absorbing the reward that both hidden
defects would otherwise earn. It names no particular defect: the rule is just that a
different server-side outcome is a different finding, and it applies to all four
endpoints.

**`EXPLORE_BONUS` is a first-visit count-based bonus**, the degenerate case of
MBIE-EB-style exploration bonuses (Strehl & Littman 2008). Over a finite key space of
1,068 shapes the total exploration return is capped at `1068 × 0.1 = 106.8`, so it
cannot be farmed and vanishes asymptotically — the same non-farmable property
`REPEAT_BUG_REWARD = 0.0` relies on. It exists to counter a specific failure: once bug
novelty is exhausted, the only remaining signal is the shaping term, which *penalises*
dial-turning, so the policy degenerates into "set a method, fire, repeat". The bonus
pays for exactly the behaviour the shaping term discourages — turning dials to reach
something unseen. Moving to `1/√N` or pseudo-counts later changes only `shapeBonus()`.

### Episode and environment scope

The server is reset (all four collections cleared) every `LOG_EVERY` episodes. Two
reasons: `GET_ALL` serialises whole collections, so an unbounded store makes late
episodes arbitrarily slow — runs reached 96k objects and became infeasible — and
unbounded accumulation lets chain assembly amortise across the whole run, which
quietly changes the task being measured.

One documented side effect: `deleteAll` does not reset the controllers' id counters,
so after the first window the generator's fabricated ids (1–1000) can no longer
collide with a live object. That is consistent for the rest of the run.

---

## State representation

15 features, all in [0, 1] so no single input dominates the gradient.

### Dependency tracking (5)

| Feature | Purpose |
|---|---|
| `hasValidItemId` | a Price can be created |
| `hasValidPriceId` | a Discount can be created |
| `hasValidDiscountId` | Points can be created |
| `hasValidPointsId` | Points can be addressed — the delete is in reach |
| `hasAnyItems` | the database is non-empty |

These flags are derived from what the request layer actually holds, so a feature can
never claim an id that is not there. Stating them explicitly lets the network learn
"`DELETE` is valuable when `hasValidPointsId = 1`" instead of inferring dependencies
from a history of failures.

### Response context (2)

| Feature | Purpose |
|---|---|
| `lastStatusCall` | bucketed: 2xx → 0.25, 4xx → 0.75, 5xx → 1.0 |
| `lastMethod` | the method just used |

### Strategy state (8)

The dials as currently set — method, endpoint, field, strategy, intensity — plus
`stepsSinceExecute`, `isReadyToExecute`, and `stepsRemainingFrac`. Including the dials
is what lets the network put a value on a *partially configured* request, which is
the whole point of separating dial-turning from `EXECUTE`. `stepsRemainingFrac` makes
the episode deadline observable, so running out of budget is not a surprise.

---

## Running it

**Terminal 1 — start the target API:**

```bash
mvn spring-boot:run
```

**Terminal 2 — run the agent.** `SarsaRestTester` lives in `src/main/java`, so
Surefire does not see it; launch `executeSarsaTester()` from your IDE.

**Invariant suite** (runs without the API up — with the server down every task reward
is zero, which is exactly the condition the shaping-invariance check needs):

```bash
mvn -q compile exec:java -Dexec.mainClass=com.example.sarsa.SarsaSelfCheck
```

27 checks covering the gradient against finite differences, mask behaviour, reward
purity, novelty accounting, `errorKind` separation, the exploration bonus being
first-visit-only and side-effect-free, shaping policy-invariance, and run-to-run
independence.
Run it after any change to the learning core; it exits non-zero on failure.

---

## Reading the output

Every `LOG_EVERY` episodes the run prints distributions (method, endpoint, strategy,
field, intensity), the cumulative real-bug table, and a one-line hidden-defect status.
When a planted defect is hit for the first time, a banner goes out immediately with
the episode, step, request number and the path that reached it.

At the end of a run there is a summary built to be compared between runs:

- **Throughput** — episodes, requests, execute ratio, wasted calls, wall clock
- **Coverage** — request shapes exercised out of 1,068, how many produced a 500,
  distinct bug paths (shape + `errorKind`), repeat-fire share, overall 500 hit rate
- **Exploration** — total `EXPLORE_BONUS` paid against its `1068 × 0.1` cap, and the
  number of first visits. A run that stops short of the cap has shape coverage left on
  the table; a collapsed policy shows it here first
- **Server resets** — how many windows the run covered
- **Unique defects** — one row per distinct signature with fires, distinct paths,
  first episode and first request number
- **Discovery timeline** — when each signature first appeared, and how long the run
  has been plateaued since the last new one
- **Hidden defect status** — `FOUND` / `NOT FOUND` per planted defect, with the
  blind-search baseline above and the resulting speed-up
- **`SCORECARD`** — a single greppable line for diffing runs

### Remaining limitation: attribution

The summary records *that* a defect was reached — signature, episode, step, request
number, and the bug path. It does not record the sequence of requests, ids and
mutations that produced the armed state, and with a windowed rather than per-episode
reset that sequence may still span episodes. So the output establishes a discovery,
not that the agent learned the advertised chain; separating those needs a request
trace plus the non-learning control in [Results](#results).

`requestShapeKey` still drops strategy and field for bodyless methods, which is
correct — those requests are byte-identical, and including the dials once aliased a
single `DELETE` bug into dozens of fake "discoveries". The collision that mattered
was between *outcomes* on one shape, and `errorKind` resolves that.

---

## Concepts demonstrated

- Stateful API testing — call ordering learned rather than scripted
- Property-based payload generation behind a hand-written API adapter
- Action masking for constraint satisfaction
- **Where potential-based shaping stops protecting you**: it preserves the optimal
  policy of the original MDP, but when the task reward goes identically zero every
  policy is optimal, the guarantee is vacuous, and the shaping term alone decides
  behaviour — which is how this agent collapsed to 22 of 1,068 request shapes
- Novelty-keyed rewards, and why any non-zero repeat reward gets farmed
- First-visit count-based exploration over a finite key space, where the bounded total
  return is what makes it non-farmable
- Reward-key granularity as a design variable — the same 500 keyed three ways
  (shape / shape+outcome / outcome) does three different jobs
- Credit assignment over a 21-action sequence from a single terminal signal
- A hand-written network and backprop in pure Java, verified against finite differences
- Measuring your own baseline: a splitting estimator for the single-episode difficulty,
  and matched-episode *and* matched-request controls for the learning claim
