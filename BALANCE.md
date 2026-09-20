# Balance

Every number that controls pacing on this server comes from one model. This
file is that model: what the ladders are, why they are the shape they are,
and which knob to turn when something feels wrong.

Run `/admin zones reload`, `/admin packs reload`, etc. after editing any of
the files named below — none of this needs a restart.

---

## 1. The combat model

This is the piece everything else is derived from, so it is worth having in
your head before touching any number.

- Every equipped pet swings **once per second** (`ATTACK_INTERVAL_TICKS` =
  20 in `PetCombatController`). Damage varies pet to pet; the cadence never
  does.
- Every hit has a **10% chance to crit for double**, so expected damage is
  `×1.10`.
- In Auto mode, the whole squad shares one target, and switching to a new
  one puts **every** pet on a 1-second cooldown
  (`BASE_AUTO_SWITCH_COOLDOWN_TICKS`).

So the time to kill one cube is:

```
seconds = switchCooldown + (hitsNeeded - 1) × 1.0
hitsNeeded = ceil(cubeHP / squadVolleyDamage)
```

**Two consequences that drive the entire design:**

1. **Kill time has a hard floor of ~1 second**, no matter how much damage
   you have. Past the point where you one-shot a cube, more damage does
   nothing for that cube. This is why damage upgrades have strong
   diminishing returns and why coin multipliers are the most valuable stat
   in the game — coins have no equivalent ceiling.
2. **Cube HP has to be set relative to expected squad damage**, not picked
   independently. If it is too low, every cube dies in one volley, kill time
   pins to the floor, and combat has no texture at all — which is exactly
   what the old numbers did.

The tuned relationship is **tier-1 cube HP ≈ 85% of a settled squad's
volley**: you one-shot the trash, take two swings on the mid tier, and four
on the rare big one.

---

## 2. The zone ladder — `yield-zones/…/zones.yml`

One headline number per zone: its **tier-1 coin value**. Everything else is
a fixed ratio off it.

| | tier 1 | tier 2 | tier 3 |
|---|---|---|---|
| spawn weight | 100 | 25 | 5 |
| max-hp | ×1 | ×3 | ×7 |
| coin-value | ×1 | ×4 | ×12 |
| diamond-value | ×1 | ×3 | ×8 |
| xp-value | ×1 | ×3 | ×8 |

Tier 3 pays **12× for 7× the HP** on purpose. The rare big cube should be
worth stopping for — that is what makes a golden/diamond roll on one exciting
instead of a chore.

The coin ladder itself climbs ~2.6× per zone and is made of round numbers so
the jump is readable at a glance:

```
10 → 25 → 65 → 175 → 450 → 1.2K → 3K → 8K → 20K → 50K → 130K
→ 340K → 875K → 2.25M → 5.8M → 15M → 38M → 100M → 260M → 675M
```

Tier-1 HP is 4× the coin value from Crystal Caverns onward. The first five
zones are deliberately softer than that ratio, because a brand-new player is
still fighting with the single starter pet and the first ten minutes have to
feel good rather than be mathematically correct.

The other two ladders, both derived rather than chosen — the zone's
common-pet damage (solved so a settled squad one-shots that zone's tier-1
cube) and its pack price:

```
pet dmg   1 → 1 → 2 → 4 → 8 → 15 → 30 → 60 → 150 → 300 → 600 → 1.5K
          → 3K → 8K → 20K → 40K → 80K → 200K → 500K → 1.25M
pack      50 → 100 → 300 → 700 → 2K → 9K → 30K → 100K → 450K → 2M
          → 8M → 30M → 100M → 300M → 1B → 3B → 10B → 30B → 100B → 300B
unlock    4K → 10K → 40K → 125K → 600K → 3M → 12.5M → 60M → 300M → 1.2B
          → 5B → 25B → 100B → 400B → 1.5T → 6T → 25T → 100T → 400T
```

Pack prices are the subtle one. They are set so that a stay funds roughly 30
packs in Meadow rising to ~200 in the late zones — enough to rebuild a squad
in a couple of minutes on arrival, not so many that sheer pack volume (and
the auto-fusion it feeds) makes the pet-damage ladder meaningless. Both
failure modes are real and were hit while tuning this: price them high and a
player opens *three* packs in a whole mid-game zone and stalls at 13 seconds
a cube; price them low and they open thousands, fusion carries everything,
and every zone's pets may as well deal 1 damage.

Because packs are cheap relative to income by design, **sneaking while
smacking a pack station buys ten at once** — at one per click the intended
"arrive, buy a stack, watch your damage jump" moment would be several
hundred clicks.

### Treasure chests

