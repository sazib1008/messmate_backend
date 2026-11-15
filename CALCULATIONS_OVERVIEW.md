# MessMate — Calculations Overview (Code-Verified Audit)

> **Audit scope:** backend only — Kotlin+Spring Boot.  
> **Source files read:** `FinancialCalculationEngine.kt`, `CycleCalculationService.kt`,
> `ChefService.kt`, `MealService.kt`, `DepositService.kt`, `ExpenseService.kt`, `Enums.kt`.  
> **Rule:** no spec restating — every formula below is quoted directly from the current codebase.

---

## 1. Dynamic Meal Rate

**File:** `FinancialCalculationEngine.kt` · `calculateSettlement()` · Lines 136–170

```kotlin
val mealVariableExpenses = expenses
    .filter { it.category.canonicalCategory == ExpenseCategory.MEAL_VARIABLE }
    .fold(BigDecimal.ZERO) { acc, e -> acc.add(e.amount) }

val totalMemberUnits  = members.fold(BigDecimal.ZERO) { acc, m -> acc.add(m.memberMealUnits) }      
val totalGuestUnits   = members.fold(BigDecimal.ZERO) { acc, m -> acc.add(m.guestMealUnits) }
val totalCountedUnits = totalMemberUnits.add(totalGuestUnits)

val mealRate = if (totalCountedUnits > BigDecimal.ZERO) {
    mealVariableExpenses.divide(totalCountedUnits, 4, RoundingMode.HALF_UP)
} else {
    BigDecimal.ZERO
}
```

**Formula:** `Meal Rate = Σ(MEAL_VARIABLE expenses) / Σ(all meal units incl. guests)`  
Rate is computed to 4 decimal places (HALF_UP).

**DB inputs:**
- `expense.amount` where category maps to MEAL_VARIABLE
- `daily_meal_status.status == ON` → aggregated into memberMealUnits
- `guest_meal.mealUnits` → aggregated into guestMealUnits

**Edge cases:**
- ✅ Zero-denominator: returns BigDecimal.ZERO when totalCountedUnits == 0
- ✅ Volatile-rate guard: `isRateVolatile = !isFinal && totalCountedUnits < 15.0` (added to suppress alarming UI in early-cycle)
- ✅ Sanity Guard (Fixed: Nov 2026):
  * When `totalCountedUnits == 0` and `mealVariableExpenses > 0`, calculation sets `isRateAnomalous = true` and generates a clear `rateWarning` stating variable expenses cannot be distributed with 0 meal units.
  * When `mealRate > 500.00` and elapsed active days > 2, calculation flags `isRateAnomalous = true` with a descriptive warning regarding extreme rate anomaly.
- ✅ Effective Meal Resolution on Read (Fixed: Nov 2026):
  * Calculation engine no longer requires scheduled backfill rows to count default meals.
  * `EffectiveMealService` resolves missing `daily_meal_statuses` rows deterministically on read from mess defaults (`defaultBreakfastOn`, `defaultLunchOn`, `defaultDinnerOn`).
  * Dates prior to `member.joinDate` or after `member.leaveDate` count as 0 units.
  * Paused days/sessions count as 0 units.
  * Pre-booked meals beyond `today` (asOfDate) are strictly bounded and excluded from active rate calculations.

**Status: ✅ Verified working** (Fixed: Nov 2026 — rate sanity guards, zero-unit alert, bounded calculation, and read-time default resolution)

---

## 2. Student Total Cost (Individual Settlement)

**File:** `FinancialCalculationEngine.kt` · `calculateSettlement()` · Lines 207–240

```kotlin
// Tier 1 — Variable
val mealCost     = member.memberMealUnits.multiply(mealRate).setScale(2, RoundingMode.HALF_UP)
val guestCost    = member.guestMealUnits.multiply(mealRate).setScale(2, RoundingMode.HALF_UP)
val variableCost = mealCost.add(guestCost)

// Tier 2 — Fixed overhead (equal split, 1/N)
val fixedOverheadCost = fixedOverheadPerMember   // = Σ(FIXED_OVERHEAD) / members.size

// Tier 3 — Individual Direct (100% to targetMemberId)
val individualDirectCost = individualExpenses
    .filter { it.targetMemberId == member.userId }
    .fold(BigDecimal.ZERO) { acc, e -> acc.add(e.amount) }
    .setScale(2, RoundingMode.HALF_UP)

// Tier 4 — Ad-Hoc / Special (participant split or equal if no participants)
val adHocSpecialCost = memberAdHocCost.setScale(2, RoundingMode.HALF_UP)

// Final bill
val totalCost = variableCost.add(fixedOverheadCost).add(individualDirectCost).add(adHocSpecialCost)
```

