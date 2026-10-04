# SARSA REST Bug Hunter

**A hand-written reinforcement learning agent finds deep, stateful API bugs — and beats
Microsoft's RESTler on RESTler's own kind of problem.**

Pure Java, no ML libraries, no OpenAPI spec, no recorded traffic, no LLM. A small on-policy
SARSA agent drives a REST API through a hand-written adapter with a `500` status code as its
only feedback. Nothing in its state or reward encodes defect knowledge: nothing says
negative values are interesting, or that `PATCH` and `PUT` differ. It has to work out on its
own that some bugs exist only at the end of a precise sequence of dependent calls.

This branch (`2-hidden-bugs-restler`) measures it against
[**RESTler**](https://github.com/microsoft/restler-fuzzer) (Atlidakis, Godefroid &
Polishchuk, ICSE 2019) — the published standard for stateful REST API fuzzing —
reimplemented from the tool's source and run through the same harness.

---

## The result

Two planted defects, both at the end of a dependency chain. Same API, same request budget,
same oracle, same server-reset policy. RESTler is **given** the dependency chain from a
spec; the agent learns it from a 500-only reward.

| | **SARSA agent** | **RESTler** (best of 16 configs) | uniform random |
|---|---|---|---|
| **Defect 1** — 6-request chain | **100%** of runs (55/55) | 56.0% | 87.3% |
| **Defect 2** — 7-request chain | **81.8%** of runs (45/55) | 40.0% | 9.1% |
| Defect signatures found | 18 of 18 | 18 of 18 | 18 of 18 |
| Requests to a first sighting (median) | **475** | 2,525 | 671 |
| Requests per run | 295,657 | 250,000 (cap) | 295,657 |

The agent beats RESTler on both defects: **p = 4.3 × 10⁻⁷** and **p = 0.0003** (Fisher,
one-sided). It beats uniform random on both: p = 0.006 and p = 3 × 10⁻¹¹.

### The conclusion

**RESTler's grammar buys structure. It cannot buy value-mutation ordering.**

Both defects need a value written to an object that is *already linked* into the chain —
`PATCH /prices/{id}` negative, after a Discount has linked that Price, before the `DELETE`.
That is not a producer-consumer relation. It is a re-visit to a resource you already made,
with a specific value class, at a specific point in an ordering. One mechanism explains the
whole table:

- **Defect 2** — the hard part *is* the four-deep structure, which is exactly what the
  grammar is for. RESTler builds the chain reliably and its fuzzing occasionally lands a
  negative in the right slot. Structure solved, mutation left to chance: 40%, and a genuine
  **4.4× over random** (p = 0.002).
- **Defect 1** — the hard part isn't structure at all. Random flails into "patch a price
  negative, delete a point" fairly often while RESTler spends its budget on well-formed
  sequences, so it lands *below* random (p = 0.003). Systematicity is a liability when the
  target needs undirected mutation.

RESTler poisons the store freely — up to 567 negative prices per run — and **never links a
Point to one**. Its search picks what to extend by position in the grammar, never by what
the service's state has become, so the poisoned prefix sits in its pool, equally valid,
never built upon. The agent's state carries which resources it holds, so it can learn
*where in the chain* the mutation goes. That is the entire difference, and it is the same
conclusion this project reached from the other direction: **representation is the binding
constraint on reachable bug depth.**

Honest limits, stated once: one API, one defect class, RESTler reimplemented rather than
executed (four of eight documented deviations favour it, none favours the agent), and its
arms are n=25 against the agent's n=55. The full comparison, the fairness audit and every
measurement behind these numbers are below and in
[docs/RESTLER-EXPERIMENT.md](docs/RESTLER-EXPERIMENT.md).

---

## What actually unlocked Defect 2

Worth putting near the top because it is the most useful thing this project learned, and it
is not about the learner.

