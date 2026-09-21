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

## 2b. The merchant ladder — the rotating shop

Two separate channels sell packs, and they answer different questions.

**Zone packs** (`zone_<id>_pack`) are the ladder. One per zone, sold at that
zone's own physical station for a fixed price, unlimited supply, ten pets
scaled to that zone (`×1 / ×1.6 / ×3 / ×6 / ×12 / ×26 / ×100` off the zone's
baseline damage, at weights `100/100/40/40/15/15/4/1/0.25/0.02`). This is
what the pacing model in §3 assumes a player buys, and it is the only pack
channel that matters for time-to-zone.

**Merchant packs** (Starter → Titan) are the rotating shop's ten rungs. Each
draws from a **three-zone window**: commons and uncommons from the window's
first zone, rares and the epic from its middle, legendary/mythic/secret from
its last. So a merchant pack is a pack from roughly two zones ahead, at the
cost of a worse floor. Each rung also carries the **merchant-only pets** —
Stray Cat, Golden Retriever, Cosmic Leviathan, Eternal Sovereign and the
rest, which belong to no zone — parked on the rung whose damage window their
own damage fits.

| rung | zones | cost | mean dmg | ceiling |
|---|---|---|---|---|
| Starter | 1–2 | 25 | 3 | 100 |
| Common | 3–5 | 1,000 | 5 | 800 |
| Uncommon | 5–7 | 15,000 | 16 | 3,000 |
| Value | 7–9 | 150,000 | 63 | 15,000 |
| Rare | 9–11 | 3,000,000 (+100💎) | 304 | 60,000 |
| Jackpot | 11–13 | 40,000,000 (+750💎) | 1,362 | 300,000 |
| Epic | 13–15 | 400,000,000 (+4,000💎) | 7,315 | 2,000,000 |
| Legendary | 15–17 | 4,000,000,000 (+25,000💎) | 40,478 | 8,000,000 |
| Mythic | 17–19 | 40,000,000,000 (+150,000💎) | 188,061 | 50,000,000 |
| Titan | 18–20 | 150,000,000,000 (+400,000💎) | 470,153 | 125,000,000 |

Merchant eggs hatch on the spot like any other - the rotation and the
per-cycle stock are what make the merchant a treat rather than a substitute
for a zone's own station.

Price is **1.5× the window's middle zone's own pack**, and the diamond side
-cost is 15% of the coin price converted at that zone's own coin-per-diamond
income ratio.

The 1.5× was chosen against the metric that actually decides whether a pack
was worth buying: not the mean pull, but **the best pull out of a run of
opens**, since a player equips their best pets and fuses the rest. Measured
as `E[max damage over 50 opens] per coin`, the merchant lands at 0.86–1.26×
the zone pack of its middle zone — parity, paid for with a worse floor, and
bought with the upside of pets from zones you have not unlocked yet. Stock
limits (1–10 per 5-minute cycle, 1 for the top rungs) keep it a treat rather
than a staple.

**What this replaced.** All ten rungs were literally the same pool: the same
twelve pets at the same twelve weights, from the 25-coin Starter Pack to the
20-billion Titan Pack. A Titan Pack was a 24% chance of a 3-damage Stray Cat.
Measured per point of mean damage it cost `1.78×10⁹` coins against the zone
pack's `85,000` at the same moment — about **20,900× worse** — and the
ladder's mean damage never moved off ~6 across four orders of magnitude of
price.

---

## 2c. The hatch loop

Eggs are paid for at the moment they hatch, at a station, with nothing
stored in between. That single change moves where the loop's ceiling comes
from, so it is worth writing down.

**Paying and hatching used to be separate.** A player could buy a hundred
packs in one click and open them at leisure, which meant the open cooldown
(1s by default, `open-cooldown-seconds`) limited only how fast the *reveals*
played, never how fast income turned into pets. Now it limits both.

**The arithmetic that forces bulk tiers.** A zone's egg costs about 1/150th
of a minute's income in that zone (§2), so a settled player earns roughly
**2.5 eggs per second** and a 1/second cooldown would cap them at **1**.
Everything past that first egg per second would be coins piling up with
nothing to spend them on, and the gap widens with every multiplier a player
buys. So:

- The cooldown is charged **once per action**, not once per egg.
- The rungs are 1x / 5x / 10x / 24x, and **everything up to 10x is
  ungated**. Only 24x needs the Multi-Hatch gamepass.
- 10x against a 1s cooldown is 10 eggs/second, well above the ~2.5 a free
  player can afford. The loop stays bound by income, which scales with the
  player, rather than by the clock, which does not.

**Why the ungated cap is 10 and not 5.** Hatching is something you do
standing at a station, so unlike the old buy-in-bulk-and-open-while-mining
model it does not overlap with earning. A minute of mining funds ~150
hatches, and one action per second turns that into time spent at the egg
instead of at the cubes:

