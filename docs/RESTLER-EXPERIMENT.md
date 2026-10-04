# SARSA vs. RESTler — experiment details

The [README](../README.md) is the short version. This document keeps the implementation
notes and the reasoning behind the comparison, including predictions that changed as
the results came in.

## Results summary

| | SARSA | Best RESTler-port configuration | Random |
|---|---:|---:|---:|
| Defect 1 | 55/55 — 100% | 14/25 — 56.0% | 48/55 — 87.3% |
| Defect 2 | 45/55 — 81.8% | 10/25 — 40.0% | 5/55 — 9.1% |
| Requests per run | 295,657 | 250,000 cap | 295,657 |
| Median requests to first detection of a signature | 475 | 2,525 | 671 |
| p90 requests to first detection of a signature | 4,816 | 24,727 | 5,208 |

The latency figures cover error signatures generally, not just the hidden defects.
All three testers reached the 18-signature catalogue across the experiments. SARSA
was more reliable on both hidden defects and needed fewer requests to first detect
signatures. Its total request budget was about 18% larger than RESTler's cap.

The reported sweep covered 16 RESTler configurations. Only the random-walk setup with
an explicitly ordered dictionary found either hidden defect. The configuration using
the agent's vocabulary with neutral dictionary ordering found neither in 25 runs.
Trace analysis identified prefix selection and extensive prefix replay as important
differences from the SARSA runner's use of existing server state.

### Reading this notebook

The sections below are the development record, rather than one final specification.
Early plans mention nine configurations, different trial counts and equal 250,000-request
budgets; later experiments expanded the comparison. The table above is the final result
summarised in the README. Where an addendum corrects an earlier prediction or protocol,
read it as the later finding.