Eleven changes to the **learner** were measured and came back flat: exploration rate,
learning rate, network size at 8/16/32/64 units, n-step returns, count-based state novelty,
reward-key granularity, reward starvation, extra state features, and a reward aimed directly
at the missing action. One change to the **environment interface** moved Defect 2 from 13%
to 82%:

| steps per episode | 35 | 140 | 560 | 1,120 | 2,240 | 4,480 |
|---|---|---|---|---|---|---|
| **Defect 2 found** | 13% | 26% | 69% | 76% | 78% | 82% |
| vs RESTler (one-sided) | — | tie, p=0.17 to RESTler | p = 0.014 | p = 0.002 | p = 0.001 | p = 0.0003 |

At matched *total* steps throughout — episodes are traded for episode length, so this is not
a bigger budget. `STEP_LIMIT = 35` left only 14 slack actions over Defect 2's 21-action
minimum; exploration noise had nowhere to land the second mutation while the chain still
stood.

Note the 140-step column: **at short episodes the comparison inverts.** The agent is
nominally behind RESTler on Defect 2 (26.4% vs 40.0%, not significant). RESTler's grammar
beats an under-budgeted learner on the deep chain, and only loses once the learner has room
to express the sequence. Every configuration from 560 steps up wins on both defects, with
the margin growing monotonically — so the headline is robust, not a cherry-picked operating
point.

---
## What was compared

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

## Full comparison against RESTler

### Against RESTler

Uniform random over the agent's own action mask is a weak baseline, and the obvious
objection to every number above. So this branch adds the real one:
[**RESTler**](https://github.com/microsoft/restler-fuzzer) (Atlidakis, Godefroid &
Polishchuk, ICSE 2019), the published standard for stateful REST API fuzzing,
reimplemented in Java from the tool's source and driven through the same harness — one
HTTP client, one oracle, one recorder — so "found Defect 1" means the same thing in every
column.

This API is RESTler's home turf. Its contribution is exactly this problem — inferring
producer-consumer dependencies and searching over call orderings — and it is **handed the
chain for free** from a spec, while the agent learns it from a 500-only reward. Sixteen
configurations were swept (all five published search strategies, checkers on/off, four
dictionaries, `max_combinations`, prefix caching, GET-producers, collection-DELETE) and
the best is reported. Of eight documented deviations in the port, **four favour RESTler
and none favours the agent**. Full design, fairness audit, source-cited fidelity table and
deviations register: [docs/RESTLER-EXPERIMENT.md](docs/RESTLER-EXPERIMENT.md).

| Tester | Defect 1 | Defect 2 | shapes / 1068 |
|---|---|---|---|
| **SARSA** — 1,120 steps, γ=0.999, n=55 | **100%** (55/55) | **81.8%** (45/55) | 1068 |
| uniform random — same budget and seeds, n=55 | 87.3% (48/55) | 9.1% (5/55) | 1067 |
| RESTler, best of 16 configurations, n=25 | 56.0% [34.9–75.6] | 40.0% [21.1–61.3] | 693 |
| RESTler, agent's vocabulary + neutral dictionary order, n=25 | **0 / 25** | **0 / 25** | 687 |
| RESTler as shipped (`bfs` + default dictionary) | 0 — never reaches the trivial 5-request guard | 0 | 20 |

SARSA over RESTler: Defect 1 **p = 4.3 × 10⁻⁷**, Defect 2 **p = 0.0003**. The agent spends
295,657 requests against RESTler's 250,000 cap, which is immaterial — RESTler's latest
first-fire across every arm is request 227,670, with 85% landing before 200k. All arms at
~5 server resets per run.

**The grammar buys structure and cannot buy value-mutation ordering.** Both defects need a
value written to an *already-linked* object: `PATCH /prices/{id}` negative, after a Discount
has linked that Price, before the `DELETE`. That is not a producer-consumer relation — it is
a re-visit to a resource you already made, with a specific value class, at a specific point
in an ordering. The split in the table is one mechanism seen twice:

