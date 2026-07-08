---
name: dedup-composite-hash
description: DedupHasher must always use composite hash — reference-only key caused recurring payment collapse
metadata:
  type: feedback
---

Always use composite dedup hash in DedupHasher. Never use bank reference (AcctSvcrRef or EndToEndId) as sole key.

**Why:** Recurring payments (salary, standing orders) reuse the same EndToEndId every month. Using EndToEndId as the sole key causes all occurrences to hash identically and collapse to one imported row. Example: 12 salary rows with EndToEndId "B2004D" produced only 1 imported transaction.

**How to apply:** The composite is `accountId | bookingDate | amount.toPlainString() | counterpartyIban | purpose | endToEndId`. Drop accountServicerReference entirely — it is not stored on the Transaction entity and cannot be reproduced by the recompute maintenance job.

Two public methods required for consistency:
- `hash(Long accountId, ParsedTransaction tx)` — used at import time
- `hash(Long accountId, Transaction tx)` — used by recompute; reads the same fields from the entity
Both delegate to one private `compositeHash(...)` method.