| rung | actions per minute mined | time at the egg |
|---|---|---|
| 5x | 30 | 50% of the mining time |
| 10x | 15 | 25% |
| 24x | 6 | 10% |

At a cap of 5 the gamepass was worth roughly a quarter of a player's whole
progression rate - a gate, not a convenience, and the line this server's
monetization otherwise stays on the right side of. At 10 it saves about a
tenth: real, worth buying, and not the difference between keeping up and
falling behind.

**A hatch in flight never blocks the next one.** The reveal runs ~3.5s for a
single egg, so refusing while it plays would have capped the game at one
batch per animation - slower than the cooldown that is supposed to be the
limit. A new hatch takes the screen from the old one instead.

**Auto-hatch** runs only while the player is stood at a station, on the same
cooldown, at the largest rung they can afford. It is the idle loop, and it
is deliberately a *place* now: you pick an egg and stand at it.

**Treasure chests** hatch their eggs on the spot rather than granting a
stockpile - the one hatch that happens away from a station. The pacing
model already counted those eggs as free pets at the moment the chest
opened, so the timings in §3 are unchanged by this.

---

## 2d. The black market

One egg, five pets, priced in **Store credits** — the only thing in the
game bought with a currency you cannot mine.

| pet | chance | damage |
|---|---|---|
| Obsidian Warden | 30% | +400% of your best pet |
| Astral Leviathan | 25% | +500% |
| Eclipse Tyrant | 20% | +600% |
| Genesis Sovereign | 15% | +700% |
| Infinity Wyrm | 10% | +800% |

**They are Huges**, mechanically — damage is a percentage of the owner's
best *normal* pet rather than a number of their own. That is the whole
reason they are safe to sell: they multiply whatever progress a player has
already made instead of handing an early-zone account a late-zone pet.
Someone who buys one in Meadow gets a big multiplier on a Meadow squad,
exactly as a buyer in Genesis Core gets one on theirs. A paying player
climbs their own ladder faster; they do not skip it.

For scale, the best Huge a free player can win in the chase (a Secret) is
+300%, so the black market's floor is above the chase's ceiling and its top
is roughly double it. Decisively the strongest pets in the game, and
deliberately not a different order of magnitude from what the game gives
away.

**Price** is 2,000 credits a hatch — $20 at the Store's 100-credits-to-the-
dollar rate. Achievements and milestones also pay credits (a few thousand
across a whole playthrough), so a free player reaches one eventually and a
paying one has it today. That number is the single dial if it is wrong.

**Placement**: one kiosk every third zone, seven in all, rather than one at
every entrance. Twenty made the rarest shop in the game the most common
building in it.

**What it replaced**: three "elite cache" eggs that sold ordinary zone pets
at a markup on an hourly rotation. Those were a worse version of the zone
station standing next to them — same pets, more money, and a timer telling
you to come back later. A shop that sells what you cannot get anywhere else
is a reason to look; a shop that sells the same thing slower is not.

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
- **The whole merchant ladder was one pool copied ten times.** See §2b —
  every rung from 25 coins to 20 billion offered the same twelve pets at the
  same weights.
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

## 6. The donation goal moves this whole model

Every $100 the server takes permanently adds **+0.1 to a server-wide coin
multiplier**, forever, for everybody (see `DonationGoalService`). That is a
deliberate design choice, but it is also the one thing that will quietly
invalidate the pacing table above, so it is worth stating plainly:

| donated lifetime | permanent coin multiplier | time to Genesis Core |
|---|---|---|
| $0 | 1.0× | 13.1 h |
| $500 | 1.5× | ~9 h |
| $1,000 | 2.0× | ~7 h |
| $2,500 | 3.5× | ~4 h |
| $5,000 | 6.0× | ~2.5 h |

(Coin income scales directly with the multiplier while unlock costs stay
fixed, so time-to-unlock falls roughly in proportion. Those are estimates
from that relationship, not separate simulation runs.)

It is **coins only**, on purpose, and it should stay that way. Coins are the
one stat with no ceiling in the combat model. A permanent unbounded *damage*
multiplier would push every cube past the one-shot threshold and flatten
combat; a permanent *luck* multiplier would inflate Huge and Secret odds
forever and cheapen the chase. A coin multiplier just moves everyone along
the same curve faster, which is what a community goal should do.

If the ladder ever starts feeling too short because of this, the lever is
`MULTIPLIER_PER_GOAL` in `DonationGoalStore` (or raising the late-game
unlock costs), not removing the reward.

---

## 7. Not covered by this model

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

## 8. Knobs

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

## 9. Re-deriving the numbers

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