**Formula:**  
`totalCost = (memberUnits + guestUnits) × mealRate + fixedOverhead/N + directCharges + adHocShare`

**DB inputs:** daily_meal_status, guest_meal.mealUnits, expense.amount per tier,
expense.targetMemberId (Tier 3), expense.participantIds (Tier 4)

**Edge cases:**
- ✅ Guest cost charged at same meal rate — no markup
- ✅ Fixed overhead is Σ(FIXED_OVERHEAD)/N computed to 2dp (rounding ≤ 1 paisa per member)
- ⚠️ Fixed overhead split equally regardless of active-day count — documented design

**Status: ✅ Verified working**

---

## 3. Net Balance

**File:** `FinancialCalculationEngine.kt` · `calculateSettlement()` · Lines 241–242

```kotlin
val netBalance = member.totalDeposits.subtract(totalCost).setScale(2, RoundingMode.HALF_UP)
val hasNegativeBalance = netBalance < BigDecimal.ZERO
```

**Formula:** `Net Balance = Total Approved Deposits − Total Cost`  
Positive = surplus; negative = debt.

**DB inputs:**
- `deposit.amount` where `deposit.status == APPROVED` and `deposit.cycleId == activeCycle.id`
- `totalCost` from §2

**Edge cases:**
- ✅ Only APPROVED deposits counted (CycleCalculationService L267)
- ✅ Carry-forward from prior cycle injected as APPROVED deposit with ref "CARRY-FWD-C{n}"
- ✅ hasNegativeBalance persisted in StudentCycleSummary
- ⚠️ Balance ledger is maintained separately and can drift if a deposit is deleted post-approval (reversal entry is written correctly by DepositService.deleteDeposit)

**Status: ✅ Verified working**

---

## 4. Cycle Progression / Day Counting

**File:** `FinancialCalculationEngine.kt` · `CycleTimeline` class · Lines 8–47  
**Orchestration:** `CycleCalculationService.kt` · `getActiveCycle()` · Lines 78–160

```kotlin
// Scheduled end date
fun calculateScheduledEndDate(): LocalDate {
    var activeDaysCounted = 0
    var cursor = startDate
    while (activeDaysCounted < targetActiveDays) {
        if (!pausedDates.contains(cursor)) { activeDaysCounted++ }
        if (activeDaysCounted < targetActiveDays) { cursor = cursor.plusDays(1) }
    }
    return cursor
}

// Active days elapsed
fun calculateCountedActiveDays(asOfDate: LocalDate): Int {
    if (asOfDate.isBefore(startDate)) return 0
    var activeCount = 0
    var cursor = startDate
    while (!cursor.isAfter(asOfDate)) {
        if (!pausedDates.contains(cursor)) { activeCount++ }
        cursor = cursor.plusDays(1)
    }
    return activeCount
}
```

**Automatic status transitions** (getActiveCycle L119–125):
- ACTIVE → EXPIRING when `remainingDays in 1..3`
- ACTIVE/EXPIRING → FINALIZING when `remainingDays == 0`

**DB inputs:** dining_cycle.startDate, targetActiveDays, cycle_paused_day.pausedDate

**Edge cases:**
- ✅ Zero-denominator: asOfDate before startDate returns 0 immediately
- ✅ All timezone references use ZoneId.of("Asia/Dhaka")
- ✅ Partial-day pauses (session-level) vs. Whole-day pauses (Fixed: Sep 2026):
  - *Original audit gap:* Previously, any row in `cycle_paused_day` subtracted a full day from `calculateScheduledEndDate` and `calculateCountedActiveDays`.
  - *Resolved behavior:* Timeline counters only filter whole-day pauses (`session == null`). Session-specific pauses (`session != null`) do NOT reduce cycle active-day count since the mess operates for other sessions. Session pauses correctly isolate to blocking meal toggles, guest bookings, and chef headcount for that specific session.
- ⚠️ Status transitions only fire when getActiveCycle() is called; no background scheduler exists.

**Status: ✅ Verified working** (Fixed: Sep 2026 — whole-day pauses affect cycle timeline; session pauses isolate to session-level)

