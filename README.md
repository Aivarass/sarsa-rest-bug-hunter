# SARSA REST Bug Hunter
**Autonomous discovery of deep, stateful API bugs using reinforcement learning (pure Java)**

*Discovers 5-step API bug chains across 3.2 quintillion possible paths using 8 neurons.*

---

## Why this project exists

Stateless API testing rarely finds bugs that require specific sequences of dependent calls. You can't delete an item that doesn't exist.

This project demonstrates that a small reinforcement learning agent can autonomously learn these dependencies and discover deep, multi-step API bugs without domain knowledge, reward shaping, or external ML libraries.

---

## What makes this different

This project is not based on:
- LLM-generated test cases
- Prompt-driven diagnostics
- OpenAPI enumeration

Instead, the agent interacts with the API directly, observes outcomes, and learns which sequences of actions lead to defects. The goal is not to generate tests, but to learn how to test.

---

## The problem: why deep API bugs are hard to find

Real-world REST APIs often contain hidden state dependencies:
- An item must exist before it can be deleted
- A price cannot be created without a valid item ID
- A discount depends on a valid price ID
- Points depend on a valid discount ID

Certain defects only surface after a precise sequence of calls:

```
POST Item → POST Price → POST Discount → POST Points → DELETE Points → 500 error
```

**Why is this hard?**
- Random testing: 1 in billions chance to hit the sequence
- Scripted testing: Requires manual domain knowledge
- This agent: Learns the sequence from sparse reward signal

---

## Approach

- **Algorithm:** SARSA (on-policy temporal difference learning)
- **Learning signal:** Sparse rewards only (5xx responses)
- **Exploration:** Epsilon-greedy with action masking
- **Model:** Single hidden-layer neural network (8 neurons)
- **Implementation:** Pure Java, no ML frameworks or libraries

Why these choices? SARSA's on-policy updates match actual exploration behavior (off-policy can diverge with function approximation). 8 neurons is enough because the state representation does the heavy lifting. Sparse rewards means no reward shaping and no domain knowledge baked in.

---

## Key result

| Metric | Value |
|--------|-------|
| Maximum chain depth | 5 dependent API calls |
| Search space | ~3.2 quintillion paths |
| Hidden neurons | 8 |
| Time to discovery | ~30 minutes |
| Unique bug combos | 115+ |

### Learning Progression

| Episode | Avg Reward | Key Event |
|---------|------------|-----------|
| 0-100k | Negative | Exploring, learning dependencies |
| 120k | ~-1.5 | DELETE+ITEMS discovered |
| 130k | 77.7 | Exploitation begins |
| 150k | 102.5 | 5-step chain discovered |
| 160k | 103.2 | 91 unique bug combos |

---

## State Representation

The agent observes 14 features, normalized to [0, 1] for stable learning.

### Dependency Tracking (5 features)

| Feature | Purpose |
|---------|---------|
| `hasValidItemId` | Enables PRICE creation and ITEM operations |
| `hasValidPriceId` | Enables DISCOUNT creation and PRICE operations |
| `hasValidDiscountId` | Enables POINTS creation and DISCOUNT operations |
| `hasValidPointsId` | Enables POINTS operations |
| `hasAnyItems` | Indicates non-empty database |

These explicit flags let the network learn "DELETE is valuable when `hasValidPointsId=1`" rather than inferring dependencies from failed requests.

### Response Context (2 features)

| Feature | Purpose |
|---------|---------|
| `lastStatusCode` | Categorized: 2xx→0.25, 4xx→0.75, 5xx→1.0 |
| `lastMethod` | Last HTTP method used |

### Strategy State (7 features)

Tracks the agent's current request configuration (HTTP method, endpoint, field targeting, mutation strategy, intensity) and dial-turner timing.

All features normalized to [0, 1] to prevent gradient domination. Strategy state is included so the network can evaluate partially-configured requests.

---

## Technical details

### Hyperparameters

```java
ALPHA = 0.01      // Learning rate
GAMMA = 1.0       // Undiscounted for full credit assignment
EPSILON = 0.01    // Exploration rate
STEP_LIMIT = 35   // Steps per episode
```

### Reward function

```java
if (response == null) return -0.15;  // Dial-turner penalty
if (statusCode >= 500) return +10;   // Bug found
return 0;
```

### Running the project

```bash
# Terminal 1: Start the target API
mvn spring-boot:run
```

```bash
# Terminal 2: Run the SARSA agent
mvn test -Dtest=SarsaRestTester#executeSarsaTester
```

**Expected:** ~150k episodes, ~30 minutes to discover 5-step chain.

---

## Concepts demonstrated

- Stateful API testing (learns dependencies across calls)
- Property-based payload generation
- Exploration vs exploitation tradeoffs
- Action masking for constraint satisfaction
- Credit assignment over long sequences (γ=1.0)
- Pure Java RL implementation

### Why 8 neurons is sufficient

The state representation explicitly encodes dependency signals (`hasValidItemId`, etc.). The neural network learns combinations of features, not raw request patterns. Representational clarity matters more than network depth.

Experiments with 16 neurons show ~3x faster convergence to the same behavior, confirming 8 neurons is near the capacity floor rather than undersized.

### Credit assignment over 5 steps

With GAMMA=1.0 (undiscounted returns), value propagates fully across long action sequences. The agent learns that early actions (POST ITEM) are valuable because they enable future rewards, even though they produce no immediate signal.
