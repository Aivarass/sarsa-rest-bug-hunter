# SARSA REST Bug Hunter, the RESTler comparison

**My small SARSA tester found the harder hidden defect in 82% of runs.
The best RESTler configuration reached 40%.**

I built a toy REST API with bugs that only appear after several dependent calls.
After testing my agent against random exploration, I wanted to see how it compared
with an established approach to stateful API fuzzing.

This branch contains that experiment: a Java port of
[RESTler's search machinery](https://github.com/microsoft/restler-fuzzer), a shared
testing harness, and the comparison with my handwritten SARSA agent.

For the agent itself and the broader project, start with
[the project overview](https://github.com/Aivarass/sarsa-rest-bug-hunter/tree/2-hidden-bugs-state-expansion).

## The results

Discovery means finding the defect at least once during a run.

| | SARSA | RESTler — best of 16 configurations | Random exploration |
|---|---:|---:|---:|
| **Defect 1** | **100%** | 56.0% | 87.3% |
| **Defect 2** | **81.8%** | 40.0% | 9.1% |
| Runs | 55 | 25 | 55 |
| Requests per run | ~296,000 | 250,000 cap | ~296,000 |
| Median requests to first detection of an error signature | **475** | 2,525 | 671 |

**SARSA found both hidden defects more reliably than the best RESTler configuration
tested.** On the harder defect, its discovery rate was about **2× RESTler's** and
**9× random's**. Its median request cost to first detection of an error signature
was **5.3× lower than RESTler's**.

All three testers found the same 18 error signatures across their runs. The difference
was how reliably and how quickly they found them, rather than a larger list of possible
failures.

The API, error detection and roughly five server resets per run were shared. SARSA
used about 18% more requests than RESTler. The RESTler arm is a source-based Java port,
so this compares the implemented search strategies on this API.

## What they had to find

Four resources form a dependency chain:

```text
Item → Price → Discount → Points
```

Deleting a Point fails when its linked price is negative (**Defect 1**), or when both
the price and discount are negative (**Defect 2**).

The catch: creating negative prices or discounts is rejected. A tester must create
valid resources, mutate their values and reach the final operation while preserving
the links. `PATCH` changes the existing object; `PUT` replaces it and can leave
descendants pointing at an older object.

One route to the harder defect takes seven requests:

```text
Create item → Create price → Make price negative → Create discount
            → Make discount negative → Create points → Delete points → Crash
```

## What the comparison showed

**RESTler's configuration made a large difference.** In the reported sweep, 15 of 16
configurations found neither hidden defect. The successful configuration combined
random-walk search with a dictionary ordered to try negative values early. The port's
default BFS setup did not reach even the ordinary five-request Points deletion guard
within 250,000 requests.

**Rebuilding sequences was expensive.** The trace analysis attributed roughly 98% of
RESTler's requests to prefix replay: repeating earlier calls to prepare for the next
one. My runner retained resource IDs and reused existing objects. Even its random
policy reached error signatures sooner, showing that the harness contributed to the
request-efficiency advantage.

**Learning added value on the harder defect.** Random exploration used the same
generator and available actions as SARSA, yet found Defect 2 in only 9.1% of runs.
The learned policy reached 81.8%. RESTler also improved on random for D2, at 40%.

**The agent needed room to explore.** Earlier 35-step episodes reached D2 in about
13% of runs. Longer episodes produced the largest improvement at the same total
action budget; learner-parameter changes did not deliver a comparable gain.
That investigation is covered in the
[main project overview](https://github.com/Aivarass/sarsa-rest-bug-hunter/tree/2-hidden-bugs-state-expansion#what-made-the-difference).

For this experiment, a small learned policy combined with a reusable API-testing
harness was the most effective approach. The interesting part was seeing how much
request ordering, mutation choices and reuse of server state affected discovery.

## Code and further reading

The agent is pure Java: one-step SARSA, a 15 → 16 → 32 neural network, and a
handcrafted payload generator. The adapter knows the API's fields and parent IDs;
it does not script either hidden-defect sequence.

- [RESTler port](src/main/java/com/example/restler) — search modes, dictionaries and checkers.
- [Shared trial runner](src/main/java/com/example/harness/TrialRunner.java) — budgets,
  error recording and trial output for all three testers.
- [Experiment details](docs/RESTLER-EXPERIMENT.md) — results summary, search behavior,
  port notes and [how to run a trial](docs/RESTLER-EXPERIMENT.md#run-a-trial).
- [Earlier results](docs/EARLIER-RESULTS.md) — what changed during development,
  unsuccessful settings and conclusions revised after more runs.