- **Defect 2** — the hard part *is* the four-deep structure, which is what the grammar is
  for. It builds the chain reliably and its fuzzing occasionally lands a negative in the
  right slot. Structure solved, mutation left to chance: 40%, and **4.4× uniform random**
  (p = 0.002).
- **Defect 1** — the hard part isn't structure at all. Random flails into "patch a price
  negative, delete a point" fairly often while RESTler spends budget on well-formed
  sequences, so it lands *below* random (p = 0.003). Systematicity is a liability when the
  target needs undirected mutation.

The structural reason it cannot close the gap is in `driver.py`. Every mode keeps all valid
renderings, so a sequence ending in `PATCH /prices` with a negative value **is** in the
pool — but `extend` takes the first sequence satisfying a dependency in grammar order, and
that is a clean chain containing no mutation at all. The poisoned prefix sits there,
equally valid, never built upon.

> **RESTler's sequence search is state-blind.** It picks what to extend by position in the
> grammar, never by what the service's state has become. No dictionary, checker or value
> ordering can express "extend the prefix that poisoned the resource".

Only `random-walk` escapes this, by replacing selection with randomisation — which is why
it is the one mode of sixteen configurations that finds anything at all. Its default
dictionary also holds one value per primitive and **no negative number**
(`Grammar.fs:537`), so out of the box it cannot arm either defect: that is the dictionary,
not the search. Negatives reach the service only through its `InvalidValueChecker`, whose
requests are real — so it *does* poison the store, up to 567 negative prices and 215
negative discounts per run. It just never links a Point to one.

**Breadth costs it nothing; latency costs it 5×.** All three testers eventually find all
18 defect signatures — the grammar costs RESTler nothing in *what* it can reach, because
its checkers do the same undirected type-confusion fuzzing that produces the
`ClassCastException` / `NullPointerException` families. What it costs is speed:

| median requests to a signature's first sighting | p90 |
|---|---|
| agent **475** · random **671** · RESTler **2,525** | 4,816 · 5,208 · **24,727** |

The cause is architectural. **~98% of RESTler's budget is prefix replay**: it re-sends a
sequence's whole prefix for every rendering of the last request (`sequences.py:449`), and
at a median of **52.6 requests per rendering** over sequences up to 68 long, only the final
request of each is new. To be in a state, RESTler must re-execute the path to it. The agent
holds the ids it already obtained and re-acquires them with `GET_ALL`, so it never pays
that toll.

That replay is not waste to be configured away. RESTler's own `create_prefix_once` setting
amortises a prefix across *multiple renderings of the same sequence* — but `random-walk`,
the only mode that finds these defects, stops at the first valid rendering
(`driver.py:258`) and so renders each valid sequence just **1.16 times**. There is nothing
to amortise. The cost is irreducible for the configuration that works.

Neither arm's reward design explains any of this. The random arm runs at ε=1.0, ignores the
Q-network entirely, and so cannot be influenced by any reward term — yet it is already 3.8×
faster than RESTler, so the gap is not about learning at all. And an ablation of the
first-visit `EXPLORE_BONUS` (n=8) moves first-sighting latency the *wrong* way, 414 → 265
requests with the term removed, so the agent's remaining 1.4× margin over random is not
attributable to the novelty term either — it is the learned policy.

Request-shape coverage points the same way but is the weaker axis, and is reported as
supporting rather than headline: RESTler reaches **693 of 1,068 shapes, 65% of *the agent's
payload taxonomy*** — 8 mutation strategies × 11 field targets is this generator's design,
not a neutral space, and RESTler has no native notion of "target `DISCOUNT_ID` with
`NEGATIVE`"; the matched dictionary projects its payloads onto those labels. On a neutral
denominator — the 24 method × endpoint request types — both testers address all 24. The
coverage gap exists only inside our own taxonomy, so the denominator flatters us and the
number should be read that way.

