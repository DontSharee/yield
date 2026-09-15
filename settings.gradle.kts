plugins {
    // Lets Gradle auto-download JDK toolchains (e.g. JDK 25, required by
    // Paper 26.2) instead of failing when one isn't already installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "yield"

include("yield-core")
project(":yield-core").projectDir = file("plugins/yield-core")

include("yield-packs")
project(":yield-packs").projectDir = file("plugins/yield-packs")

include("yield-rebirth")
project(":yield-rebirth").projectDir = file("plugins/yield-rebirth")

include("yield-zones")
project(":yield-zones").projectDir = file("plugins/yield-zones")

include("yield-quests")
project(":yield-quests").projectDir = file("plugins/yield-quests")

include("yield-skilltree")
project(":yield-skilltree").projectDir = file("plugins/yield-skilltree")

include("yield-leaderboards")
project(":yield-leaderboards").projectDir = file("plugins/yield-leaderboards")

include("yield-cosmetics")
project(":yield-cosmetics").projectDir = file("plugins/yield-cosmetics")

include("yield-tutorial")
project(":yield-tutorial").projectDir = file("plugins/yield-tutorial")

include("yield-teams")
project(":yield-teams").projectDir = file("plugins/yield-teams")

include("yield-broadcasts")
project(":yield-broadcasts").projectDir = file("plugins/yield-broadcasts")

include("yield-achievements")
project(":yield-achievements").projectDir = file("plugins/yield-achievements")

include("yield-blocktree")
project(":yield-blocktree").projectDir = file("plugins/yield-blocktree")

include("yield-leveling")
project(":yield-leveling").projectDir = file("plugins/yield-leveling")

include("yield-lootboxes")
project(":yield-lootboxes").projectDir = file("plugins/yield-lootboxes")

include("yield-auctionhouse")
project(":yield-auctionhouse").projectDir = file("plugins/yield-auctionhouse")

include("yield-upgrades")
project(":yield-upgrades").projectDir = file("plugins/yield-upgrades")

include("yield-packstations")
project(":yield-packstations").projectDir = file("plugins/yield-packstations")

include("yield-zonemachines")
project(":yield-zonemachines").projectDir = file("plugins/yield-zonemachines")

include("yield-mining")
project(":yield-mining").projectDir = file("plugins/yield-mining")

include("yield-ranks")
project(":yield-ranks").projectDir = file("plugins/yield-ranks")

include("yield-spawnnpcs")
project(":yield-spawnnpcs").projectDir = file("plugins/yield-spawnnpcs")

include("yield-trade")
project(":yield-trade").projectDir = file("plugins/yield-trade")