- [Search and dictionary behavior](#part-1--restler-from-source).
- [Port implementation and deviations](#part-4--fidelity-table-and-deviations-register).
- [Problems found while building the harness](#part-5--audit-addenda-found-during-implementation).
- [The successful random-walk configuration](#512-restler-finds-defect-1--and-which-mode-does-it-is-the-result).
- [Run a trial](#run-a-trial).

## Original experiment plan

**Branch:** `2-hidden-bugs-restler`
**Question:** on one fixed API with two planted stateful defects, does a learned
exploration policy (SARSA) find them more reliably than grammar-based stateful fuzzing
(RESTler), at an equal request budget?

The system under test does not change. The *tester* changes. Everything else is held
constant by construction, by sharing one code path for transport, oracle, budget and
bookkeeping across all arms.

Primary sources for RESTler's behaviour are the tool itself
(`microsoft/restler-fuzzer`, `main`, cloned for this work) and are cited as
`file:line` throughout. Where this document states what RESTler "does", it is reporting
code, not recollection.

---

## Part 0 — What is being compared

### The two testers

| | **Arm A — SARSA** (incumbent) | **Arm C — RESTler** (new) |
|---|---|---|
| Input it is given | nothing but the adapter | an OpenAPI spec |
| Knows the dependency chain? | **learns it** from reward | **given** it, via grammar |
| Unit of decision | a dial turn, or `EXECUTE` | a sequence to render |
| Search | ε-greedy over a learned Q-function | BFS over sequence length |
| Value selection | 8 strategies × 11 fields, policy-selected | dictionary / checker value generators |
| Memory across the run | network weights | the valid-sequence pool |
| Feedback used | 500 = reward | 2xx = sequence is valid; 5xx = bug |

The honest framing of the result — fixed now, before any data — is **not** "RL beats
RESTler" or the reverse. It is: *where does the capability to reach a deep stateful
defect live — in a learned policy, or in a spec-derived grammar plus a value
dictionary?* Both arms carry domain knowledge; they carry it in different places, and
the experiment is designed to make that the only difference.

### Where each arm's domain knowledge sits

This must be stated plainly, because "fairness" here means *matched knowledge*, not
*zero knowledge*.

| Knowledge | Arm A (SARSA) | Arm C (RESTler) |
|---|---|---|
| Endpoint paths, methods | `Endpoint` enum + `HttpType` enum | spec |
| JSON field names | `PayloadGenerator` literals | spec |
| Which parent id goes in which child body | `getItemIdJson`, `getPriceIdJson`, `getDiscountIdJson` | inferred producer-consumer edges |
| *Order* in which to call them | **not given — learned** | **given** (grammar gates extension) |
| That negative values exist | in the vocabulary (`STRATEGY_NEGATIVE`) | in the dictionary / value generator |
| That `PATCH` ≠ `PUT` for aliasing | **not given** | **not given** |
| That the bug needs a negative *linked* price | **not given** | **not given** |

Rows 1–3 and 5 are matched by construction (Part 3). Row 4 is the asymmetry that
*defines* the comparison and cannot be removed without destroying one arm or the other:
a grammar-based fuzzer that is not told the dependency order is not RESTler, and an RL
agent that is told it is not learning anything. Row 4 is therefore reported as the
finding, not hidden as a confound.

---

## Part 1 — RESTler, from source

Everything in this section is what the implementation must reproduce.

### 1.1 Search loop

`restler/engine/core/driver.py:62 extend()` — for every request type `r` in the
grammar and every sequence `seq` already in the pool, append `seq + r` **iff**
`r.consumes ⊆ ⋃ seq.produces` (`driver.py:42 validate_dependencies`). The pool is then
replaced by the newly extended generation (`driver.py:135`), so generation *n* holds
only sequences of length *n*.

Search-strategy variants, all in the same function:

| `fuzzing_mode` | Behaviour | Source |
|---|---|---|
| `bfs` (**the default**) | extend *every* sequence by *every* satisfiable request | `restler_settings.py:305` sets `FUZZING_MODE_DEFAULT = 'bfs'` |
| `bfs-fast` | `break` after the first sequence per request type — one sequence per request type per generation | `driver.py:116-118` |
| `bfs-minimal` | as `bfs-fast`, plus stops at the first valid rendering | `driver.py:116`, `driver.py:258` |
| `bfs-cheap` | keeps all sequences, stops at the first valid rendering | `driver.py:258` |
| `random-walk` | truncates the new generation to **one** randomly chosen sequence | `driver.py:125-130` |

### 1.2 Rendering

`restler/engine/core/sequences.py:420 render()` — **the prefix is re-executed from
scratch for every rendering of the last request**, each prefix request replayed with its
last known valid value combination (`use_last_cached_rendering=True`,
`sequences.py:449 render_prefix`). Only the last request iterates over value
combinations. This is why RESTler's request cost grows quadratically with generation,
and it is not an inefficiency to be optimised away — it is how the tool produces a live
dependency chain for the request under test.

- A rendering is **valid** iff the final status is in
  `VALID_CODES = {200, 201, 202, 204, 304}` (`transport_layer/response.py:8`).
- Any `5xx` is a **bug**, anywhere in the sequence, including inside the prefix replay
  (`response.py:163-170 has_bug_code`; prefix check at `sequences.py` →
  `BugBuckets.update_bug_buckets`). A prefix 5xx also marks the sequence failed.
- `bfs` / `bfs-fast` are **exhaustive over renderings**: every valid rendering becomes
  its own sequence in the next generation (`driver.py:270-285`). The cheap/minimal/
  random-walk modes keep only the first (`driver.py:258-263`).
- Combination cap: `MAX_COMBINATIONS_DEFAULT = 20` (`restler_settings.py:318`).
- `MAX_SEQUENCE_LENGTH_DEFAULT = 100` (`restler_settings.py:322`) — not a constraint
  here, the deepest chain needed is 7.

### 1.3 The dictionary — the decisive detail

`src/compiler/Restler.Compiler/Grammar.fs:537` and
`Dictionary.fs:226 DefaultMutationsDictionary` give the default value per primitive
type, **one value each**:

```
string   "fuzzstring"      int     "1"
number   "1.23"            bool    "true"
object   { "fuzz": false }  uuid/date/datetime  fixed literals
```

**There is no negative number in RESTler's default dictionary.** On this API that is
decisive: `PATCH /api/prices/{id}` with `price: 1.23` can never arm Defect 1. Out of the
box, RESTler's main driver is *structurally incapable* of reaching either planted
defect — not because BFS is weak, but because the value vocabulary lacks the one value
class that matters. Any comparison that stops there is measuring the dictionary, not the
algorithm, and would be a straw man.

### 1.4 Checkers — where RESTler's invalid values actually come from

`restler/checkers/`. All are constructed with `CheckerBase.__init__(..., enabled=False)`
(`checker_base.py:28`) and switched on with `--enable_checkers` (`restler.py:121-158`);
`docs/user-guide/Checkers.md:11` describes them as on by default when fuzzing. Two
matter here:

**`InvalidValueChecker`** (`invalid_value_checker.py:184 apply`) — the important one:

1. Triggers on a rendered sequence, valid or invalid (`_fuzz_valid = _fuzz_invalid =
   True`, `invalid_value_checker.py:138-139`).
2. Fires **once per (generation, request-schema-hash)** —
   `generation_executed_requests` dedup at `:215-223`.
3. Re-executes the sequence prefix (`_execute_start_of_sequence`, `:244`).
4. For **each fuzzable block separately**, holding all other values at their original
   rendering, substitutes up to `param_budget = max_combinations / num_fuzzable_blocks`
   values (`:266-269`; `max_combinations` default **100**,
   `docs/user-guide/Checkers.md:177`), each substitution being a real HTTP request
   (`:320`).
5. `5xx` → bug bucket (`:345-348`).

Its default value generator, `invalid_value_checker_value_gen.py:55
placeholder_value_generator`, yields boundary values and then, forever:

```python
yield str(random_gen.randint(-10, 10))        # negative integers
yield str(random_gen.uniform(-10000, 10000))  # negative reals
```

**This is RESTler's route to the planted defects, and it is a legitimate one.** The
checker's requests hit the real server, so a `PATCH /api/prices/{id}` carrying `-7.3`
persists the poison into the store *even though the mutated rendering never enters the
sequence pool*. The main BFS then builds `POST /api/points` → `DELETE /api/points/{id}`
on an already-poisoned chain and the crash fires. Defect 2 needs the same thing to
happen on `PATCH /api/discounts/{id}` as well.

**`PayloadBodyChecker`** (`payload_body_checker.py`, FSE'2020) — structural body
fuzzing: drops fields, changes types, edits structure. The analogue of this project's
`STRATEGY_STRUCTURE`. Included for completeness; it cannot arm either defect, since
both need a *negative number*, not a malformed body.

Garbage collection is off unless configured (`restler_settings.py:418,431`) and is
ineffective on this API anyway (every single-resource `DELETE` is a planted 500 guard
that does not delete). Excluded, and the exclusion is noted.

### 1.5 Dependency inference reaches our nested ids

`src/compiler/Restler.Compiler/Dependencies.fs:219 getNestedObjectProducer` resolves
exactly our shape — a body consumer `{"item": {"id": <dyn>}}` is matched to a producer
endpoint whose container name matches the parent object name (`/api/items`), preferring
the shortest endpoint, restricted to `POST`/`PUT` producers (`:236-239`).

So **the real compiler would infer this API's chain unaided**. Handing the chain to the
reimplementation is reproducing RESTler's documented behaviour, not granting it a
favour. (Had inference failed, supplying `annotations.json` would also have been
standard documented usage — `docs/user-guide/Annotations.md`.)

---

## Part 2 — Adversarial fairness audit

Written against the Part 1 + Part 3 design with one goal: *find every way this
comparison could be rigged, in either direction.* Each item gets a verdict and, where
needed, a fix that is folded into Part 3.

### 2.A Threats that would **disadvantage** RESTler

| # | Threat | Verdict | Fix |
|---|---|---|---|
| A1 | **Default dictionary has no negative number** (§1.3), so the main driver cannot possibly arm either defect. Reporting only this configuration would be a straw man. | **Real and fatal if unaddressed.** | Run checker-enabled configurations as first-class arms (C4–C6), and report **the best RESTler configuration** as "RESTler's rate", with the full per-configuration table beneath it. Also run a `negatives-in-main-dictionary` variant (C7) so the main driver gets the vocabulary too. |
| A2 | **Only running `bfs`**, whose breadth explodes before it reaches generation 6–7, where the defects live. | **Real.** | Run all three published strategies — `bfs`, `bfs-fast`, `random-walk` — plus `bfs-cheap`. Take the max. |
| A3 | **Prefix re-execution** (§1.2) makes RESTler pay ~n requests per length-n rendering, so a request-denominated budget buys it fewer *attempts* than it buys SARSA. | **Inherent to the tool, not an artefact of my harness.** Keep, but report requests-per-sequence-attempt for both arms so the reader can see the exchange rate, exactly as the README already does for SARSA's steps-vs-requests gap. | Report, don't "fix". Additionally report a **wall-clock**-matched comparison, where RESTler's cheaper per-decision cost works in its favour. |
| A4 | **Checkers off** would remove the only source of invalid values. | Same as A1. | C4–C6 enable `InvalidValue` + `PayloadBody`. |
| A5 | `max_combinations = 20` (main) and `100` (checker) are defaults that may be too small to reach a negative value often enough. | Plausible. | Sweep the checker's `max_combinations` ∈ {20, 100, 500} in screening; use the best in the headline. |
| A6 | **Excluding `DELETE /api/{collection}`** (delete-all) from the grammar removes real API surface. | Excluding it *helps* RESTler (it would otherwise wipe its own chains), and the SARSA arm cannot address it at all. | Exclude from the headline grammar to match the SARSA arm's addressable surface exactly; run C8 **with** it included to verify the direction of the effect empirically rather than by assertion. |
| A7 | **Excluding `HEAD`/`OPTIONS`** (`ItemController` only) removes surface. | Neither can return 5xx; the SARSA arm has no such action. | Exclude; note it. Zero effect on either defect by inspection of `ItemController:97-112`. |
| A8 | SARSA harvests ids from `GET_ALL` responses (`extractFirstId`), while RESTler's inference restricts producers to `POST`/`PUT` (§1.5). After a server reset this could let SARSA re-acquire a chain more cheaply. | Real, small. | RESTler supports GET producers (`allowGetProducers`). Run C9 with GET producers enabled; report. |
| A9 | A **500 inside the prefix** aborts the sequence (§1.2), and on this API `DELETE /api/points/{id}` *always* 500s. RESTler could keep aborting on its most valuable prefix. | Faithful tool behaviour, and symmetric in spirit (SARSA's episode also ends). | Keep; report aborted-prefix counts so the effect is visible. |
| A10 | Windowed server reset destroys RESTler's dynamic objects mid-BFS, invalidating its pool. | Applied identically to both arms, so fair — but it could interact with BFS specifically. | Run the **no-reset** variant for *both* arms (C-NR, A-NR) as a robustness check. |
| A11 | Wrong `errorKind` attribution: RESTler's own bucketing differs from this project's `bugSignature`. | Using one oracle for both arms is required for "found D1" to mean one thing. | Shared oracle; RESTler's native bucketing is *additionally* reported for cross-checking. |
| A12 | Me implementing BFS badly and calling it RESTler. | The central fidelity risk. | §3.6: a line-by-line fidelity table to Part 1, a deviations register, and a differential smoke test — on a 3-endpoint toy the reimplementation must reproduce the documented generation/sequence counts. Every deviation is listed in the README. |

### 2.B Threats that would **advantage** RESTler

| # | Threat | Verdict | Fix |
|---|---|---|---|
| B1 | **Dependency order given** by the grammar, which is precisely what SARSA must learn. | Inherent (Part 0, row 4). Not removable. | Declared as the finding, not buried. Reported as: RESTler is handed the ladder and still has to find the poison; SARSA must find both. |
| B2 | Hand-tuning the **spec** to point at the bug — e.g. adding an `example` of `-1`, or a `minimum: 0` hint. | Would invalidate everything. | Spec is derived **mechanically** from the controllers and models (field names, types, nesting only). No examples, no constraints, no annotations. Spec is committed and diffable; the derivation rule is documented per field. |
| B3 | Loading the dictionary with values chosen *because* they trigger the defect (e.g. exactly `-1`). | Would invalidate the headline. | C4–C6 use RESTler's **own** generator semantics (`randint(-10,10)`, `uniform(-10000,10000)`, the boundary list) — reproduced from `invalid_value_checker_value_gen.py`, not invented. C6 instead reuses `PayloadGenerator` verbatim, i.e. the *SARSA arm's own* vocabulary, which cannot favour RESTler. No hand-picked constants anywhere. |
| B4 | Giving RESTler the **accumulated-state advantage**: a long run on a never-reset server lets poison pile up. | Symmetric — SARSA benefits from the same mechanism (the README documents it). | Identical reset policy, both arms, request-denominated. |
| B5 | Counting RESTler's **checker requests outside the budget**. | Would be a large hidden subsidy — the checker is ~100 requests per trigger. | **Every** request leaving the harness counts against the budget, for every arm. One counter, in shared code, incremented in `HttpTransport.send`. |
| B6 | Excluding RESTler's **failed/invalid renderings** from the request count. | Same class as B5. | Same single counter. |
| B7 | Letting RESTler **keep ids across the reset** while SARSA drops them. | Would be a real subsidy. | Reset clears the pool/dynamic objects for RESTler and the id handles for SARSA — both arms re-acquire from scratch. |
| B8 | Reporting **max over 9 configurations** for RESTler but a single configuration for SARSA. | **This is a real multiple-comparisons subsidy to RESTler.** | Symmetry: SARSA's configuration is also chosen as the best of its published sweep (ε ∈ {0.1,0.2,0.3,0.35}, α ∈ {0.003,0.01,0.03}, 8/16/32 units — 6+ arms, already measured). Both arms are therefore "best of a sweep". The screening stage uses a *separate seed set* from the headline stage, so the winner is not selected on the same data it is scored on. |
| B9 | Giving RESTler a **larger effective vocabulary** than SARSA (the checker's unbounded generator vs. SARSA's fixed 1,068 shapes). | Real in C4/C5/C7. | That is why **C6 exists**: identical vocabulary, drawn from `PayloadGenerator`. C6 is the *like-for-like* headline; C4/C5/C7 are reported as "RESTler with its own vocabulary". Both numbers are published. |

### 2.C Threats to the comparison itself (neither arm)

| # | Threat | Verdict | Fix |
|---|---|---|---|
| C1 | **Budget unit.** The README's own finding: SARSA's episode budget rewards not executing, so matched-episode and matched-request comparisons disagree. RESTler has no notion of an episode. | Fatal to interpretability if unaddressed. | **Requests are the budget.** Every arm stops at exactly `R` requests. SARSA's episode count becomes an *outcome*. This requires refactoring the SARSA loop to honour a mid-episode request cap. Wall clock reported as a secondary axis. |
| C2 | Choosing `R` to suit one arm. | Real. | Report the **full discovery CDF over request index**, so the rate at *any* budget ≤ R is readable without new runs. `R = 250,000` is set from the incumbent's measured mean cost (239,846 at ε=0.3/16 units), not chosen after seeing results. |
| C3 | Different HTTP stacks → different timing, different interleavings. | Real. | One `HttpTransport` class, one pooled `HttpClient`, identical timeouts, used by every arm. |
| C4 | Different oracles. | Real. | One `BugOracle`. D1 ≡ `DELETE+POINTS+CRASH:IllegalStateException`, D2 ≡ `DELETE+POINTS+CRASH:ArithmeticException`, for all arms. |
| C5 | Seed luck. | Real. | Paired seeds across arms; `n = 45` on headline arms (the README's own floor: "treat any Defect 2 claim at n < 50 as noise"); Fisher exact + Clopper-Pearson CIs. |
| C6 | **Refactoring silently changes the SARSA arm**, so arm A is no longer the published agent. | Real and easy to miss. | Behaviour-preservation gate: fixed-seed run before vs. after the refactor must produce a byte-identical run summary, and all 27 `SarsaSelfCheck` invariants must pass. The refactor does not land until that diff is empty. |
| C7 | Contaminating the user's **running experiments** (ports 8080–8085, 12-core box at load ~10). | Real, operational. | Ports **8086+** only; ≤3 concurrent trial streams while those runs are in flight; never signal a JVM not spawned by this work. |
| C8 | Measurement requests (`sampleChainDepth`'s `GET /api/points`, the reset's 4 `DELETE`s) inflating or distorting the budget. | Minor. | Reset `DELETE`s **count** (both arms, same cadence, so symmetric). Diagnostic `GET`s are **excluded** from the budget and counted separately — same rule, same code, both arms. |
| C9 | **Three dead `PATCH` branches** in the API (`PriceController:86`, `DiscountController:89`, `PointsController:91` read `itemId`/`discountId`, which `PayloadGenerator` never emits) are reachable from a mechanically-derived spec but not from SARSA's vocabulary. | Real surface asymmetry. | Derive the spec from **the model classes** (`Price{item,price}`, `Discount{price,discount}`, `Point{discount,points}`), which is both the mechanical rule *and* exactly SARSA's surface. The three scalar-id branches are out of scope for both arms, documented as a finding. Neither defect needs them (`PointsController:112-121` depends only on linked values). |
| C10 | The API is changed to make a point. | Would void the whole thing. | **Zero changes** to `com.example.api.*`. Verified by `git diff --stat` on that package at the end. Findings are documented, not fixed. |

### 2.D What the audit changed

The audit added, on its own evidence: C7 (negatives in the main dictionary), C8
(delete-all included), C9 (GET producers), the no-reset variants, the
`max_combinations` sweep, the separate screening seed set (B8), the model-derived spec
rule (C9), and the behaviour-preservation gate (C6). It also fixed the headline: the
like-for-like number is **C6**, not the best of the vocabulary-unmatched arms.

---

## Part 3 — Design, post-audit

### 3.1 Held constant across every arm

1. **System under test** — `com.example.api.*` byte-identical; same `application.properties`
   (`server.error.include-exception=true`, needed by the oracle); one fresh API JVM per trial,
   which resets both the maps and the `AtomicLong` id counters.
2. **Transport** — `harness.HttpTransport`: one pooled `HttpClient`, HTTP/1.1, 5 s connect,
   10 s request, identical headers.
3. **Oracle** — `harness.BugOracle`: `errorKind` (`GUARD` / `CRASH:<Class>`), `bugSignature`,
   `requestShapeKey`, `bugPathKey`.
4. **Budget** — `R = 250,000` fuzzing requests, hard stop, enforced in `HttpTransport`.
5. **Environment** — collection-wipe reset every `W = 50,000` requests (5 windows), both arms;
   reset requests count against `R`.
6. **Bookkeeping** — `harness.RunRecorder`: first-fire request index and episode/generation per
   signature, shape coverage, fire counts, `sampleChainDepth` arming diagnostics, `SCORECARD`.
7. **Trials** — one seed per trial; paired seed sets across arms; screening seeds and headline
   seeds disjoint.

### 3.2 The only thing that varies

The tester. Nine RESTler configurations, plus the two incumbent arms:

| Arm | Tester | Mode | Checkers | Value vocabulary |
|---|---|---|---|---|
| **A** | SARSA | ε=0.3, α=0.01, 16 units, 35 steps | — | `PayloadGenerator` (1,068 shapes) |
| **B** | Random | ε=1.0, same harness | — | `PayloadGenerator` |
| **C1** | RESTler | `bfs` | none | RESTler defaults (§1.3) |
| **C2** | RESTler | `bfs-fast` | none | RESTler defaults |
| **C3** | RESTler | `random-walk` | none | RESTler defaults |
| **C4** | RESTler | `bfs` | InvalidValue + PayloadBody | RESTler generator (§1.4) |
| **C5** | RESTler | `bfs-fast` | InvalidValue + PayloadBody | RESTler generator |
| **C6** | RESTler | best of C4/C5 mode | InvalidValue + PayloadBody | **`PayloadGenerator` — matched to arm A** |
| **C7** | RESTler | `bfs-fast` | none | RESTler defaults **+ negatives in main dictionary** |
| **C8** | RESTler | best mode | as C4 | + `DELETE /api/{collection}` in grammar |
| **C9** | RESTler | best mode | as C4 | + GET producers enabled |

Robustness: `A-NR`, `C-NR` — no windowed reset. Sweep: checker `max_combinations` ∈ {20, 100, 500}.

**Headline comparison = A vs C6** (identical vocabulary; only the search differs).
**Secondary = A vs max(C1…C9)** (RESTler at its best, however it gets there).

### 3.3 Matched grammar

24 request types — exactly `HttpType{GET, GET_ALL, POST, PUT, PATCH, DELETE}` ×
`Endpoint{ITEMS, PRICES, DISCOUNTS, POINTS}`, which is precisely arm A's addressable
surface. Derived from the controllers; `DELETE /api/{collection}`, `HEAD` and `OPTIONS`
excluded per A6/A7 and tested separately in C8.

Producers (`POST`/`PUT` 2xx responses yield `id`), consumers from the model classes:

```
POST   /api/prices        consumes items.id        (body item.id)
POST   /api/discounts     consumes prices.id       (body price.id)
POST   /api/points        consumes discounts.id    (body discount.id)
GET|PUT|PATCH|DELETE /api/{r}/{id}   consumes {r}.id   (path)
PUT|PATCH /api/prices/{id}      additionally consumes items.id
PUT|PATCH /api/discounts/{id}   additionally consumes prices.id
PUT|PATCH /api/points/{id}      additionally consumes discounts.id
```

Fuzzable (non-dependency) blocks, from the model classes — this is the C9 fix:

| Request | Fuzzable blocks |
|---|---|
| `*/items` | `name`:string, `description`:string, `quantity`:int |
| `*/prices` | `price`:number |
| `*/discounts` | `discount`:number |
| `*/points` | `points`:int |

### 3.4 Budget, stopping, reset

- `HttpTransport` holds the single request counter and throws `BudgetExhausted` at `R`.
  Both testers unwind cleanly; partial episodes/sequences are fine and recorded as such.
- Reset at every multiple of `W`: `DELETE /api/items|prices|discounts|points`
  (collection form — harness-only, not in the fuzzer's grammar), then the tester drops
  its cached ids (arm A) or its sequence pool and dynamic objects (arm C).
- `sampleChainDepth()` runs at each window boundary for every arm, excluded from `R`.

### 3.5 Metrics

Per trial, one machine-readable `RESULT` line in the existing format, extended:

```
RESULT arm=C6 seed=2001 d1req=<idx|none> d2req=<idx|none> guardreq=<idx|none>
       d1ep=<ep/gen> sigs=N paths=N shapes=N req=N fires=N armed1=N armed2=N
       gen=<max generation> seqs=<valid sequences> aborts=N secs=N
```

Analysis:
- P(find D1 | R), P(find D2 | R) with Clopper-Pearson CIs; Fisher exact between arms.
- **Discovery CDF over request index** — the primary figure; makes every smaller budget readable.
- Median first-fire request index among finders.
- Arming diagnostics (`armed1`/`armed2`) — the README's existing instrument for *why* an arm fails.
- Requests per sequence-attempt / per episode — the exchange-rate column (A3).
- Wall-clock-matched rates as a secondary table.

### 3.6 Fidelity controls

- **Deviations register** in the README: every point where the reimplementation differs
  from Part 1, with the reason.
- **Fidelity table**: each Part 1 behaviour ↔ the method implementing it ↔ the assertion
  covering it.
- **Differential smoke test**: on the matched grammar, assert the generation-by-generation
  sequence counts BFS must produce (hand-derivable from the dependency graph), that
  `bfs-fast` keeps exactly one sequence per request type, that `random-walk` keeps one,
  and that `bfs` keeps every valid rendering.
- **Invariant suite** extended alongside `SarsaSelfCheck`: budget never exceeded, oracle
  identical across arms, reset symmetry, no 5xx missed in a prefix.

### 3.7 Implementation map

```
com.example.harness
  HttpTransport     one client, one request counter, budget enforcement, transport-failure tally
  BugOracle         errorKind / bugSignature / requestShapeKey / bugPathKey   (moved from SarsaRestTester)
  RunRecorder       firstSeen, fires, shapes, arming sample, window logs, run summary, SCORECARD
  Tester            interface { run(budget) }  — implemented by both arms
  TrialRunner       CLI: arm, seed, budget, window, options → one RESULT line
com.example.restler
  ApiGrammar        24 request types, producers/consumers, fuzzable blocks   (spec-derived)
  Dictionary        RESTler defaults | RESTler value generators | PayloadGenerator-matched
  Sequence          render-with-prefix-replay, validity, bug detection
  SequenceSearch    bfs | bfs-fast | bfs-cheap | random-walk  (driver.py:62 + :167)
  checker/InvalidValueChecker, checker/PayloadBodyChecker
com.example.sarsa
  SarsaRestTester   refactored onto the shared harness; behaviour-preserving (C6 gate)
```

### 3.8 Run schedule

1. **Gate** — behaviour preservation of arm A; invariant suites green.
2. **Smoke** — every arm, `R = 20,000`, 1 seed: assert each arm reaches the trivial
   `DELETE+POINTS+GUARD`, that budgets are honoured exactly, and that request accounting
   is exact.
3. **Screening** — `n = 15`, seeds 3001–3015 (screening set), all of C1–C9 + the
   `max_combinations` sweep. Picks the best RESTler configuration.
4. **Headline** — `n = 45`, seeds 2001–2045 (headline set, disjoint from screening):
   arms A, B, C6, and the screening winner.
5. **Robustness** — `n = 15` on headline seeds: `A-NR`, `C-NR`.
6. **Write-up** — README section in the existing format: protocol, table, CDF, Fisher
   tests, retractions if any, deviations register, and a plain statement of what the
   experiment does and does not establish.

Throughput: ~250k requests/trial at ~1,200 req/s ≈ 3.5 min/trial. ≈ 190 trials total
≈ 11 h sequential, ≈ 4 h at three concurrent streams on ports 8086+.

---

## Part 4 — Fidelity table and deviations register

Written against the implementation, so a reader can check the port rather than trust it.
`driver.py`, `sequences.py`, `response.py`, `restler_settings.py` are under
`restler/engine/`; `Grammar.fs`, `Dictionary.fs`, `Dependencies.fs` under `src/compiler/`.

### 4.1 Reproduced behaviour

| RESTler behaviour | Source | Implemented in | Pinned by |
|---|---|---|---|
| Append request iff `consumes ⊆ seq.produces` | `driver.py:42` | `Sequence.satisfies` | "edges reproduce the chain" |
| Request-type-major extension order | `driver.py:100` | `RestlerFuzzer.extend` | "bfs-fast one per request type" |
| `bfs`: extend every sequence by every request | `driver.py:62` | `extend`, no `break` | "bfs extends every sequence" |
| `bfs-fast` / `bfs-minimal`: one sequence per request type | `driver.py:116` | `break` in `extend` | "bfs-fast appends each type once" |
| `random-walk`: keep one random extension | `driver.py:125` | `extend` tail | "random-walk keeps exactly one" |
| Prefix re-sent per rendering, cached combinations | `sequences.py:449` | `RestlerFuzzer.render` | request-per-rendering column |
| Only the last request iterates combinations | `sequences.py:420` | `renderOne` | — |
| Validity = `{200,201,202,204,304}` | `response.py:8` | `isValidCode` | "validity is 2xx/304 only" |
| 5xx anywhere is a bug, prefix included | `response.py:163` | `render` prefix loop | — |
| `bfs`/`bfs-fast` exhaust all renderings | `driver.py:270` | `renderOne` phase 2 | — |
| cheap/minimal/random-walk stop at first valid | `driver.py:258` | `stopsAtFirstValid` | — |
| Checkers skipped after a prefix failure | `invalid_value_checker.py:197` | `applyCheckers` | — |
| Checker fires once per (generation, request type) | `invalid_value_checker.py:215` | `firstTimeThisGeneration` | — |
| Checker re-executes the prefix | `checker_base._execute_start_of_sequence` | `replayPrefix` | — |
| Checker fuzzes one parameter at a time | `invalid_value_checker.py:300` | `invalidValueChecker` | — |
| Checker budget = `max_combinations / blocks` | `invalid_value_checker.py:266` | same | — |
| `max_combinations = 20` | `restler_settings.py:318` | `Config.maxCombinations` | "capped at max_combinations" |
| `max_sequence_length = 100` | `restler_settings.py:322` | `Config.maxSequenceLength` | — |
| Checker `max_combinations = 100` | `Checkers.md:177` | `Config.checkerMaxCombinations` | — |
| Default dictionary, one value per primitive | `Grammar.fs:537`, `Dictionary.fs:226` | `DefaultDictionary` | "no negative value" |
| Invalid-value generator, incl. negatives | `invalid_value_checker_value_gen.py:55` | `RestlerValueGenerator` | "does produce negatives" |
| Boundary values first | `..._value_gen.py:22` | `boundaryValues` | "boundary values come first" |
| Values spliced unescaped | `primitives.py` concatenation | `RestlerValueGenerator` | — |
| Nested-object-id producer inference | `Dependencies.fs:219` | `ApiGrammar` edges | "edges reproduce the chain" |
| POST/PUT are producers, GET optional | `Dependencies.fs:236` | `RequestType.produces` | "edges reproduce the chain" |

### 4.2 Deviations, with direction of effect

| # | Deviation | Why | Who it favours |
|---|---|---|---|
| D1 | **Restart the search when the pool dies.** The real tool raises `ExhaustSeqCollectionException` and exits (`driver.py`). | A fixed request budget would otherwise score this arm on requests it never sent. Re-running the tool is what an operator does. | **RESTler, heavily — and this one is load-bearing.** Measured: the `random-walk` trial that found Defect 1 restarted **300 times** in 250k requests, i.e. it got 300 independent searches where the real tool would have ended after the first. Since `random-walk` reaches the defect only by sampling prefixes luckily, those 300 attempts are plausibly *why* it succeeded. Without this deviation RESTler's rate on this defect would very likely be zero. Restarts are counted on every RESULT line (`restarts=`, `wrestarts=`) so the reader can judge it. |
| D2 | **Windowed server reset** every 50k requests, which the tool knows nothing about. | The same environment policy the RL arm runs under; without it, `GET_ALL` cost grows without bound. | Neutral by construction; measured both ways (`A-NR`, `C-NR`). |
| D3 | **`PayloadBodyChecker` is an approximation** — the documented mutation classes under the real trigger and dedup rules, not the full FSE'20 search. | The full checker is a large schema search. It cannot produce a negative number, so it cannot reach either defect. | Immaterial to the headline; included so a default checker is not simply missing. |
| D4 | **Collection `DELETE`, `HEAD`, `OPTIONS` excluded** from the grammar. | Matches the RL arm's addressable surface exactly (audit A6/A7). | **RESTler** — including collection `DELETE` lets it wipe its own chains. Measured in C8. |
| D5 | **No garbage collector.** | Off by default (`restler_settings.py:418`) and inert here: every single-resource `DELETE` is a planted 500 that deletes nothing. | Neutral. |
| D6 | **No auth, no `query`/`header` fuzzing, no `uuid4_suffix`.** | The API has none of those. | Neutral. |
| D7 | **This project's oracle, not RESTler's bug buckets.** | "Found Defect 1" must mean one thing across arms. | Neutral; RESTler's own bucketing would merge the two crashes behind one `DELETE /points` sequence, which would *understate* it. |
| D8 | **Java, not Python.** | One transport, one process model, comparable wall clock. | **RESTler** on the wall-clock axis — it loses the interpreter overhead the real tool pays. |

Four of the eight deviations favour RESTler and none favours the RL arm. That is
deliberate: where a judgement call existed, it was made against the incumbent.

### 4.3 What the arms cannot do that the real tool can

Stated so the result is not over-read: this arm has no `UseAfterFree`,
`ResourceHierarchy`, `LeakageRule`, `NameSpaceRule` or `InvalidDynamicObject` checker.
All five look for *response-shape* violations (a deleted resource still readable, a
child reachable from the wrong parent, an unauthorised read) rather than 5xx, and this
API's oracle is a 500. Omitting them cannot change either planted defect's discovery.
They would, however, matter on an API with authorisation or real deletes - this
experiment is not evidence about those.

---

## Part 5 — Audit addenda found during implementation

The Part 2 audit was written against the design. Building it surfaced four more fairness
problems that reading the plan could not have caught. Recorded here because each one
would have produced a wrong conclusion that looked perfectly reasonable.

### 5.1 Three dictionary-construction bugs, each of which faked a RESTler failure

`MatchedValueSource` builds its dictionary by walking the RL generator's own axes. Three
successive versions of that walk each produced a *confident, plausible, wrong* result —
arm C6 scoring zero while looking like a search-strategy finding. They are worth listing
individually because they are the same mistake in three disguises, and because the
property they violate is not obvious: **when a search caps how many values per parameter
it will try, the dictionary's iteration order is part of the experiment's design.**

**(a) Field-major walk → an all-positive dictionary.** The first version varied *field*
fastest, so the first values produced were all `Strategy.NONE` and `Strategy.VALID`
payloads. The main driver reads only the first `max_combinations` (20) values of a leaf,
so the dictionary the search actually saw contained **zero negative prices**:

```
 0  395.61   NONE        8  771.13  NONE       16  385.58  NONE
 1  817.52   NONE        9  627.10  NONE       17  203.26  VALID
 ...                    12  875.50  VALID      19  662.33  VALID
negatives in first 20: 0
```

Fixed by walking *strategy* fastest: one value from each class in the first slots.

**(b) Raw enum walk → shape keys outside the measured space.** The walk used
`Strategy.values()` and `Field.values()`, which include `NONE`. The RL arm folds
`NONE → VALID/ALL` before keying (`StrategyState.getEffectiveStrategy/Field`), so it
never emits a `NONE` label. This arm's shape count came out at **1,287 — outside the
1,068-shape space arm A is scored on**, silently breaking the one coverage comparison
that is supposed to be like-for-like. Fixed by excluding `NONE` from both axes.

**(c) Shared cursor → the chain was unreachable at all.** The worst of the three. The
cursor was shared across leaves, so a leaf's index-0 value depended on how many *other*
leaves had been asked for first. And index 0 is not an ordinary entry: when
`max_combinations` is smaller than the product of the leaves' dictionary sizes — it is,
20 < 20×20×20 for `/items` — the renderer freezes every leaf but one at index 0 in
*every* combination. Real RESTler does the same thing (`itertools.product` truncation),
but its index 0 is the primitive's single default value, always usable. Mine was:

```
name         index0 = "zYI"     strategy=VALID
description  index0 = "cBFFS"   strategy=ENCODING
quantity     index0 = null      strategy=NULL_INJECT   <-- every POST /items -> 500
```

`ItemController.createItem` unboxes `getQuantity() < 0`, so a null quantity is an NPE.
**Every rendering of `POST /items` returned 500, `items.id` was never produced, and
nothing downstream of Item was reachable.** Measured directly: `POST /prices` was sent
**0 times in 20,000 requests**. Every vocabulary-matched arm would have scored zero on
both defects, and the table would have read as a clean RESTler failure.

Fixed by being an actual dictionary: **one fixed list per parameter**, computed once and
shared by every request type that uses it, with `VALID` at index 0 — which is RESTler's
own model, where `restler_fuzzable_number` is a single list used everywhere it appears.

Pinned by four invariants: a negative exists within `max_combinations` for every numeric
leaf; index 0 of every leaf is `VALID`; combination 0 renders every leaf `VALID`; and
every emitted shape key is inside the RL arm's 1,068-shape space.

### 5.1b Why an invariant suite was not enough — the smoke gate

All of (a), (b) and (c) are invisible to a structural test suite: the grammar was right,
the strategies were right, the budget was right. What they broke was the arm's *ability
to exercise the API at all*, which only shows up against a live service.

`RestlerSmokeCheck` now runs every configuration briefly against a real server and gates
on one property: **the dependency chain resolves end to end** (an Item, a Price, a
Discount and a Point all exist in the store). One Point proves all four producer-consumer
edges resolved, so the check is budget-insensitive, and its failure is always a harness
bug rather than a property of the configuration.

Addressability, depth and poison-in-store are reported but deliberately **not** gated.
`bfs` reaches generation 5 in 20k requests where `bfs-fast` reaches 41 — gating on depth
would have rejected RESTler's own default mode for being slow, which is precisely the
kind of silent disadvantage this whole audit exists to prevent. That breadth-versus-depth
gap belongs in the results table.

### 5.2 Harness requests are excluded from the budget, not charged to it

Part 3 originally charged the window-reset `DELETE`s against the request budget. On
reflection that makes the budget depend on scaffolding rather than on the tester. The
rule is now: {@code fuzz()} counts, {@code harness()} does not, and the split is in
shared code so it cannot drift between arms. Scale: ~24 scaffolding requests per trial
against a 250,000 budget — 0.01%, and identical for both arms either way.

### 5.3 Reset cadence had to be checked between sequences, not between generations

The first implementation checked the window boundary once per BFS generation. A single
late generation can span tens of thousands of requests, so the reset could land far past
its nominal index and then fire repeatedly to catch up. The check now runs between
sequence renderings — accurate to within one rendering, which is the same granularity the
RL arm gets from checking between episodes. The boundary also advances *past* the current
request index rather than by one window, so an overshoot cannot produce a burst of
back-to-back resets.

### 5.4 `bfs` had to stream its extension or it would run out of memory before budget

In `bfs` mode the extended generation is up to `|grammar| × |pool|` sequences. With a
pool in the 10⁵ range that is millions of objects materialised only to be rendered one at
a time — and the request budget would have stopped the run long before most were reached,
so the memory was never going to be needed. Extensions are now produced lazily in exactly
the order `extend` would have produced them. Same semantics, same order, memory bounded
by the *valid* pool. Had this OOM'd instead, `bfs` would have been recorded as a failing
configuration for a reason that has nothing to do with RESTler.

### 5.5 The invariant suite was firing live traffic at port 8080

Not a fairness problem but worth recording. `SarsaSelfCheck` drives real episodes, and
`SarsaRestTester`'s default base URL is `localhost:8080` — so running the suite while
other experiments were live sent **44 requests** into one of them. It now points at
`localhost:1` unless told otherwise and prints its own traffic count. The right default
for a test suite that sends HTTP is a port nothing listens on.

### 5.6 Trials were being lost silently

The first trial runner piped the fuzzer's stderr to `/dev/null` and appended only lines
matching `^RESULT`. A trial that died therefore vanished: one config came back with
seeds 3001 and 3003 and no 3002, and nothing recorded that anything had gone wrong.

Silently dropping a crashed trial changes `n` and biases the arm — if deep-search
configurations are the ones that die, dropping them makes the survivors look better than
the configuration is. A trial that died is *data about the configuration*, not an absence
of data.

The runner now captures full output per trial, appends an explicit
`FAILED seed=… reason=…` line when no `RESULT` appears, classifies the reason
(`OutOfMemoryError`, `server-never-came-up`, …), keeps the log, and sets `-Xmx3g` so
memory pressure is a recorded condition rather than a silent disappearance. Trial
failures are surfaced the moment they happen rather than discovered during analysis.

### 5.7 `bfs` needed its producer sets cached

Not a fairness issue, but it shaped the schedule. `bfs` calls `validate_dependencies`
once per (request type, sequence) pair per generation — millions of times once the pool
is large — and each call was rebuilding the sequence's producer set. A `Sequence` is
immutable, so the set is now cached on first use. Pure performance, no semantic change;
without it `bfs` trials were slow enough to distort the experiment's wall-clock budget.

### 5.8 One vocabulary gap remains, and it gets its own arm

Matching the *set* of values does not match how a request is *composed*, and the two
testers compose differently:

- The RL agent picks one `(strategy, field)` dial pair and `PayloadGenerator` renders a
  whole body in that style — so `STRATEGY_NEGATIVE + FOCUS_ALL` sends *every* field
  negative at once.
- RESTler renders a Cartesian product of per-parameter dictionary values, capped, and its
  checker substitutes **one parameter at a time** while holding the rest at their original
  rendering. So arm C6 sends one negative leaf with the others valid.

This is a property of the testers, not of the vocabulary, and changing it would stop the
arm being RESTler. It also does not disadvantage it here: arming Defect 1 wants exactly
one negative leaf (`price`) with the rest valid, which is precisely what C6 sends, and
*not* the all-fields-negative body the RL arm may pick.

Even so, it leaves one objection open — "the bodies weren't the same bytes". So a further
arm closes it:

| Arm | Vocabulary match |
|---|---|
| **C6** | same value *set*, RESTler's own per-parameter composition |
| **C10** | same value set **and** the same whole-body composition — each rendering is a complete `PayloadGenerator` body for one `(strategy, field)` pair, indexed by combination id |

C10 is `restler_custom_payload` usage (a documented RESTler feature for replacing a whole
body) pointed at the RL arm's generator. With C10 the two arms have byte-identical request
*repertoires* and differ only in which request to send next and in what order — which is
the cleanest statement of the experiment there is. Both are reported.

### 5.9 The mechanism screening exposed, and the two arms fairness then requires

Early screening shows a consistent and precisely explainable pattern, and it is a
property of RESTler's design rather than of this port. Both halves follow from
`driver.py` directly:

- **`bfs-fast` / `bfs-cheap` / `bfs-minimal` go deep but extend only the first valid
  rendering.** All modes keep every valid rendering of the last request
  (`driver.py:270`), so a sequence ending in `PATCH /prices` with a *negative* value
  **is** in the pool. But `extend` iterates request-type-major over the pool and
  `break`s after the first satisfiable sequence (`driver.py:116`), and the pool is in
  combination order — so the sequence that gets extended is combination 0, the valid
  value. The poisoned prefix sits in the pool and is never built upon.
- **`bfs` extends every rendering but drowns in breadth.** With a rich dictionary its
  pool multiplies by ~(combinations × satisfiable requests) per generation while each
  rendering costs one request per prefix element, so the 250k budget is spent by
  generation 3–4. The defect needs depth 6.

Net: the configurations that *could* carry the poison forward cannot reach the depth, and
the ones that reach the depth do not carry it forward. Observed directly —
`bfs-fast` + matched vocabulary puts 85 negative prices and 91 negative discounts in the
store and still never links a Point to one (`armed1 = 0`).

That is a real finding, but reporting it without testing the obvious escape would be
exactly the kind of quiet disadvantage this audit exists to prevent. Two more arms:

**C10 — whole-body composition** (§5.8): closes the "bodies weren't the same bytes" gap.

**C11 — `dict=matched-negfirst`, interesting value at index 0.** Dictionary order is a
user's choice, and RESTler's own invalid-value generator puts its boundary classes first,
so ordering the dictionary with the negative first is a realistic configuration. It
should also *work*, and the reasoning is worth stating in advance of the data:
`POST /prices` would reject combination 0 (negative, 500 guard) and settle on
combination 1 (valid), so the chain still builds — but `PATCH /prices` **accepts**
combination 0, making the negative rendering the first valid one and therefore the one
`extend` carries forward. `POST /discounts` then links a poisoned price, and the chain
completes.

If C11 finds Defect 1, the honest headline is not "RESTler cannot" but *"RESTler can,
and whether it does is decided by the order of its mutations dictionary"* — a sharper and
more useful claim than either side of a win/lose framing. Note also what C11 cannot fix:
RESTler dictionaries are per-primitive (optionally per-endpoint), never per-method, so one
ordering has to serve both `POST /prices` and `PATCH /prices` on the same endpoint. That
constraint is the real limitation, and it is structural.

### 5.10 My §5.9 prediction about dictionary ordering was wrong — and the reason is the finding

§5.9 predicted that a negative-first dictionary would let `bfs-fast` carry a poisoned
prefix forward: `PATCH /prices` accepts the negative at combination 0, so the negative
rendering becomes the first valid one and the one `extend` carries forward. The data says
otherwise — `bfs-fast` with per-endpoint negative-first ordering puts **156 negative
prices and 96 negative discounts** in the store and still never links a Point to one.

The prediction confused two different "firsts". Dictionary order decides which
**rendering of the last request** is kept. It has no influence over which **prefix** gets
extended, and that is the one that matters.

Walk `extend` (`driver.py:100`) at generation 4, looking for somewhere to put
`POST /discounts`, which consumes `prices.id`. It iterates request-type-major over the
generation-3 pool and takes the *first* sequence that satisfies the dependency. The
generation-3 pool is itself ordered by the request type that terminated each sequence, so
it opens with `GET`-terminated sequences — and `POST /items → POST /prices → GET /items/{id}`
satisfies `prices.id` perfectly well while containing no `PATCH` at all. `POST /discounts`
is appended to *that*, linking a clean price. The poisoned sequence
`POST /items → POST /prices → PATCH /prices(negative)` sits in the pool, equally valid,
and is never built upon.

So the real limitation is deeper than value selection:

> **RESTler's sequence search is state-blind.** It chooses what to extend by position in
> the grammar, never by what the service's state has become. No dictionary, no checker and
> no value ordering can express "extend the prefix that poisoned the resource", because
> the pool has no notion that one prefix left the service in a more interesting state than
> another.

Only the exhaustive mode escapes this, because it extends *every* valid rendering and so
necessarily extends the poisoned one too — and `bfs`'s breadth then makes the required
depth unreachable: pool size multiplies by roughly (valid renderings × satisfiable
requests) per generation while each rendering costs one request per prefix element, so
the 250k budget is spent around generation 3–5 and the defect needs 6.

That is the complement of this project's existing result, and the pair is the interesting
part. Both testers are limited by what their representation can hold, from opposite
directions:

| | can represent | cannot represent |
|---|---|---|
| **SARSA** | "I hold an id for this resource" (`hasValid*Id`) | "I have already poisoned this chain" |
| **RESTler** | the dependency structure, exactly and for free | anything at all about values or state |

The README's standing conclusion was that *state design is the binding constraint on
reachable bug depth*. This arm supports it from the other side: a tester with a perfect
structural model of the API and no state representation whatsoever hits the same wall, one
rung lower down.

Two further configurations are owed to RESTler before this is reported, and both are
measured rather than argued:

- **`bfs` + `maxcomb=2`** — the narrowest dictionary that still contains a negative, to
  find the deepest generation an exhaustive search can actually reach on this budget, and
  to put a number on how much budget depth 6 would need.
- **`random-walk` + negative-first** — the one mode that is *not* position-ordered: it
  keeps a single randomly chosen extension per generation, so it can pick the poisoned
  prefix, and it is cheap enough to reach depth. If anything in RESTler finds Defect 1,
  the mechanism says it should be this.

### 5.11 The RESTler arms are deterministic, which changes how many seeds they need

Screening shows the RESTler arms repeating **exactly** across seeds:

```
bfs + default dict :  gen=5  seqs=16977  rend=52360   (identical, seeds 3001-3003)
bfs-fast + checkers:  gen=28 seqs=2520   rend=3032    (identical, seeds 3001-3004)
bfs-fast + neg-pe  :  gen=16 seqs=12950  rend=16573   (identical, seeds 3001-3005)
```

This is correct, not suspicious. The main driver is a deterministic enumeration: the
grammar is fixed, the dictionary is fixed, `extend` and `render` are deterministic, and
the service's id assignment is deterministic given a fresh JVM. The *only* stochastic
element is the checkers' value generator — which is why `negprice` / `negdisc` vary a
little while `gen`, `seqs` and `rend` do not, and why `random-walk` is the one mode where
seeds change the search itself.

Two consequences, both of which the write-up must state:

1. **A zero at small n means much more here than it would for the RL arm.** `0/5` from a
   deterministic enumeration is near-conclusive for that configuration; `0/5` from an
   ε-greedy policy would be noise. The Clopper-Pearson interval does not know the
   difference, so the interval alone understates the evidence — the determinism has to be
   reported alongside it.
2. **Seed budget should go where variance is.** The RL arms (A, B) get `n = 45`, the
   README's own floor for a ~15% event. The RESTler arms get `n = 20`, which is already
   far beyond what a deterministic search needs and leaves room for the checker
   randomness and for `random-walk`. Unequal n is handled exactly by Fisher's test; the
   alternative — spending hours re-measuring an identical enumeration — would buy nothing.

A side finding worth keeping: **RESTler's default mode and default dictionary never reach
even the trivial five-request `DELETE /points` guard** within 250,000 requests (`0/3`),
while `bfs-fast` reaches it in every trial (`5/5`). `bfs` spends its whole budget on
breadth at generation 5. The shipped default is the wrong mode for a chained API, which is
itself useful to know and is not a statement about this experiment's defects at all.

### 5.12 RESTler finds Defect 1 — and which mode does it is the result

`random-walk` with the per-endpoint negative-first dictionary **found Defect 1** at
request 137,446 (screening seed 3002), with `armed1 = 193`: 193 stored Points whose
linked Price was negative, against `armed1 = 0` in every `bfs-fast` configuration.

This is the mechanism of §5.10 confirmed from the other side, and it refines that
section's claim. §5.10 said only the exhaustive mode escapes position-ordered selection.
That was incomplete — there are **two** escapes, and they fail differently:

| Escape from position-ordered selection | How | Why it does or does not work |
|---|---|---|
| `bfs` — extend *everything* | exhaustive: the poisoned prefix is extended because every prefix is | breadth costs the budget; reaches generation 5, needs 6 |
| `random-walk` — extend *one at random* | `driver.py:125` truncates each generation to one randomly chosen sequence | **works**: no position bias, so a poisoned prefix is as likely as any other, and the mode is cheap enough to reach generation 68 |

So the sharp statement is not "RESTler cannot represent state" alone. It is:

> RESTler has no state-aware sequence selection, and the only configuration that reaches
> this defect is the one that replaces selection with **randomisation**. Where the RL
> agent conditions on state, RESTler's successful mode simply declines to choose — and
> pays for it in reliability, needing ~300 search restarts and 137k requests for a hit.

Three things follow for the write-up, and all three must be stated:

1. **RESTler is not incapable here.** The headline cannot be "0% vs 91%". It is a rate
   comparison between a learned policy and a randomised grammar walk, and the RESTler
   number is whatever `random-walk` measures at `n = 20`.
2. **It needs a non-default configuration to do it.** `random-walk` is not the shipped
   mode (`bfs` is, `restler_settings.py:305`), and the dictionary ordering is a
   customisation. Reported as such: out-of-the-box RESTler finds neither defect, nor even
   the trivial five-request guard.
3. **`random-walk` is the one stochastic arm**, so it is the one RESTler configuration
   where seeds genuinely matter and `n` has to be real — unlike the deterministic modes of
   §5.11. It gets the full RESTler seed budget.

The two testers now look like two points on one axis rather than a winner and a loser:

| | sequence selection | value selection | reaches Defect 1 |
|---|---|---|---|
| RESTler `bfs` | exhaustive | dictionary | no — budget |
| RESTler `bfs-fast` | first-by-grammar-position | dictionary | no — never extends the poisoned prefix |
| RESTler `random-walk` | uniform random | dictionary | **yes**, unreliably |
| SARSA | learned, conditioned on id-state | learned per request | yes, reliably |

## Run a trial

Requires JDK 17+ and Maven. Start a dedicated API instance:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8086"
```

In another terminal, run a RESTler-port trial. This example uses random-walk search,
the per-endpoint negative-first dictionary and both implemented checkers:

```bash
mvn -q compile exec:java \
  -Dexec.mainClass=com.example.harness.TrialRunner \
  -Dsarsa.baseUrl=http://localhost:8086/api/ \
  -Dexec.args="arm=restler seed=2001 budget=250000 reset=50000 mode=random-walk dict=matched-negfirst-pe checkers=all"
```

For a SARSA trial through the same harness, restart the API and use:

```bash
mvn -q compile exec:java \
  -Dexec.mainClass=com.example.harness.TrialRunner \
  -Dsarsa.baseUrl=http://localhost:8086/api/ \
  -Dexec.args="arm=sarsa seed=2001 budget=250000 reset=50000 steps=1120 eps=0.3 neurons=16"
```

Use `arm=random` for the random policy. These examples show how to run individual
trials at a common request cap; they are not a script reproducing the full results
table. Start a fresh API process for each independent trial. The final `RESULT` line
records discovery request indices, coverage, request counts and runtime.

The port's checks run without a live API:

```bash
mvn -q compile exec:java -Dexec.mainClass=com.example.restler.RestlerSelfCheck
```