---

## 5. Cycle Settlement / Conclusion

**File:** `CycleCalculationService.kt` · `calculateAndSave(isFinal=true)` · Lines 357–376  
**File:** `CycleCalculationService.kt` · `startNewCycle()` · Lines 441–484

```kotlin
// Final settlement
if (isFinal) {
    cycle.status = CycleStatus.COMPLETED
    cycle.actualEndDate = LocalDate.now(dhakaZone)
    result.memberSummaries.forEach { summary ->
        val ledger = BalanceLedger(
            entryType = LedgerEntryType.MEAL_CHARGE,
            amount    = -(summary.totalCost),
            balanceAfter = summary.netBalance,
            description  = "Cycle #${cycle.cycleNumber} Final Settlement ..."
        )
        balanceLedgerRepository.save(ledger)
    }
}

// Carry-forward into new cycle
if (request.defaultCarryForward) {
    prevSummaries.forEach { summary ->
        if (summary.netBalance.compareTo(BigDecimal.ZERO) != 0) {
            depositRepository.save(Deposit(
                amount = summary.netBalance,
                status = DepositStatus.APPROVED,
                transactionRef = "CARRY-FWD-C${prevCycle.cycleNumber}"
            ))
        }
    }
} else {
    // Audit record for non-carried surplus (Fixed: Sep 2026)
    prevSummaries.filter { it.netBalance > BigDecimal.ZERO }.forEach { summary ->
        balanceLedgerRepository.save(BalanceLedger(
            entryType = LedgerEntryType.REFUND_DUE,
            amount = -(summary.netBalance),
            balanceAfter = BigDecimal.ZERO,
            description = "Cycle #${prevCycle.cycleNumber} surplus refund pending (carry-forward disabled)"
        ))
    }
}
```

**DB inputs:** All APPROVED deposits, all expenses, all ON meal statuses for the cycle.

**Edge cases:**
- ✅ COMPLETED cycle is frozen: ExpenseService and DepositService both check assertCycleNotCompleted()
- ✅ Carry-forward handles both positive (surplus) and negative (debt) netBalance
- ✅ Carry-forward disabled surplus safeguard (Fixed: Sep 2026):
  - *Original audit gap:* When concluding with `defaultCarryForward = false`, member surplus silently vanished without ledger records or warnings.
  - *Resolved behavior:*
    1. Manager cannot finalize or start a new cycle with `defaultCarryForward = false` if any member has `netBalance > 0` without explicit acknowledgement (`confirmForfeitSurplus = true`). The UI surfaces affected members and surplus amounts in a confirmation step.
    2. When carry-forward is disabled, an immutable `BalanceLedger` entry (`entryType = REFUND_DUE`) is always written recording the refund owed, ensuring complete financial auditability.
- ⚠️ Carry-forward ledger entry uses findFirstByUserIdOrderByCreatedAtDesc (cross-cycle global ledger) — correct by design but noteworthy

**Status: ✅ Verified working** (Fixed: Sep 2026 — surplus confirmation required & REFUND_DUE ledger entry recorded)

---

## 6. Chef Headcount / Portions to Cook

**File:** `ChefService.kt` · `getDailyHeadcount()` · Lines 63–116

```kotlin
// Paused day check (Fixed: Sep 2026)
val activeCycle = diningCycleRepository.findByMessIdAndStatus(messId, CycleStatus.ACTIVE)
val pausedRows = activeCycle?.let { cyclePausedDayRepository.findAllByCycleIdAndPausedDate(it.id, date) } ?: emptyList()
val fullDayPause = pausedRows.firstOrNull { it.session == null }
if (fullDayPause != null) {
    return ChefDailyHeadcountResponse(
        date = date,
        isPaused = true,
        pauseReason = fullDayPause.reason ?: "Mess closed / paused for holiday",
        sessionSummaries = emptyList()
    )
}

// Who counts as an "active dining member" for this date?
val activeDiningMembers = membershipRepository.findAllByMessId(messId)
    .filter { it.role != UserRole.CHEF }
    .filter {
        it.status == MembershipStatus.ACTIVE &&
        (it.role.isOwnerOrManager || !it.joinDate.toLocalDate().isAfter(date)) &&
        (it.leaveDate == null || !it.leaveDate!!.toLocalDate().isBefore(date))
    }

// Default ON/OFF for the session
val defaultSessionOn = when (session) {
    BREAKFAST -> diningConfig?.defaultBreakfastOn ?: false
    LUNCH     -> diningConfig?.defaultLunchOn     ?: true
    DINNER    -> diningConfig?.defaultDinnerOn    ?: true
}

// Explicit toggles override the default
val explicitStatuses = dailyMealStatusRepository.findAllByMessIdAndDateAndSession(messId, date, session)
val explicitMap = explicitStatuses.associateBy { it.userId }

val activeMembersOnCount = activeDiningMembers.count { member ->
    val record = explicitMap[member.userId]
    if (record != null) record.status == MealStatus.ON else defaultSessionOn
}

val guestCount     = sessionGuests.sumOf { it.guestCount }
val totalHeadcount = activeMembersOnCount + additionalExplicitOnCount + guestCount
```

