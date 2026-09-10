---
name: mockito-restub-after-thenthrow
description: when(mock.method()) re-stubbing after a thenThrow re-triggers the old exception before the new stub attaches; use doReturn/doThrow instead
metadata:
  type: feedback
---

Re-stubbing a mock method that is CURRENTLY stubbed to throw, using the
`when(mock.method()).thenReturn(...)` syntax, does not work: evaluating
`mock.method()` as the argument to `when(...)` is a real invocation, and
Mockito's stub lookup applies the *already registered* `thenThrow` stub to
that invocation immediately - the exception fires before `when(...)` can
attach the new stub. The test method itself then fails with that raw
exception (as an "Error", not an assertion "Failure") at the exact line of
the second `when(...)` call.

**Fix:** use `doReturn(value).when(mock).method()` (or `doThrow(...)`) for the
restub - the `do*().when()` syntax does not evaluate the method for real in a
way that triggers existing stubbing.

**Where this bit us:** [[presence-manual-refresh]] -
`PresencePollingServiceTest.flagWirdAuchNachFehlgeschlagenemAbrufWiederFreigegeben`
- first stub `deviceRepository.findAll()` to throw (simulating a DB failure),
assert the wrapping `IllegalStateException`, then tried to restub it to
return `List.of()` for a follow-up call proving the AtomicBoolean guard flag
was released in the `finally`. The plain `when().thenReturn()` restub failed
with the original `RuntimeException` escaping the test method.

**How to apply:** any test that stubs a mock to throw and then needs to
re-stub the SAME method to succeed later in the SAME test must use
`doReturn`/`doThrow` for the second stub, not `when(...)`.