Each zone rolls a fourth, much rarer cube tier: a gold-glowing chest at
weight `0.6` against the normal tiers' `100/25/5`, so **1 in 218 cubes —
about one every six minutes**. It takes ~10 seconds to break and pays about
6× the coins per point of HP a normal cube does, plus **8 of that zone's own
packs**.

The packs rather than more coins are the point. Coins are already what the
chest's inflated coin value pays, and a second pile of them would just be a
bigger number; packs are what a player turns into power, so a chest reads as
"your squad just got better".

It is modelled as a cube *tier*, not a separate entity, so targeting, combo,
the damage pipeline and the payout path all work for it unchanged.

**What it costs the curve:** free packs scale with time-in-zone, and the late
zones are long, so the last zone gets ~140% more packs than the budget alone
would buy. Measured end to end that only compresses the whole game by **12%
(14.9 h → 13.1 h)**, because squad damage grows as roughly packs^0.55 and the
1-second kill floor caps what the extra damage can buy. If that is too much,
`reward-pack-amount` in each zone's `treasure:` block is the dial.

### Why a new zone feels like a power spike

You walk into a new zone carrying the **previous** zone's pets, which are
~2.6× too weak. Cubes take about **4 seconds** each. You buy that zone's
packs at its pack station, and settle at about **1.6 seconds** each.

That is a ~2.7× throughput jump, and it happens on **every single zone
entry**. It is the core dopamine loop, and it is automatic: it falls out of
the pet ladder growing at the same rate as the HP ladder, with the pack
station as the thing that closes the gap.

---

## 3. The pacing curve

The unlock ladder is a uniform **~4× per zone**, every step a round number.
Combined with income that grows a little slower than that, the time to earn
each unlock stretches out as you go.

Measured, not assumed — these come from an end-to-end playthrough simulation
that reads these config files and plays a free account from the starter pet
to Genesis Core, spending on packs, rebirths, upgrade stations and the skill
tree along the way:

| reach | at | gap from the last |
|---|---|---|
| Frostpeak | 5 min | 5 min |
| Sulfur Flats | 18 min | 7 min |
| Crystal Caverns | 36 min | 10 min |
| Emerald Depths | 1.0 h | 12 min |
| Netherite Wastes | 1.8 h | 18 min |
| End Barrens | 2.7 h | 30 min |
| Starlight Fields | 5.2 h | 66 min |
| Eternal Forge | 8.0 h | 96 min |
| Genesis Core | 13.1 h | 3.0 h |

**Five zones inside the first half hour**, and a final zone that is a
multi-session goal — the gap between unlocks grows about 40× across the
ladder.

This describes an *engaged, not maxed* player: roughly half of gross income
into the unlock, the rest into packs, rebirths, upgrades and the skill tree.
Someone who lucks into a mythic early, or who optimises hard, will beat it.
That is fine and intended.

---

## 4. What was actually broken before

Worth recording, because several of these were not "tuning" problems.

- **Every zone past Frostpeak was unbuyable.** Unlock costs required turn-in
  items — 32 Packed Ice, 32 Magma Blocks, 32 Ancient Debris, 32 Sea Lanterns
  — and the server has **no source for any of them**. Mining drops raw ores;
  cubes drop coins and diamonds. Nothing produced those blocks. Removed;
  unlocks are coins + a light diamond side-cost.
