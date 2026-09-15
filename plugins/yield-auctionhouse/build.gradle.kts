version = "0.0.7"

dependencies {
    // Not transitive from yield-packs' own compileOnly dependency on it -
    // needed directly here since AuctionService/the stores reference
    // yield-core types (DatabaseManager, ClientSession-based transactions,
    // Gui, MenuLore, ItemSerialization) themselves.
    compileOnly(project(":yield-core"))
    compileOnly(project(":yield-packs"))
    // Needed at compile time for the raw MongoCollection/Document/ClientSession
    // types AuctionListingStore/AuctionClaimStore use directly - the real
    // driver classes ship inside yield-core's own shaded jar at runtime.
    compileOnly("org.mongodb:mongodb-driver-sync:5.10.0")
}
