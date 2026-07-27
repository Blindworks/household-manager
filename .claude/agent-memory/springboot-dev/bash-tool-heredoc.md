---
name: bash-tool-heredoc
description: Im Bash-Tool nie PowerShell-Here-Strings verwenden - @'...'@ landet als literales @ in der Commit-Message
metadata:
  type: feedback
---

Mehrzeilige Strings (Commit-Messages, Dateiinhalte) im **Bash-Tool** ausschliesslich
per POSIX-Heredoc uebergeben:

    git commit -F - <<'EOF'
    Zeile 1
    Zeile 2
    EOF

**Why:** Dieses Projekt laeuft auf Windows und bietet beide Shells an. Die
PowerShell-Here-String-Syntax `@'...'@` ist in Git Bash kein Sonderzeichen — das
fuehrende `@` wird zum ersten Zeichen der Commit-Message (real passiert am
2026-07-27, Commit musste per `--amend -F -` repariert werden). Das faellt erst
im `git log` auf, nicht beim Commit selbst.

**How to apply:** `@'...'@` gehoert ausschliesslich ins PowerShell-Tool, `<<'EOF'`
ausschliesslich ins Bash-Tool. Nach jedem Commit mit mehrzeiliger Message einmal
`git log -1 --format='%s'` pruefen.
