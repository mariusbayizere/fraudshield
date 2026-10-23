# Delta check: the configuration propagation contract, draft 3 to the approved split

- **Subject:** `docs/architecture/risk-configuration-propagation-contract.md`, from 8831400 (draft 3,
  reviewed in `config-propagation-contract-review-3.md`) to 5873764 (approved by the owner, as split).
- **Scope:** only the delta. (1) review 3's M2 fix in §3.3; (2) references left dangling by moving C8
  out; (3) the C9 correction (review 3 m3); (4) anything new that the edit got wrong.
- **Checked against:** V3 (`decision_states`), V4 (`alert_rules`, `alert_rule_versions`), V62, V64,
  `JdbcOverdueHolds`, `AlertRuleEntity`, `AlertRuleVersionEntity`, `RuleRepository`, both
  `TenantTransactions`, the OpenAPI rule operations, and `breaker-monitor-contract.md` at 5873764.
- **Reviewer:** independent; wrote none of this work. Nothing was built or run.

## Verdict: CONFIRMED_WITH_CHANGES

The M2 fix is sound for every writer that follows it. The deadlock and the 23505 on
`(rule_id, version)` are gone, and versions are still allocated in commit order. The C9 correction
is true. No rule depends on the removed C8. Seven MINORs remain, all wording: §8 still points at the
removed C8 and the removed C9 clause, §1 claims a defect this contract no longer removes, C7 still
says the triggers serialise, the backstop sentence overclaims, and §9 says m4 is answered by a grant
list that the text does not contain. None needs another review round. Fix them before code is
written against the document.

## (1) Review 3's M2: verified fixed

**What changed.** §3.3 (:133-143) now says:
- every rule write (`createRule`, `createRuleVersion`, `setRuleState`) runs in READ COMMITTED
  through C6's retrying runner;
- it takes RULES as its first statement;
- the trigger takes RULES again, re-entrantly, as a backstop.

The three named writes are all the rule writes. The OpenAPI file has `listRules`, `createRule`,
`previewRule`, `createRuleVersion` and `setRuleState`, and only those three of them write. No Java
code writes `alert_rules` or `alert_rule_versions` yet. The decision module's entities and
`RuleRepository` only read.

**The deadlock is gone.** Review 3's cycle needed T1 to hold the no-key-update row lock on X while it
waited for RULES in its trigger. With the lock taken first, T1 waits for RULES before its UPDATE
runs, so it holds no row lock while it waits. Every row lock on a rule of institution I (V4:123-156)
is taken under I's RULES lock. Two conforming writers therefore never hold row locks in opposite
orders.

**The 23505 is gone.** In READ COMMITTED each statement takes a new snapshot. So
`createRuleVersion` chooses `max(version) + 1` for `(rule_id, version)` (V4:151) after the lock, and
it sees the previous writer's commit. The second of two concurrent edits gets the next number, not
the same one. C7's "last-writer-wins … both stay" is now true.

**Commit-order allocation still holds.** The trigger reads `max` of `alert_rule_set_versions` under
RULES, and RULES is held until the commit is visible. Re-entry is harmless: a transaction can take
the same transaction-level advisory lock again at once, and it is still "one such lock" (§2).

**Implementation conditions** (not findings; implementation review should check them):
- The lock must be a separate statement. A lock call inside the same statement as the write, in a
  CTE for example, reads under that statement's earlier snapshot.
- If the write uses JPA, nothing may be persisted before the lock call. Hibernate flushes pending
  entities before a native query, so a row written earlier would reach the database before the lock.
- Review 3's fix 4, a P10 or P7 case for `setRuleState` racing `createRuleVersion`, was not adopted
  (see n4).

## (2) Removing C8: dangling references

- `the leader` (§2), P11 and the §6 breaker cost were removed cleanly. Nothing else uses "leader",
  "lease" or `R_monitor`.
- C5 now points at the breaker-monitor contract.
- C3, C4 and P1-P15 did not depend on C8.
- That contract takes the snapshot versions from this one's C2 to C4 and P2, all of which are
  unchanged.
- Three leftovers remain: n1, n2 and n3.

## (3) The C9 correction: true

- V3:95-114 gives `decision_states` no threshold-version column, and V62/V64 add none. Only V6 has
  threshold-version columns.
- `JdbcOverdueHolds.SELECT_OVERDUE` recomputes the policy from `decided_at` against
  `risk_threshold_versions.effective_at`, which is "today's computation from `decided_at`".
