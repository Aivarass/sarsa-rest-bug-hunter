# Experiment notes

Supporting detail for the [project overview](../README.md). These notes separate the
regular SARSA runs from the RESTler comparison and retain the implementation details
behind the summary.

## Two comparisons

### SARSA versus random at matched total steps

Both arms used 55 runs with paired seeds, 1,120 steps per episode and 1,562 episodes:
approximately 1.75 million action steps per run. Random used ε = 1.0 with the same
generator and action mask. Equal action budgets produced different request counts.

| | SARSA | Random |
|---|---:|---:|
| HTTP requests per run | ~296,000 | ~44,000 |
| Defect 1 discovered | 55/55 — 100% | 54/55 — 98.2% |
| Defect 2 discovered | 45/55 — 81.8% | 8/55 — 14.5% |
| D1 median first-discovery episode, successful runs | 31 | 301 |
| D2 median first-discovery episode, successful runs | 201 | 949 |
| Median distinct signatures per run | 18 | 17 |
| Request shapes covered | 1,068 / 1,068 | 1,068 / 1,068 |

The main distinction here is Defect 2. Both policies usually find Defect 1 and reach
the same request-shape coverage.

The headline SARSA run used 16 hidden units, ε = 0.3, α = 0.01, γ = 0.999,
one-step SARSA, β = 0.015 and a first-visit shape bonus of 0.1. Its logging and reward
window was 312 episodes, with server resets at approximately the same cadence.

### SARSA versus the RESTler port

The comparison experiment is on `2-hidden-bugs-restler`. It uses a Java port of
RESTler's search machinery through the same request harness, error recorder and
server-reset policy. The port receives dependency information corresponding to the
API specification; SARSA uses its handwritten API adapter. The best of 16 RESTler
configurations is reported.

| | SARSA | RESTler port | Random |
|---|---:|---:|---:|
| Runs | 55 | 25 | 55 |
| Defect 1 | 55/55 — 100% | 14/25 — 56.0% | 48/55 — 87.3% |
| Defect 2 | 45/55 — 81.8% | 10/25 — 40.0% | 5/55 — 9.1% |
| Requests per run | 295,657 | 250,000 cap | 295,657 |
| Reported median requests to first detection of a signature | 475 | 2,525 | 671 |
| Reported p90 requests to first detection of a signature | 4,816 | 24,727 | 5,208 |

The latency figures concern first detections of error signatures, not specifically
the two hidden defects. All three testers reached the 18-signature catalogue across
their experiments; that does not mean every run found all 18.

SARSA and random have the same reported request budget in this comparison. SARSA's
budget is about 18% larger than the RESTler cap. These are full-run results at those
budgets, not a claim that the request counts were identical across all three arms.

The comparison write-up reports:

- Only the random-walk configuration with an explicitly ordered dictionary found
  either hidden defect in the final 16-configuration sweep.
- The configuration using the agent's payload vocabulary with neutral dictionary
  ordering found neither defect in 25 runs.
- The default-style BFS configuration did not reach the ordinary five-request Points
  deletion guard within the 250,000-request budget.
- Approximately 98% of the port's requests were prefix replay. The runner's ability
  to retain IDs and reuse resources avoided much of that cost.

The port's source mapping, deviations, configurations and detailed trace analysis
belong with its code on `2-hidden-bugs-restler`, in `docs/RESTLER-EXPERIMENT.md`.

## The planted defects

`Item → Price → Discount → Points` is a chain of resources. A Discount references
the stored Price object, and a Point references the stored Discount object.

Both hidden defects are in `DELETE /api/points/{id}`:

| Defect | Condition | Exception |
|---|---|---|
| D1 | Linked price is negative, discount is not negative | `IllegalStateException` |
| D2 | Linked price and discount are both negative | `ArithmeticException` |

D2 is checked first because its condition is a subset of the negative-price check.
A clean Point returns a separate, deliberate 500 guard.

POST rejects negative prices and discounts. PATCH mutates the stored object in
place; PUT replaces it. One seven-request route to D2 is:

```text
POST   /api/items                 create an item
POST   /api/prices                create a nonnegative price linked to it
PATCH  /api/prices/{id}           make the price negative
POST   /api/discounts             create a nonnegative discount linked to the price
PATCH  /api/discounts/{id}        make the discount negative
POST   /api/points                link a Point to the discount
DELETE /api/points/{id}           trigger D2
```

This is one valid ordering, not the only ordering. PATCH can also modify an object
after its descendants have been created. A negative price written by PUT can reach
a Discount created afterwards. With this generator's ID-only nested objects, PUT
on a Discount does not preserve the linked negative price in the same way as PATCH.

## Episode length and learner experiments

| Steps per episode | 35 | 140 | 560 | 1,120 | 2,240 | 4,480 |
|---|---:|---:|---:|---:|---:|---:|
| D2 discovery in the episode-length sweep | 13% | 26% | 69% | 76% | 78% | 82% |

The sweep kept total action steps approximately constant. Longer episodes preserve
local handles and request settings for longer and move the terminal learning cutoff
farther away. They changed both interaction and learning conditions; the sweep does
not isolate those effects from each other.

The later 1,120-step, γ = 0.999 benchmark reached 81.8%. It is a separate result from
the 76% entry in the episode-length sweep.

