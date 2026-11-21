# Research digest: LLM / multi-agent code review landscape (as of 2026-11-21)

Scope: generic web research for PRD input. Most figures are **vendor-reported**. Where only search snippets were available, the claim is marked (snippet).

## (a) Commercial and open-source AI reviewers

| Tool | Positioning | Findings / noise handling |
|---|---|---|
| CodeRabbit | Established PR-review bot, broad language coverage, ships bundled linters/SAST | Reported as lower-noise than Greptile in several comparisons; top F1 on Martian's Code Review Bench (vendor blog post) |
| GitHub Copilot code review | "Zero-friction" default inside GitHub; agentic tool calls plus static analysis | Usually described as quietest but least thorough. GitHub's own ReviewBench (Oct 2026) has it leading at 40.1% "grounded F1". From June 2026 it bills AI credits plus Actions minutes |
| Claude Code Review (Anthropic, Mar 2026) | Multi-agent: parallel bug hunters, then a **verification pass**, then severity ranking | Scope deliberately narrowed to **logic errors** to keep false positives down; reports <1% of findings marked incorrect (internal use); about $15-25 and about 20 min per review; never approves PRs |
| Qodo (PR-Agent lineage) | Enterprise-scale, rules system, multi-agent | Own benchmark: 60.1% F1 on LLM-injected bugs |
| Greptile | Whole-repo graph context, high recall | Self-reported 82% catch rate (Augment's rerun: 45%); its catch-rate metric **excluded false positives and style comments** |
| Graphite | Workflow-first: stacked PRs, merge queue, AI review bundled | Differentiates on workflow, not on the bot |
| Augment, Propel, Macroscope, Entelligence, Cursor BugBot, Sourcery, DeepSource | Mixed | Each publishes self-run benchmarks, or none at all (CodeRabbit, Devin, BugBot) |

Common patterns: severity ranking, verification and filter passes, scope restriction (bugs over style), learned per-repo preferences, a human always makes the merge decision, and static analysis increasingly fused with the LLM.

Sources: https://claude.com/blog/code-review · https://infoq.com/news/2026/04/claude-code-review · https://thenewstack.io/github-reviewbench-code-review/ · https://www.greptile.com/ai-code-review-evaluation · https://qodo.ai/ai-code-review-benchmark/ · https://www.coderabbit.ai/ja/blog/coderabbit-tops-martian-code-review-benchmark · https://blog.bluedot.org/p/best-ai-code-review-tools-2025 · https://dev.to/filiphric/best-ai-code-review-tools-in-2026-top-7-compared-2kll

## (b) Multi-agent and specialist designs

- **Dimension split** (correctness / security / performance / style) is the dominant academic pattern. CodeX-Verify reports gains from combining agents, with diminishing returns per added agent (+14.9, then +13.5, then +11.2 points) on a small sample of 99 (snippet). https://arxiv.org/abs/2511.16708
- **Hierarchical or supervisor** designs: Code Broker (5 agents under an orchestrator) https://arxiv.org/pdf/2604.23088. DeputyDev (separate security, performance and maintainability agents) https://arxiv.org/pdf/2508.09676
- **Industrial pattern:** parallel finders, then an independent verifier or critic, then dedupe and rank (Claude Code Review). The verifier stage is what makes precision viable.
- Takeaway: specialists raise recall, but each one adds cost and duplicate findings. Merge, dedupe and verify are first-class steps.

## (c) Evaluation approaches and benchmarks

- **Public academic:** SWR-Bench (1,000 manually verified PRs with full repo context; LLM judge reaches about 90% agreement with humans; tools are better at functional errors than at other kinds) https://arxiv.org/abs/2509.01494. ContextCRBench (hunk- and line-level localization and comment generation) https://arxiv.org/abs/2511.07017. CR-Bench-v2 (ties instances to SWE-Bench defects) https://arxiv.org/pdf/2611.02566
- **Vendor benchmarks** (Greptile, Qodo, Augment, Propel, Entelligence, DeepSource CVE-165, GitHub ReviewBench): small (50-165 items), self-scored, often with synthetic or LLM-labeled ground truth, and reported in incompatible metrics (recall vs F1 vs "detection rate"). The same repos scored 82% vs 45% depending on who ran them. https://deepsource.com/blog/ai-code-review-benchmarks · https://www.augmentcode.com/blog/we-benchmarked-7-ai-code-review-tools-on-real-world-prs-here-are-the-results
- Older CodeReviewer-style datasets reportedly have about 36% noisy ground truth (snippet).
- **Metrics worth adopting:** precision, recall and F1 on **seeded or reconstructed real bugs** with a published ground truth. Also track **false positives per PR** (noise as developers feel it, which F1 hides), localization accuracy (file and line), severity calibration, duplicate rate, and cost and latency per review. Report confidence intervals, and keep a blind LLM judge cross-checked against human labels.

## (d) Durable orchestration with Temporal

- **Known patterns:** every LLM call and tool call is an Activity; workflow code stays deterministic; disable the LLM client's own retries so Temporal owns the retry policy; mark content-policy and other 4xx errors **non-retryable**; heartbeat long or streaming LLM activities; keep large payloads out of history (pass references); use continue-as-new for long loops; version or patch workflow code for in-flight runs. https://go.temporal.io/platform-hub/ai-engineering/ai-patterns · https://docs.temporal.io/ai · https://temporal.io/blog/of-course-you-can-build-dynamic-ai-agents-with-temporal · https://temporal.io/blog/the-heros-journey-to-ai-durability-with-temporal
- **Pitfalls:**
  - Replay skips only *completed* Activities. If a call succeeds but its result is never recorded (worker crash, lost ack), the Activity re-runs: the LLM is billed twice and may return a different answer. **No exactly-once billing.**
  - Payload and event-size limits (about 2 MB per event, snippet) and history growth in agent loops.
  - Calling an LLM directly inside workflow code breaks determinism on replay.
  - Ecosystem integrations (OpenAI Agents SDK, Vercel AI SDK, LangGraph, Pydantic AI) wrap model calls as Activities automatically. Per-call checkpointing raises history volume, which is a cost and granularity trade-off. https://temporal.io/blog/building-durable-agents-with-temporal-and-ai-sdk-by-vercel · https://avchauzov.github.io/blog/2025/temporal-llm-pipelines-durable-execution
