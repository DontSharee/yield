version = "0.1.0"

dependencies {
    compileOnly(project(":yield-core"))
    compileOnly(project(":yield-packs"))
    // Needed at compile time for the raw MongoCollection/Document types the
    // stores use directly - the real driver classes ship inside yield-core's
    // own shaded jar at runtime.
    compileOnly("org.mongodb:mongodb-driver-sync:5.10.0")
}
