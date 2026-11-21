# Adversarial review of the resumed spine

The revised spine resolves the original retry-accounting, evidence-boundary and publishing-atomicity gaps. Four seams still permit incompatible implementations.

## High priority

### A late report can change the outcome after Temporal history expires

AD-13 says that exhausting report retries ends with no links. A publishing attempt may overlap its timeout and leave a well-formed manifest after the Workflow has already returned that outcome. AD-14 allows a retained lookup to serve the Workflow result and an expired lookup to serve the manifest. Both implementations comply and the same review changes status at expiry. Freeze one authority rule for late or unacknowledged publications. Exercise the case where report publication succeeds immediately after its Activity timed out. Exercise a manifest appearing during a resubmission lookup, since that exception can otherwise permit an expired ID to start again.

### The attempt split changes the usable budget

AD-8 defines bounded allocations but leaves the per-attempt split open. One worker can spend 10 of the 30 tool calls on the first SINGLE attempt and another can spend 28 while both reserve the same total. AD-8 also says a returned budget stop ends the run. As a result, a healthy job can consume radically different tool counts before the same terminal reason. Bind the split or specify where configuration pins it for both invocation construction and Activity enforcement. Define the distinction between a per-attempt or per-invocation slice stop and exhaustion of the run budget. Record effective first-attempt allowances beside nominal totals in the experiment manifest.

### The cancellation wait and report window compete

AD-16 permits an acknowledgement wait within the Activity timeout envelope. AD-8 permits a Workflow execution backstop only five minutes later than the work deadline and AD-3 allows four minutes for report attempts. A conservative implementation may wait too long and lose reporting, while another reports promptly. Bind a cancellation grace within the margin, then record unknown usage when work cannot be confirmed. Specify that accepted cancellation wins until the Workflow freezes disposition.

## Medium priority

### Coverage types need canonical diff coordinates

AD-15 requires a union of observed units against preflight units. An API adapter may identify a rename using both paths while a tool uses the returned HEAD display path for both sides. They can generate incompatible coverage for the same change. Define coverage coordinates as the canonical change identity, explicit BASE or HEAD side, and one-based line. Use a separate descriptor unit for changes without textual units. Pin the compare and rename policy and define how redaction affects delivered units.