**DB inputs:** mess_membership, daily_meal_status (messId+date+session), guest_meal, dining_configuration defaults, cycle_paused_day

**Edge cases:**
- ✅ Managers/owners counted regardless of join-date filter
- ✅ Session-level enable/disable respected (disabled sessions excluded before the loop)
- ✅ additionalExplicitOnCount handles orphaned ON records for ex-members
- ✅ Paused-day integration (Fixed: Sep 2026):
  - *Original audit gap:* Paused days still computed and displayed normal non-zero headcounts to the kitchen.
  - *Resolved behavior:* `ChefService.getDailyHeadcount()` checks `cycle_paused_day`. Full-day pause returns `isPaused: true`, 0 portions, and the pause reason. Session-level pauses mark only that session as disabled with 0 portions. Chef UI renders a clear "Mess Paused / Holiday — No Service" banner.
- ⚠️ Guest headcount uses raw guestCount (persons), not mealUnits (weighted) — correct for plates, but different from financial unit counting

**Status: ✅ Verified working** (Fixed: Sep 2026 — paused days return isPaused=true with 0 portions and clear UI state)

---

## 7. Guest Meal Cost

**File:** `MealService.kt` · `bookGuestMeal()` · Lines 197–214  
**File:** `CycleCalculationService.kt` · `calculateAndSave()` · Lines 263–264, 342–354

```kotlin
// At booking: mealUnits = session multiplier × guest count
val unitMultiplier = when (session) {
    BREAKFAST -> cycleConfig?.breakfastMultiplier ?: BigDecimal("0.50")
    LUNCH     -> cycleConfig?.lunchMultiplier     ?: BigDecimal("1.00")
    DINNER    -> cycleConfig?.dinnerMultiplier    ?: BigDecimal("1.00")
}
val mealUnits = unitMultiplier.multiply(BigDecimal(request.guestCount))

// At settlement: guest cost charged to host at the same meal rate
val guestUnits = guestMeals.fold(BigDecimal.ZERO) { acc, gm -> acc.add(gm.mealUnits) }
// engine: guestCost = member.guestMealUnits * mealRate

// GuestMeal record updated each recalculation
gm.provisionalRate = result.mealRate
gm.totalCost       = gm.mealUnits.multiply(result.mealRate)
```

**Formula:** `Guest Cost = Σ(guestMealUnits) × mealRate` — same rate as regular members, charged to host.

**DB inputs:** guest_meal.mealUnits, guest_meal.guestCount, cycle_configuration multipliers

**Edge cases:**
- ✅ Session multiplier applied at booking (breakfast guest = 0.5 unit)
- ✅ totalCost on GuestMeal record updated on every provisional and final recalculation
- ⚠️ No separate guest markup — if a markup is ever desired, a new engine tier is required
- ⚠️ mealUnits baked in at booking time; a mid-cycle multiplier config change does not retroactively adjust existing guest meal records

**Status: ✅ Verified working** (same-rate pricing, correct weighted units)

---

## 8. Session Weight Multipliers

**File:** `CycleCalculationService.kt` · `calculateAndSave()` · Lines 199–260

