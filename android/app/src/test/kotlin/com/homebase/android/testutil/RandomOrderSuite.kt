package com.homebase.android.testutil

import java.io.File
import java.util.Random
import org.junit.runner.RunWith
import org.junit.runners.Suite
import org.junit.runners.model.RunnerBuilder

/**
 * A JUnit4 suite that discovers **every** `*Test` class in this module and runs them all in **one
 * JVM** in a **randomised class order** (seed logged to stdout). It exists purely to surface
 * order-dependent cross-test pollution — the failure mode behind PR #359 / issue #363, where one
 * test leaking a fire-and-forget coroutine only broke a *later*, unrelated test. The normal
 * `testDebugUnitTest` run keeps its stable, deterministic order; this suite is wired to a **separate**
 * Gradle task (`testRandomOrderUnitTest`) and CI job so it never makes the default suite flaky.
 *
 * ### How the class list is built (zero extra dependencies)
 * Gradle passes the compiled unit-test output directory as the `homebase.testClassesDir` system
 * property (see `app/build.gradle.kts`). [discoverTestClasses] walks it for `*Test.class` files,
 * turns each into an FQCN and loads it. We deliberately scan the **compiled .class files** rather
 * than pull in a classpath-scanning library (ClassGraph/Reflections/cpsuite) — issue #363 called for
 * no heavy tooling, and the test output dir is a stable, self-describing source of truth.
 *
 * ### What is included
 * Every top-level class whose simple name ends in `Test` (the project's test-class convention).
 * Inner/synthetic (`$`) classes and the suite itself are excluded; non-`Test` helpers are skipped
 * automatically (they don't match the suffix).
 *
 * ### Reproducing a failure
 * Each run prints `RandomOrderSuite: ... replay with -Dhomebase.testOrderSeed=<n>`. Re-run pinned
 * with that seed to replay the exact order.
 */
@RunWith(RandomOrderRunner::class)
class RandomOrderSuite

private const val SUITE_FQCN = "com.homebase.android.testutil.RandomOrderSuite"

class RandomOrderRunner(klass: Class<*>, builder: RunnerBuilder) :
    Suite(builder, klass, discoverTestClasses())

/**
 * Walk the compiled unit-test output directory for top-level `*Test` classes, load them, and return
 * them in a seeded-random order. The seed comes from `-Dhomebase.testOrderSeed` when set (so a CI
 * failure can be replayed deterministically), otherwise from the clock. The chosen order + replay
 * seed are logged so a randomised-run failure is reproducible.
 */
private fun discoverTestClasses(): Array<Class<*>> {
    val dirProp = System.getProperty("homebase.testClassesDir")
        ?: error(
            "homebase.testClassesDir system property is unset — RandomOrderSuite must be run via the " +
                "testRandomOrderUnitTest Gradle task, which points it at the compiled test classes.",
        )
    // The task may hand over several class roots (Kotlin + Java output), joined by the path separator.
    val roots = dirProp.split(File.pathSeparatorChar).map(::File).filter { it.isDirectory }
    require(roots.isNotEmpty()) {
        "homebase.testClassesDir pointed at no existing directory: $dirProp"
    }

    val fqcns = roots.asSequence()
        .flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
        }
        // Top-level test classes only: must end in "Test", no inner/synthetic ($) classes, never the
        // suite itself (would recurse infinitely).
        .filter { it.endsWith("Test") && '$' !in it && it != SUITE_FQCN }
        .distinct()
        .sorted() // deterministic pre-shuffle baseline so a given seed always yields the same order
        .toList()

    val seed = System.getProperty("homebase.testOrderSeed")?.toLongOrNull() ?: System.nanoTime()
    val shuffled = fqcns.shuffled(Random(seed))

    System.out.println(
        "RandomOrderSuite: running ${shuffled.size} test classes in randomised order " +
            "(replay with -Dhomebase.testOrderSeed=$seed): ${shuffled.joinToString()}",
    )

    return shuffled
        .mapNotNull { fqcn ->
            runCatching { Class.forName(fqcn, false, RandomOrderSuite::class.java.classLoader) }
                .getOrNull()
        }
        .toTypedArray()
}