- So C9's "stored policy, else today's computation" (:291-293) matches the code and the schema, and
  the residual in §6 (:330) stays true.
- §8 still describes the removed clause (n2).

## (4) New problems the edit introduced

See n5 (backstop), n6 (m4) and n7 (the "three ways" claim).

## Findings

### n1. MINOR: §8 row N10 cites "C8's own bound", which no longer exists

- **Evidence.** :353, "C5 restated; C8's own bound". C8 (:281-286) is now only a pointer, and has no
  bound.
- **Fix.** "C5 restated; the breaker bound moved to `breaker-monitor-contract.md` §4."

### n2. MINOR: §8 row N9 still describes the removed C9 fallback

- **Evidence.** :352 says "C9: the decision's own version first". C9 (:291-293) now says that no
  table records that version, so it cannot be used. Review 3 had rated N9 PARTLY because of this
  clause.
- **Fix.** "C9: stored policy, else today's computation (review 3 m3); the residual stated in §6."

### n3. MINOR: §1 lists "Racing monitors" as a defect this contract removes

- **Evidence.** :66 says "Defects this contract removes", and :78-79 lists racing monitors. Only C8
  removed that defect, and C8 has moved out.
- **Fix.** Move the bullet out of the list, or mark it "removed by `breaker-monitor-contract.md`".

### n4. MINOR: C7 still says the triggers serialise rule writes, and the race test was not adopted

- **Evidence.** :272 says "Rule writes are serialised by the RULES lock in the §3.3 triggers". After
  the fix, the lock taken as the first statement serialises them. The trigger only backs it up. A
  reader who keeps C7's wording and leaves out the first-statement lock gets review 3's deadlock
  back.
- Review 3's fix 4, a race case in P10 or P7, is neither adopted nor listed in §9.
- **Fix.**
  - Make it "serialised by the RULES lock each rule write takes first (§3.3)".
  - Add to P10: "`setRuleState` racing `createRuleVersion` on one rule, and two `createRuleVersion`
    on one rule, both commit or get 409, with no 40P01 and no 23505 surfacing".

### n5. MINOR: the backstop sentence overclaims

- **Evidence.** :140-142 say "A writer that forgot it still allocates correctly, although it can then
  deadlock, and the runner retries".
  - Only the rule-set version is still allocated correctly. A writer that forgot the lock chooses its
    `(rule_id, version)` before any lock, so review 3's 23505 comes back for it.
  - C6's runner retries only 40001 and 40P01 (:255-256), so it does not retry a 23505.
  - A writer that skipped the lock may also have skipped the runner. In a deadlock with a writer that
    follows the rule, PostgreSQL may abort either one.
- **Fix.** "A writer that forgot it still allocates the rule-set version in commit order. It can
  deadlock with conforming writers, or fail with 23505 on its rule version, so forgetting it is a
  defect; the backstop protects only the set version."

### n6. MINOR: §9 says m4 is answered by a grant list that §3 does not contain

- **Evidence.** :371-372 say "m4 … is part of §3's grant list: SELECT and INSERT to `fs_app`".
  - §3 has no such list. §3.3 (:123-145) names no grants.
  - §3.5 (:151-157) grants EXECUTE on two functions only.
  - Without the grant, every rule write fails, because the triggers are not SECURITY DEFINER (review
    3 m4).
- **Fix.** Add to §3.3: "`GRANT SELECT, INSERT ON alert_rule_set_versions TO fs_app`; SELECT to
  `fs_compliance_ro` and `fs_app_readonly` as V11:56-71". Or list m4 as carried in §9.

### n7. MINOR: "changed in only three ways" is not accurate

- **Evidence.** :18-21 name three changes. The diff also:
  - rewrites the status line and the C5 pointer;
  - rewrites §8 N1;
  - adds §9, including the m4 claim in n6.
- These are small, but a reader relying on "only three" would not check §9's m4 claim.
- **Fix.** Add "and §9 records review 3's remaining MINORs", and fix n6.

## Not findings

- **A concurrent `createRule` with a duplicate name.** Under the lock, the second writer meets the
  committed `UNIQUE (institution_id, rule_name)` (V4:135) and fails with 23505 straight away, without
  waiting. That is a real conflict, and it predates this edit. The implementation should map it to
  409.
- **The breaker-monitor contract.** It is outside this check, and it is marked "draft 1, for
  independent review". This check only confirms that it takes over what C8 governed.