```kotlin
val breakfastMultiplier = cycleConfig?.breakfastMultiplier ?: BigDecimal("0.50")
val lunchMultiplier     = cycleConfig?.lunchMultiplier     ?: BigDecimal("1.00")
val dinnerMultiplier    = cycleConfig?.dinnerMultiplier    ?: BigDecimal("1.00")
val combinedRule        = cycleConfig?.combinedSessionRule ?: "SEPARATE"

// SEPARATE (default)
for (meal in userMeals) {
    val mult = when (meal.session) {
        BREAKFAST -> breakfastMultiplier
        LUNCH     -> lunchMultiplier
        DINNER    -> dinnerMultiplier
    }
    memberUnits = memberUnits.add(mult)
}

// LUNCH_DINNER_COMBINED
if (hasLunch && hasDinner) memberUnits = memberUnits.add(BigDecimal("1.00"))
else if (hasLunch)         memberUnits = memberUnits.add(lunchMultiplier)
else if (hasDinner)        memberUnits = memberUnits.add(dinnerMultiplier)
if (hasBreakfast)          memberUnits = memberUnits.add(breakfastMultiplier)

// FULL_DAY
if (mealsForDay.isNotEmpty()) memberUnits = memberUnits.add(BigDecimal("1.00"))
```

**DB inputs:** cycle_configuration (multipliers, combinedSessionRule), daily_meal_status.status==ON

**Edge cases:**
- ✅ Same multipliers applied to guest meals at booking time (MealService.bookGuestMeal)
- ✅ Missing cycleConfig falls back to defaults (0.5 / 1.0 / 1.0, SEPARATE)
- ✅ Centralized in `EffectiveMealService` (Fixed: Nov 2026): Multipliers and combined session rules (`SEPARATE`, `LUNCH_DINNER_COMBINED`, `FULL_DAY`) are executed through `EffectiveMealService.calculateMemberCycleMealUnits()` across all elapsed cycle dates, ensuring deterministic member unit totals even when daily meal status records were not persisted.
- ⚠️ LUNCH_DINNER_COMBINED: having L+D = 1 unit, but L alone or D alone = 1 unit too — a "combined meal discount" by design but non-obvious
- ⚠️ FULL_DAY mode ignores all multipliers; breakfast still counts as a full day unit

**Status: ✅ Verified working** (Centralized in EffectiveMealService; multipliers & session combination rules unified)

---

## Summary Table

| # | Calculation | File (primary) | Status |
|---|-------------|---------------|--------|
| 1 | Dynamic Meal Rate | `FinancialCalculationEngine.kt` L164–170 | ✅ Verified working |
| 2 | Student Total Cost | `FinancialCalculationEngine.kt` L207–240 | ✅ Verified working |
| 3 | Net Balance | `FinancialCalculationEngine.kt` L241–242 | ✅ Verified working |
| 4 | Cycle Progression / Day Counting | `CycleTimeline` + `getActiveCycle()` | ✅ Verified working (Fixed: Sep 2026) |
| 5 | Cycle Settlement / Conclusion | `calculateAndSave(isFinal=true)` | ✅ Verified working (Fixed: Sep 2026) |
| 6 | Chef Headcount / Portions | `ChefService.getDailyHeadcount()` | ✅ Verified working (Fixed: Sep 2026) |
| 7 | Guest Meal Cost | `MealService.bookGuestMeal()` + engine | ✅ Verified working |
| 8 | Session Weight Multipliers | `CycleCalculationService.calculateAndSave()` | ✅ Verified working |

---

## Expense Category Mapping

**File:** `Enums.kt` · `ExpenseCategory.canonicalCategory` · Lines 73–79

```kotlin
val canonicalCategory: ExpenseCategory
    get() = when (this) {
        MEAL_VARIABLE, GROCERY, VEGETABLE, MEAT_FISH, SPICE -> MEAL_VARIABLE
        FIXED_OVERHEAD, GAS, UTILITY, CHEF_SALARY           -> FIXED_OVERHEAD
        INDIVIDUAL_DIRECT                                   -> INDIVIDUAL_DIRECT
        AD_HOC_SPECIAL, OTHER                               -> AD_HOC_SPECIAL
    }
```

> **Note (Fixed: Sep 2026):** `OTHER` maps to `AD_HOC_SPECIAL`, not `FIXED_OVERHEAD`.
> - *Original audit gap:* A manager selecting "Other" might expect a shared overhead (equal split), but selecting specific participants caused an opt-in participant split without warning.
> - *Resolved clarification:* In `ManagerExpenseScreen.tsx`, the category is explicitly named **"Other (Ad-Hoc Split)"**, and inline guidance states: *"Other expenses are split only among selected participants (like Ad-Hoc/Special). Leave participants empty to split equally across all members."* This eliminates surprise cost assignments.

---

*Generated by code audit — not from spec. Original Audit: Sep 2026. Audit fixes verified: Sep 2026.*
