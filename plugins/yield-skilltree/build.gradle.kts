version = "0.0.14"

dependencies {
    compileOnly(project(":yield-core"))
    compileOnly(project(":yield-packs"))
    // Lets cost-formula/value-formula stay arbitrary config strings (e.g.
    // "400 * <level>^1.6") instead of a hardcoded cost-curve shape in Java -
    // small, dependency-free, safe to shade into this plugin's own jar
    // (nothing else on the server needs it).
    implementation("net.objecthunter:exp4j:0.4.8")
}