- **Diamond income was completely flat.** A cube kill paid exactly one
  diamond in Genesis Core, the same as in Meadow, while everything priced in
  diamonds kept climbing. Cube tiers now carry a `diamond-value` that scales
  with the zone. (Rank was the worst offender at the time — `1000 × 1.15^n`,
  putting rank 20 alone past a hundred hours — but Rank has since moved to
  the Star/quest track, so today's diamond sinks are the Enchanting Table,
  the premium packs, and zone unlocks' side-cost.)
- **Cube HP and pet damage were `int`.** A dark-matter-fused secret pet in
  the last zone deals ~1.8×10¹⁰ damage, which silently overflows. The whole
  damage path is `long` now.
- **Every upgrade station was in Meadow, capped at level 10.** Coin Boost and
  Damage Boost were permanently stuck at +10%. There is now a full set at
  every other zone with caps climbing to 100.
- **The skill tree's pet-slot nodes granted one slot each, not one per
  level.** `value-formula: "1"` returns the total at a level, not an
  increment — so "Extra Pet Slot", max level 4, gave exactly one slot. Now
  `"<level>"`.
- **The whole skill tree cost ~2M coins to max**, which is under two minutes
  of income by Diamond Hollow. It now spans the ladder (~1.2T total).
- **The Meadow world boss had 500,000 HP and paid 1,000,000 coins.** A Meadow
  squad deals ~70 damage/second, so it was a two-hour solo fight, and the
  payout was worth more than the first nine zone unlocks combined.
- **Block-tree goals were unreachable.** 2,500,000 Stone breaks, in a zone a
  player passes through in four minutes (~120 Stone).
- **Zone pack prices had no relationship to zone income.** They were derived
  from the income a *settled* squad earns, but you need the packs in order to
  become settled — so in the middle zones a player could afford about three
  packs across an entire stay, and combat degenerated to 13 seconds a cube.

---

## 5. Donor ranks

Balanced **for a free player**; donors were measured afterwards, not designed
around.

Measured pace to the final zone, from the same playthrough simulation:

| | time to Genesis Core | vs free |
|---|---|---|
| free | 13.1 h | 1.0× |
| VIP | 5.9 h | 2.2× |
| Celestial | 1.8 h | 7.3× |

One thing worth knowing, because it is counter-intuitive: **the +20 / +80 pet
slots are not where that advantage comes from.** Once duplicates auto-fuse,
a squad's top few pets are enormously stronger than the rest of the bag —
going from 8 slots to 88 raises total volley by only about 10%, and most of
even that is wasted against a cube you already one-shot. The donor advantage
is almost entirely the **coin multiplier** (1.5× / 3×) and the **premium
auto-switch cooldown**, which attacks the 1-second kill-time floor directly.

That also means the pet-slot numbers are safe to leave alone: they read as
enormous on the store page and are, in practice, a collection perk.

Celestial's 7.1× is the number to look at if any of this feels too strong.
It is 3× coins multiplied by ~2.5× more kills per second, and that second
factor is the **premium auto-switch cooldown** — `PREMIUM_AUTO_SWITCH_MULTIPLIER`
and `PREMIUM_AUTO_SWITCH_FLOOR_TICKS` in `PetCombatController`, which take
the squad retarget from 1.0s down to 0.2s. Because kill time floors out at
that cooldown, it is the single most powerful stat any player can hold.
Raising the floor from 4 ticks to 8 would roughly halve Celestial's edge
without touching anything on the store page.

---

## 6. Not covered by this model

Two systems were rebuilt while this pass was in flight and are **not** part
of the model above. Both carry their own "first-pass judgment call" notes,
and neither was retuned here — changing them belongs with whoever designed
them. For reference against the balanced kill rate (~35-40 cubes a minute):

- **Masteries** (`masteries.yml`). The XP curve is `50 × level^1.3`, and
  Combat earns 1 XP per cube kill. That puts the first perk at level 10
  around 3,800 kills (~1.7 h), the `extra_pet_slot_1` perk at level 30
  around 50,000 kills (~22 h), and level 99 around 848,000 kills (~370 h).
  The early perks land well; the top of the track is currently far beyond
  the ~15 h it takes to finish the zone ladder.
- **Rank Quests / Stars** (`rank-quests.yml`, `RankService`). Rewards are
  Stars only, so this sits outside the coin economy entirely and nothing
  here conflicts with it.

---

## 7. Knobs

Turn these, in roughly this order, when something feels off:

| Symptom | Knob |
|---|---|
| Whole game too fast / too slow | `unlock:` coins in `zones.yml` — scale them all by the same factor |
| Early game drags | Lower the first 4-5 unlock costs only |
| Late game ends too soon | Raise the last 5 unlock costs; they dominate total playtime |
| Cubes die too fast, combat feels flat | Raise `max-hp` relative to `coin-value` (currently 4×) |
| Cubes are spongy | Lower `max-hp`, or raise the zone pack's pet damage |
| No power spike on entering a zone | Lower the zone's pack cost so a new squad comes together faster |
| Chests too frequent / too generous | `weight` and `reward-pack-amount` in each zone's `treasure:` block |
| Rebirth stops mattering | `growth` in `rebirth.yml` (higher = fewer, bigger rebirths) |
| Upgrades get maxed and forgotten | `cost-growth` in `yield-upgrades/upgrades.yml`, and the station `cap` ladder |

**Do not change one zone in isolation.** Each zone's numbers are derived from
its neighbours; a zone that is out of line with the ladder either becomes a
wall or gets skipped.

---

## 8. Re-deriving the numbers

The model is reproducible. Given the combat maths in §1, for each zone:

1. Pick the tier-1 coin value from the round ladder.
2. Expected squad damage = best-N (after auto-fusion) of the packs a player
   opens during that zone's stay, × pet level multiplier × damage multiplier
   × 1.10 for crits.
3. Tier-1 HP = 0.85 × that volley.
4. Solve the zone's common-pet damage backwards from (3).
5. Coins per minute = `2.0 × tier1Coin × coinMultiplier × 1.07 (cube bonus)
   × 1.30 (combo) ÷ avgCubeSeconds × 60`.
6. Unlock cost = that rate × the zone's target minutes × 0.5.

The `2.0` in step 5 is the tier-weighted expected coin payout in units of the
tier-1 value: `(100×1 + 25×4 + 5×12) / 130`.
