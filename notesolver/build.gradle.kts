plugins {
  id("org.openstreetmap.josm").version("0.8.2")
}

repositories {
  mavenCentral()
}

java {
  // Build with a modern JDK; emit bytecode for Java 17 (JOSM supports 11+, 17 is a safe LTS floor).
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}

tasks.withType<JavaCompile>().configureEach {
  options.release.set(17)
}

dependencies {
  // Maven Central release 5.14.4 (2025-12 area); BOM keeps platform jars aligned with Gradle's launcher.
  testImplementation(platform("org.junit:junit-bom:5.14.4"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
  useJUnitPlatform()
}

josm {
  pluginName = "notesolver"
  josmCompileVersion = "19649"
  manifest {
    canLoadAtRuntime = true
    description = "Used for closing notes when uploading a changeset and referencing them in each other's comments to keep references."
    mainClass = "org.openstreetmap.josm.plugins.notesolver.NoteSolverPlugin"
    minJosmVersion = "16538"
    author = "Kai Michael Poppe"
    website = uri("https://wiki.openstreetmap.org/wiki/User:Kmpoppe/Plugins#noteSolver").toURL()
  }
}
