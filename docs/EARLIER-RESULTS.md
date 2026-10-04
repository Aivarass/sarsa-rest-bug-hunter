# Earlier results — difficulty estimates, hyperparameter sweeps, and the reward history

Moved out of the README when this branch narrowed to the RESTler comparison. Nothing here
is retracted unless it says so; it is the record of how the agent and its reward got to the
configuration the README now reports, and of two conclusions that an undersized sample
manufactured and a larger one withdrew.

Two things in here are **superseded** by the current README and are kept for provenance:

- the episode-denominated headline (50,000 episodes at `STEP_LIMIT = 35`) — the README now
  reports a request-denominated budget at 1,120 steps, which is the only basis on which a
  tool with no notion of an episode can be compared;
- *"Defect 2 looks structurally limited, so the next lever is the state representation —
  not a knob"* — correct about every **learner** hyperparameter swept, and wrong as a
  general claim: episode length is a knob, it was never swept, and it moved Defect 2 from
  13% to 82%. See "What actually unlocked Defect 2" in the README.

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


