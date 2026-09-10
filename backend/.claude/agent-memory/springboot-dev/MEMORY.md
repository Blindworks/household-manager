# Memory Index

- [Dedup composite hash rule](feedback_dedup_composite_hash.md) — DedupHasher must always composite-hash; reference-only key collapses recurring payments
- [Alexa auth flow structure](project_alexa_auth_flow.md) — AlexaAuthService PKCE login port from alexa-cookie; known deviations/risks, unverified E2E
- [Day-relative text windows](feedback_day_relative_text_windows.md) — announce windows with "morgen"/"heute" text must clamp to LocalTime.MAX, never wrap past midnight
- [Presence-wifi review fixes](presence-wifi-review-fixes.md) — cleanup steps must sit outside the try/catch they compensate for; check aggregates for the same orphan-freeze hole as their inputs