Experiments also varied exploration rate, learning rate, network width (8–64 units),
discount factor, n-step returns, novelty rewards and state features. Some diagnostic
arms deliberately supplied defect-specific information or rewards. Those diagnostic
arms are not the configuration behind the headline result. None of these learner
changes produced a gain comparable to the episode-length change.

## Reward and observation design

The 15 inputs contain resource-ID availability, response status and method, request
configuration, time since the last execution and remaining episode budget. The
network has one tanh hidden layer and 32 action-value outputs.

The API adapter supplies endpoint names, JSON structure and parent-ID placement.
The policy receives no explicit feature describing the planted defect conditions.
Methods requiring a resource ID are masked until the tester holds one.

Three keys serve different purposes:

| Key | Contents | Purpose |
|---|---|---|
| Request shape | Method, endpoint, and strategy/field for body methods | Coverage and exploration bonus |
| Bug path | Request shape plus error kind | Novel-error reward |
| Bug signature | Method, endpoint and error kind | Reported failure identity |

A new bug path pays 1.0; a repeat pays 0.0 within the reward window. Each request
shape pays an additional 0.1 on its first visit during the run. Bug novelty resets
with the logging window; shape-exploration memory lasts for the run.

Separating error kinds fixed an important reward collision: the ordinary
`DELETE /points` guard had previously consumed the novelty reward for both hidden
exceptions. In another experiment, paying 0.01 for repeats routed 98.8% of the task
reward to repeated failures.

Potential shaping uses `Φ = −β × dialsSinceExecute`, with zero terminal potential.
Individual dial-turn rewards can become positive after `γ / (1 − γ)` consecutive
dial turns. This changes the immediate learning signal; it does not break the
discounted telescoping property. The runner reports these events so their effect on
learning can be investigated. Raising γ from 0.99 to 0.999 moves that threshold
from 99 to 999 turns.

## Current code defaults and running experiments

The checked-out defaults are a different operating point from the headline benchmark:

```java
STEP_LIMIT = 560;    EPISODES = 3_125;    LOG_EVERY = 625;
ANN_NEURONS = 16;    EPSILON = 0.3;       ALPHA = 0.01;
GAMMA = 0.99;        N_STEP = 1;         BETA = 0.015;
EXPLORE_BONUS = 0.1; ARM_BONUS = 0.0;
```

The server persists between episodes and is cleared periodically. By default the
reset cadence follows `LOG_EVERY`; `sarsa.resetEvery` overrides it independently.
Collection clearing does not reset ID counters.

Start the API with `mvn spring-boot:run`, then launch
`SarsaRestTester.executeSarsaTester()` in your IDE. Episode count, step limit,
exploration rate, seed and network width are configured in the runner.

JVM properties supported by the runner include `sarsa.alpha`, `sarsa.gamma`,
`sarsa.beta`, `sarsa.nStep`, `sarsa.exploreBonus`, `sarsa.resetEvery`,
`sarsa.baseUrl` and `sarsa.chainSamples`. The diagnostic `sarsa.armBonus` is disabled
by default. Use separate API instances for parallel trials.

`-Dsarsa.inProcess=true` skips the HTTP socket and invokes the real controllers
through an embedded Spring context. JSON serialization and deserialization remain
in the path. Fixed-seed checks reported identical results to HTTP mode and roughly
2.4× faster execution.

With the API stopped, run:

```bash
mvn -q compile exec:java -Dexec.mainClass=com.example.sarsa.SarsaSelfCheck
```

The suite checks gradients against finite differences, action masks, reward
accounting, shaping, time features, n-step returns and run-state resets. The tester
prints first discoveries, distributions, coverage, chain-state samples and a final
`SCORECARD` line.

## Observed error signatures

`GUARD` is a deliberate 500 with an empty body. `CRASH:<Class>` is an exception
reported in the response. `server.error.include-exception=true` enables that detail.

| Signature | Kind |
|---|---|
| `DELETE+POINTS+CRASH:IllegalStateException` | D1 |
| `DELETE+POINTS+CRASH:ArithmeticException` | D2 |
| `DELETE+DISCOUNTS+GUARD` | Guard |
| `DELETE+ITEMS+GUARD` | Guard |
| `DELETE+POINTS+GUARD` | Guard |
| `DELETE+PRICES+GUARD` | Guard |
| `POST+DISCOUNTS+GUARD` | Guard |
| `POST+ITEMS+GUARD` | Guard |
| `POST+POINTS+GUARD` | Guard |
| `POST+PRICES+GUARD` | Guard |
| `POST+ITEMS+CRASH:NullPointerException` | Crash |
| `PATCH+ITEMS+CRASH:ClassCastException` | Crash |
| `PATCH+PRICES+CRASH:ClassCastException` | Crash |
| `PATCH+PRICES+CRASH:NullPointerException` | Crash |
| `PATCH+DISCOUNTS+CRASH:ClassCastException` | Crash |
| `PATCH+DISCOUNTS+CRASH:NullPointerException` | Crash |
| `PATCH+POINTS+CRASH:ClassCastException` | Crash |
| `PATCH+POINTS+CRASH:NullPointerException` | Crash |
