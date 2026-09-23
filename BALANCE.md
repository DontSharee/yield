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

### Giant cubes and the big safe

Two kinds of oversized cube fall in every zone, defined once under
`giant-cubes:` at the top of zones.yml and derived per zone at load, priced
off that zone's own cubes — the same trick Huge pets use — so they track the
ladder with no per-zone numbers to maintain.

| | size | how often | HP | coins | diamonds |
|---|---|---|---|---|---|
| **Giant** (any of the zone's own blocks) | 1.3 | 2.5% of each tier | 3× that tier | 3× | 2× |
| **Big safe** (a vault, green glow) | 1.5 | 0.4% of spawns (1 in 250) | 5× the zone's toughest | 6× | 2× |

Meadow, for scale: stone 12 HP / 10 coins, giant iron 252 / 360, the
treasure chest 120 / 600 plus eggs, the big safe 420 / 720.

**The pecking order is the design.** normal < giant < chest < safe. A giant
keeps its original's pay per hit, so it is a bigger version of the same cube
rather than a better one. The safe pays more coins than anything else in its
zone, twice a giant tier 3, and that is why it is the rare one. The first
draft had it at 3× coins and 0.75%, which paid exactly what a giant tier 3
did for more HP: the rarest thing on screen was the worst deal on it.

**What it costs the curve: about 13% (13.1 h → 11.5 h)**, evenly across the
ladder, every zone arriving 9–13% sooner. Giants are ~4% of that and the safe
~10%. It is a budget, not an accident: spawns are capped per player and a
settled player one-shots most cubes, so any rarer, richer cube is extra income
more than extra time — the same reason tier 3 and bonus cubes are worth
stopping for. Every variation tried that paid enough to feel like a jackpot
cost at least this much; the only cheap safes were ones that paid less than
the chest. If the ladder needs its time back, the safe's `chance` is the dial.

**Diamonds are held at 2×**, well below coins, because they gate ranks and
pet enchants, which this model doesn't cover. A safe paying diamonds at its
coin rate would add roughly half again to diamond income by itself.

**Everything about a cube reads its size**: the fall, the landed body, the
bonus glow, the white look-at outline, the click and highlight hitboxes (one
shared ray-box test in yield-core, so what lights up is exactly what a click
hits), the hit squish, the HP label height, where pets aim, and spawn
spacing (a giant keeps the columns around it clear, so it never lands
overlapping another cube). The invisible barrier underneath stays 1×1. It is
only there for the hover outline and the owner's collision, and the model
hides it, but it does mean a player can step about a quarter-block into a
big safe's edge.

Treasure chests never come giant: they are already their zone's event.

**The boss block** is the 2×2×2 at the very top: REINFORCED_DEEPSLATE, red
glow, about 1 in 3,300 spawns (roughly once an hour of play), a full-screen
title and a wither roar when it lands, 15× the zone's toughest cube in HP
and a **guaranteed Enchant Book** when it dies. The book is the prize; its
coins are held to 10× so it costs the ladder under 1%. Pets ring a big
target wider (`PetDisplayService#ringRadiusFor`), so they stand clear of a
2-block cube instead of inside it. With it, the whole giant budget is
**about 14% (13.1 h → 11.4 h)**.

### Enchant Books and the Enchant Market

Books used to drop from 1 hatch in 20. The pacing model hatches ~1,360 eggs
an hour, so that was **~68 books an hour — one every 53 seconds**, and
every book was junk. They are found now, not farmed:

| source | rate |
|---|---|
| any cube | 1 in 2,500, × luck (`enchant-book-chance` in zones.yml) — ~1.4/h |
| big safe | 1 in 20 |
| boss block | always, and always Epic or better |
| **Enchant Market** | 6 offers per player per hour, each buyable once |

Hatching gives no books at all.

**The market** restocks for everyone at the top of every real hour, but
each player's six offers are their own. They are generated from the
player's UUID and the hour, not stored, so there is no restock job and
nothing goes stale for someone offline at :00. Only what a player bought
this hour is saved. Offer rarity weights are 60 / 50 / 35 / 20 / 6, common
to legendary, so **a Legendary is in about 19% of hours** (checked over a
million simulated player-hours): roughly once every five hours of play.
About one offer in nine is Epic. Anyone online whose market rolled one is told at the restock.
Mythic and above never appear — they stay drop-only.

**Prices are in basic cubes of the richest ladder zone you've unlocked**
(common 100 → legendary 6,000), not in eggs. An hour of income buys about
1,800 eggs in the Meadow but only about 21 near the end, because egg prices
outgrow income. Priced in eggs, a book would cost ~80× less, relative to
earnings, at the start than at the end. A basic cube is what a player
actually earns per kill, so the price keeps pace. "Ladder" (zones with
their own egg) keeps the free Haunted Hollow, whose basic cube pays 15× the
Meadow's, from inflating a new player's prices.

**Found books skew up.** A cube's book rolls 40 / 30 / 18 / 8 / 3 / 0.8 /
0.15 / 0.05 (common → secret), not the egg-pool 100 / 40 / 15 / 4 / 1 it
used to share. 62% commons were fine at 68 books an hour, when nobody read
them. At a few an hour a common is a wasted moment.

**What this does to power: nothing, by design.** At luck 1 over a full run
(11.4 h), books used to give about **19 Epics, 5 Legendaries and 1 Mythic
from ~775 books**. Now it's about **18 Epics, 6 Legendaries and 1 Mythic
from ~104**, if a player buys every Epic and Legendary they're offered.
Same enchant power from a seventh of the books, so each one is worth
reading.

The first cut shipped with the old drop table and a stingier market (100 /
55 / 25 / 9 / 5, no boss floor). That gave only ~4 Epics and ~2
Legendaries a run, a real loss of power that making books rare never
called for. The pacing model doesn't simulate enchants, so none of this
moves the ladder times above.

**No cube lands on you.** Like vanilla never spawning a hostile mob right
next to a player, a cube's column is never chosen within **5 blocks** of its
owner, measured to the nearest edge of the cube, so a boss block keeps the
same gap as a stone cube. A fall lasts over a second and a sprint covers
more than 5 blocks in that time, so where the player stands when it lands
is checked too. If they're inside its footprint, that cube is withdrawn
and a new one is scanned against where they are now. The scan no longer
falls back to a bad column after 30 misses; it skips that spawn, and the
top-up retries four ticks later. Modelled in the smallest zone with a full
load of cubes it never had to (0 in 20,000), averaging 1.1 tries.

**Giants are solid.** The fake barrier under a cube is one block, so on its
own a 1.5 safe let you walk a quarter-block in and a boss block half a
block. Each giant also spawns an invisible, owner-only shulker scaled to
its size. The client treats it as a solid box, and vanilla centres a
shulker on its block and stands it on the floor, exactly where the giant
is, so the collision is precisely the visible cube.

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
| Obsidian Warden | 30% | 250,000 |
| Astral Leviathan | 25% | 1,000,000 |
| Eclipse Tyrant | 20% | 4,000,000 |
| Genesis Sovereign | 15% | 15,000,000 |
| Infinity Wyrm | 10% | 60,000,000 |

They are plain **Exclusives with their own damage**, like every other pet —
the percentage-of-your-best-pet mechanic belongs to Huges and stays theirs
alone.

The ladder is set against what the game gives away. The strongest pet a
free player can win is the Genesis Core Secret at **125,000,000**, and the
top of this list stops below it on purpose: money buys the best pet most
players will ever hold, and a lucky free player can still beat it.

What that means in practice, and it is worth being plain about it: bought
early, these end the game for a while. The cheapest of them is 2,500× the
best pet Meadow can drop and 83× Diamond Hollow's; the Infinity Wyrm
outguns every zone's own best pet until Astral Peak, the 19th of 20. That
is what is being sold. The damage numbers above are the dial if it turns
out to be too much — pulling the top down to the 1–5M range would make
them best-in-slot for the middle of the run rather than for all of it.

**Price** is 2,000 credits a hatch — $20 at the Store's 100-credits-to-the-
dollar rate. That number is the single dial if it is wrong.

### The credit budget

Credits are the Store currency and the only way to buy a gamepass, a rank
or a black market egg, so what the game hands out for free sets what real
money is worth. The whole game now gives away about **1,650** across a full
playthrough:

| source | credits |
|---|---|
| Achievements (10) | 860 |
| Milestones (17 tiers) | 685 |
| Quests | 80 |
| Lootboxes | 50 |

That is one cheaper gamepass *or* most of one black market hatch: enough
for a free player to taste the premium shelf, not enough to clear it.

It used to be **18,985**, against a store that costs **15,073** for one of
everything — every gamepass, both ranks and a black market egg were free to
anyone who simply played for a while, which made the Multi-Hatch pass's
careful balancing in §2c meaningless and the black market's "real money
pets" a misnomer.

**Placement**: one kiosk every third zone, seven in all, rather than one at
every entrance. Twenty made the rarest shop in the game the most common
building in it.

**What it replaced**: three "elite cache" eggs that sold ordinary zone pets
at a markup on an hourly rotation. Those were a worse version of the zone
station standing next to them — same pets, more money, and a timer telling
you to come back later. A shop that sells what you cannot get anywhere else
is a reason to look; a shop that sells the same thing slower is not.

---

## 2e. Seasonal events

A window of dates in which an extra currency drops from cubes and an extra
egg stands at spawn, selling pets that exist nowhere else and leave with it.

Two ship configured: **Halloween** (Oct 20 – Nov 3, Candy Corn) and the
**Winter Festival** (Dec 15 – Jan 5, Snowflakes). Each sells a five-pet
egg:

| rarity | damage | chance |
|---|---|---|
| Rare | 25,000 | 62% |
| Epic | 150,000 | 25% |
| Legendary | 800,000 | 9% |
| Mythic | 4,000,000 | 2.5% |
| Secret | 20,000,000 | 0.6% |

The top of each set lands around what the 18th zone drops — a real prize
for a mid-game player, and still short of both the black market's best
(60M) and the Genesis Core Secret (125M). A fortnight should be worth
showing up for without ending anyone's progression.

**The currency is a flat drop** — 1–3 per cube — and deliberately *not* a
share of the payout. Coins scale by a factor of a million across the zone
ladder, so a proportional drop would make an early player's balance
worthless and a late player's free. A flat rate means an hour of play is
worth an hour of play wherever it is spent, which is what a seasonal
currency has to be if the event is for everyone. Inside the event's own
zone every cube pays; outside it, 8% do.

**Not called "Candy".** The first draft was, and it collided head-on with
Pet Candy (`candy.yml`) — which also drops from cubes and is also spent on
pets. Two unrelated things with one name, falling out of the same block,
is a bug report waiting to be filed. Candy Corn is the same joke without
the collision.

**Earn rate, measured against the real config:** a cube takes about a
second, every cube in the Hollow pays, and the average drop is 2. So the
zone pays ~7,200/hour bare and ~12,600/hour with a full set of event pets.
Over a fortnight that is ~69k at half an hour a day, ~208k at ninety
minutes, ~416k at three hours.

**The egg costs 2,500**, which is 28 hatches for the casual player and 166
for the dedicated one — and the rarest pet is 1 in 160, so the whole set is
exactly the dedicated player's fortnight and nobody else's. This shipped at
400, which paid the *casual* player 173 hatches: the event was over in a
weekend and the chase pet was not a chase.

**Leftover currency is never wiped.** It is worth nothing until next
October, which is the point — nobody has to be told their currency expired,
and a player who grinds the last day has something waiting a year later.

### Halloween, in full

Halloween is the worked example; Winter shares the machinery and can be
fleshed out the same way.

**A zone of its own — the Haunted Hollow.** Not part of the ladder: no
unlock cost, no zone egg, no pack station, no upgrade stations, and cube HP
near the bottom of the ladder so a brand-new player can break them. Its
coins are deliberately poor — about what the third zone pays — because
coming here means giving up your normal income, and what you get for that
is Candy Corn, not a better place to earn. Its chests hand out the event
egg itself, which is the only free source of one.

**It is shut when the event is not running.** The zone stays defined all
year — built, walled, listed in fast travel — but yield-events registers an
access gate on it, so walking in or fast-travelling to it is refused
outside the event window and anyone still standing inside when it ends is
returned to spawn. That is what keeps its cubes, its chests and its egg
from being a year-round alternative income the ladder was never balanced
against: the Hollow's whole trade — poor coins for Candy Corn — only makes
sense while there is Candy Corn to spend.

**Every cube in the zone pays**, 1–3 of it, rather than a lucky few:
a player who has already given up their income should not also be paid at a
trickle. Outside the zone the flat 8% chance still applies, so the event is
visible wherever you are and far faster where it is happening.

**The event's pets boost Candy Corn and nothing else** — +5% to +25% each.
They stack per equipped *pet*, not per kind, so one of each is +75% and six
of the best is +150%. That is what makes them worth equipping *during*
the event rather than a trophy that would have to out-scale a zone pet to
matter. An event is not the place to renegotiate the damage ladder.

**While you stand in the zone, the sidebar's Credits line becomes your
Candy Corn.**
The sidebar has a fixed number of lines, and the number that matters where
the event is happening is not the one you spend at the Store.

**Six quests**, each paying once per season:

| quest | goal | reward |
|---|---|---|
| Trick or Treat | 50 cubes in the Hollow | 1,000 |
| Sweet Tooth | 10,000 Candy Corn | 1,500 + 2× Luck potion |
| Hollow Dweller | 10,000 cubes in the Hollow | 3,000 + 3× Coins potion |
| Pumpkin Hatcher | 10 Pumpkin Eggs | 2,500 + 3× Damage potion |
| Candy Hoarder | 150,000 Candy Corn | 6,000 + **Hollow Reaper** |
| King of the Hollow | 50 Pumpkin Eggs | 15,000 + **Pumpkin King** |

Targets are set against the measured rates above — 10,000 cubes is about
three hours, 150,000 Candy Corn is most of a fortnight's casual play.

The last two hand over the event's two rarest pets outright, which is the
point of a quest board: the egg is luck, the quests are a guarantee for
anyone who actually turns up. Rewards are console commands with
`%player%` substituted, so a quest can pay a potion, a pet, credits or a
rank without this plugin knowing how any of those work — a seasonal reward
table changes every year and should not need code to.

### The shop

The egg answers *spend a little, many times*. The shop answers *spend a
lot, once* — and without it a fortnight of currency had exactly one sink,
which fails at both ends: a player who already has the pets they wanted is
earning something they cannot use, and a player the odds never favoured has
no way to convert effort into the pet they were chasing.

Per-player stock is what stops the shelf collapsing into a second, better
egg. Consumables are unlimited, so leftover currency always has somewhere
to go. Pets are stocked once — or three times, exactly enough to fuse.

| item | price | stock |
|---|---|---|
| Haunted Luck (2× luck, 30m) | 4,000 | ∞ |
| Cursed Fortune (3× coins, 30m) | 5,000 | ∞ |
| Grave Might (3× damage, 30m) | 5,000 | ∞ |
| Witching Hour (2× roll speed, 30m) | 8,000 | ∞ |
| Prowler Pact (**Pumpkin Prowler**) | 12,000 | 3 |
| Warden Pact (**Grave Warden**) | 40,000 | 3 |
| Reaper's Bargain (**Hollow Reaper**) | 120,000 | 1 |

Pet prices are set against what the *egg* costs for the same pet, so a
guarantee always costs a premium over gambling for it — never less:

| pet | odds | expected via egg | on the shelf |
|---|---|---|---|
| Pumpkin Prowler | 1 in 4 | ~10,000 | 12,000 (1.2×) |
| Grave Warden | 1 in 10.7 | ~26,700 | 40,000 (1.5×) |
| Hollow Reaper | 1 in 40 | ~100,000 | 120,000 (1.2×) |
| Pumpkin King | 1 in 160 | ~400,000 | **not sold** |

The King is off the shelf on purpose: a shop that sells the top prize is a
shop that makes the egg pointless. It has its own quest instead.

Clearing the whole limited shelf costs ~276,000 — most, but not all, of a
dedicated player's fortnight. That gap is the decision: guarantee the pets
you want, or keep hatching for the one nobody can buy.

**Everything but the balance restarts each year.** An event's id is
reused — next October is set up by changing its dates — so quest progress,
quest claims and shop stock are keyed by *season* (`halloween_2026`), not by
event. Keyed by id, a returning player would arrive at year two with every
quest already claimed and the pity stock already spent: an event with
nothing in it for the people most likely to come back. Leftover currency
still carries over, as above.

**A Candy Corn leaderboard** ranks what each player has *earned* this
season, not what they hold — a balance falls every time someone hatches or
shops, and would rank whoever spends least rather than whoever played most.
It is a stats.yml entry like every other board; its field names the season,
so the year is the one thing to bump alongside the dates.

Both the charge and the stock count are written and saved *before* the
reward commands run, for the same reason quest claims are: a double-click,
a failed command or a server that dies mid-payout must not leave a
one-per-player pity buy purchasable again.

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

> The pacing model walks the **ladder** zones only — it skips any zone with
> no `zone_<id>_pack`, which is how an event zone like the Haunted Hollow
> stays out of a model that is about progression.

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