**It is an episode-length result, not a tuning result.** At short episodes the comparison
inverts: at 140 steps the agent is nominally *behind* RESTler on Defect 2, 26.4% vs 40.0%
(not significant, p = 0.17 in RESTler's favour). RESTler's grammar beats an under-budgeted
learner on the deep chain and only loses once the learner has room to express the sequence.
Every configuration from 560 steps up wins on both defects, with the margin growing
monotonically:

| steps/episode | 140 | 560 | 1120 | 2240 | 4480 |
|---|---|---|---|---|---|
| Defect 2 | 26.4% | 69.1% | 76.4% | 78.2% | 81.8% |
| vs RESTler (one-sided) | tie (p=0.17 to RESTler) | p = 0.014 | p = 0.002 | p = 0.001 | p = 0.0003 |

That is the mirror image of this project's own finding. Where the state vector supplies a
sufficient statistic — the `hasValid*Id` flags for the creation ladder — the agent learns
the chain and reaches both defects. A tester with a *perfect* structural model of the API
and no state representation hits the same wall one rung lower. **Representation is the
binding constraint from both directions.**

**Scope.** RESTler is reimplemented, not executed: the real tool adds a spec compiler,
auth, replay, and five response-shape checkers (`UseAfterFree`, `ResourceHierarchy`,
`LeakageRule`, `NameSpaceRule`, `InvalidDynamicObject`) that this API cannot exercise and
that no 5xx-reward agent has. RESTler arms are n=25 against n=55, so the comparison's
precision is limited by the smaller arm. Its best configuration needs a non-default search
mode *and* a dictionary ordered interesting-value-first — the one place defect knowledge
entered on its side; with the agent's own neutral ordering it finds nothing in 25 runs.
Knowledge parity is close but not identical: RESTler gets an OpenAPI spec, the agent gets a
hand-written adapter knowing endpoints, field names and which parent id belongs in which
child body. Neither is told anything about the defects. Reset cadence is a strong
confounder, held at ~5 per run for every arm: RESTler and the agent are insensitive to it,
random is not (its Defect 2 rate reaches 40% at 50 resets). The defects were planted by us,
and were not designed to defeat RESTler — its 40% on Defect 2 is genuine capability on the
chain. One API, one defect class.

### What this branch supports

- **Episode length, not the learner, was the limit on Defect 2.** Eleven changes to the
  learner — exploration rate, learning rate, network size 8/16/32/64, n-step returns,
  count-based state novelty, reward-key granularity, reward starvation, extra state
  features, and a reward aimed directly at the missing action — all measured flat. One
  change to the *environment interface* took Defect 2 from 13% to 69%: at matched total
  steps, 35 → 140 → 560 → 1120 → 4480 steps per episode gives 13% → 26% → 69% → 76% → 82%.
  `STEP_LIMIT = 35` left only 14 slack actions over Defect 2's 21-action minimum. 560 is
  the shipped operating point — the widest simultaneous margin over random on both defects.
- **Against RESTler, on RESTler's own problem:** Defect 1 — SARSA 100%, random 87.3%,
  RESTler 56.0%. Defect 2 — SARSA 81.8%, RESTler 40.0%, random 9.1%. RESTler's grammar
  earns its keep on the deep chain (4.4× random, p = 0.002) and costs it the shallow one
  (below random, p = 0.003); the agent beats it on both (p = 4e-07 / 0.0003) while being
  handed none of the dependency knowledge RESTler gets free from a spec. 15 of 16 of its
  configurations find nothing, and the shipped default never reaches the trivial
  five-request guard. See [Against RESTler](#against-restler).
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
- **No *learner* hyperparameter moves the Defect 2 rate detectably** (all arms 5.6–17.8%,
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

## Earlier results

Difficulty estimates by multilevel splitting, the ε / α / capacity sweeps, two retracted
conclusions, and the history of how the reward reached its current form:
[docs/EARLIER-RESULTS.md](docs/EARLIER-RESULTS.md). Raw per-trial data for every arm in this
branch is in [docs/results/](docs/results/) with the runners in [scripts/](scripts/);
status and how to resume: [docs/results/STATUS.md](docs/results/STATUS.md).
