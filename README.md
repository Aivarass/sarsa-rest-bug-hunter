# SARSA REST Bug Hunter

**A small reinforcement learning agent that finds bugs hidden behind sequences of API calls.**

I built this as a personal experiment: could a learned testing strategy find stateful
API bugs more effectively than random exploration? I wrote a SARSA agent, its neural
network and a payload generator in Java, then tested them against a small Spring Boot
API with deliberately planted defects. I also compared it with a RESTler-based tester.

**The harder defect: 82% discovery with SARSA, 40% with the best RESTler configuration,
and 9% with random exploration in the comparison experiment.**

Pure Java. No ML libraries or LLM. One hidden layer with 16 neurons.

## Results at a glance

Each percentage is the share of runs that found the defect at least once.

| | SARSA | RESTler — best of 16 configurations | Random exploration |
|---|---:|---:|---:|
| **Defect 1** — negative price in a linked chain | **100%** | 56.0% | 87.3% |
| **Defect 2** — negative price and discount | **81.8%** | 40.0% | 9.1% |
| Runs | 55 | 25 | 55 |
| HTTP requests per run | ~296,000 | 250,000 cap | ~296,000 |
| Median requests to first detection of a defect signature | **475** | 2,525 | 671 |

**SARSA found the harder defect about twice as reliably as RESTler and nine times as
reliably as random exploration.** All three testers found the same 18 error signatures
across their runs; the differences were reliability and how quickly they reached them.

The RESTler comparison used a Java port of its search machinery in the same harness,
with the same API, error detection and roughly five server resets per run. SARSA used
about 18% more requests than RESTler. The port, configuration sweep and detailed results
are on the `2-hidden-bugs-restler` branch.

A separate experiment on this branch matched **total agent steps** instead of requests:
SARSA found Defect 2 in **45/55 runs**, versus **8/55 for random** — 82% versus 15%.
The [experiment notes](docs/EXPERIMENT-NOTES.md) keep the two comparisons separate.

## The testing problem

The API has four resources, each depending on the previous one:

```text
Item → Price → Discount → Points
```

Both hidden defects appear when deleting a Point whose linked data has been made
invalid. Creating a negative price or discount is rejected, so the tester has to create
valid resources, change their values later, and then reach the failing operation.

One route to Defect 2 is:

```text
Create item → Create price → Make price negative → Create discount
            → Make discount negative → Create points → Delete points → Crash
```

Order and object relationships matter. `PATCH` changes an existing object, so linked
resources see the update. `PUT` replaces it, which can leave those resources pointing
at the old object. Sending an invalid value alone is not enough.

This is a toy API built to explore a familiar testing problem: an operation fails
because of something that happened several requests earlier.

## How the tester works

The handcrafted generator knows the endpoints, JSON fields and parent IDs. **It does
not contain a scripted route to either hidden defect.** The agent chooses the HTTP
method, endpoint, mutation strategy, field and intensity, then executes the request.

- **32 actions:** configure a request or send it.
- **15 observations:** known resource IDs, the last response, request settings and time
  left in the episode.
- **A 15 → 16 → 32 network:** predicts action values and learns through SARSA, with
  forward propagation and backpropagation written by hand.
- **Feedback:** new combinations of request shape and server-error outcome earn a
  reward. A smaller bonus encourages trying new request shapes. Repeating a known
  error earns no further bug reward within the current reward window.

Random exploration uses the same generator and available actions. That comparison
measures what learned action selection adds to the testing machinery.

## What made the difference

**Longer episodes produced the biggest improvement.** An episode is a stretch of
interaction before the tester clears its local IDs and request settings. The original
35-step limit left little room to build a chain and experiment with it.

| Steps per episode | 35 | 140 | 560 | 1,120 | 2,240 | 4,480 |
|---|---:|---:|---:|---:|---:|---:|
| Defect 2 discovery | 13% | 26% | 69% | 76% | 78% | 82% |

This sweep held total action steps constant: fewer, longer episodes, rather than a
larger action budget. The headline 81.8% result comes from a later 1,120-step run with
γ = 0.999. Changes to network size, learning rate and other learner settings did not
produce an improvement comparable to extending the episodes.

**Coverage alone did not explain success.** In the matched-step experiment, both SARSA
and random tried all 1,068 request shapes, yet SARSA found Defect 2 far more often.
Trying every kind of request is different from trying it against the right linked data.

**The RESTler port was sensitive to configuration.** Only one of the 16 configurations
in the reported sweep found either hidden defect. It used random-walk search and an
explicitly ordered dictionary. The default-style setup did not reach even the ordinary
Points deletion guard. The trace analysis also found substantial prefix replay:
rebuilding request sequences consumed most of its budget, while this runner reused
resources it already held.

The result of the project is a working example of learned API testing: a small agent
can improve defect discovery, and the way a tester explores and retains state matters
as much as how many requests it sends.

## Run it

Requires JDK 17+ and Maven. Start the target API:

```bash
mvn spring-boot:run
```

Then launch `SarsaRestTester.executeSarsaTester()` from your IDE. It is in
`src/main/java`, so `mvn test` does not launch the experiment. The run prints discovery
events, coverage and a final scorecard.

With the API stopped, run the learning and harness checks:

```bash
mvn -q compile exec:java -Dexec.mainClass=com.example.sarsa.SarsaSelfCheck
```

## Explore the project

- [Experiment notes](docs/EXPERIMENT-NOTES.md) — benchmark details, current defaults,
  reward design, fast mode and observed error signatures.
- [SARSA runner](src/main/java/com/example/sarsa/SarsaRestTester.java),
  [neural network](src/main/java/com/example/ann/TinyQNetwork.java) and
  [payload generator](src/main/java/com/example/sarsa/generator/PayloadGenerator.java).
- [Target API](src/main/java/com/example/api/controller) and
  [self-check suite](src/main/java/com/example/sarsa/SarsaSelfCheck.java).
- `2-hidden-bugs-restler` branch — RESTler port, comparison code and full experiment
  write-up in `docs/RESTLER-EXPERIMENT.md`.
