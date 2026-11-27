@file:JvmMultifileClass
@file:JvmName("BuildersKt")
package kotlinx.coroutines

// VENDORED SHIM (V1.0.4 trial) — kotlinx.coroutines.BuildersKt.runBlockingK.
//
// PROVEN CAUSE (verify-1.0.4/INVESTIGATION-3-runblocking.md): the host ships
// kotlinx-coroutines 1.8.1 (app/build.gradle.kts:123-124), but the live
// Phisher plugins were compiled against coroutines 1.11.0, in which Kotlin's
// runBlocking carries @JvmName("runBlockingK"). Every provider whose MainAPI
// constructor calls it (Fibwatch, DudeFilms, BanglaPlex, FourKHDHub) throws
// NoSuchMethodError at load time and stays OFF; Cinefreak calls it only in
// an extractor constructor, so it shows ON but degraded.
//
// This file vendors ONLY the missing JVM method, delegating to the host's
// own 1.8.1 runBlocking. 1.11.0's runBlockingK IS 1.8.1's runBlocking under
// a new JVM name — same parameters, same return, same contract (run the
// block, blocking the current thread until it completes) — so the
// delegation is semantics-identical, not an approximation.
//
// PACKAGING (the trial gate): BuildersKt is a multifile facade
// (@file:JvmMultifileClass @file:JvmName("BuildersKt")), so this part file
// emits a second BuildersKt.class alongside the library's own. If D8
// rejects that as a duplicate class, this shape fails and the fallback is
// a build-time ASM step adding the method to the library's BuildersKt.class
// (see INVESTIGATION-3 §5a). A plain Java BuildersKt.java must NEVER be
// used here — it would contain only runBlockingK, and if it won the
// duplicate race every other BuildersKt method (launch/async/runBlocking)
// would vanish.
//
// The Kotlin name is deliberately runBlockingKShim (not runBlocking): the
// body calls the library's runBlocking by its Kotlin name, and naming this
// function runBlocking would make that call recurse into itself. Only the
// JVM name is runBlockingK, which is exactly what the plugin bytecode
// invokes: BuildersKt.runBlockingK(CoroutineContext, Function2)Object.
//
// REMOVE this file if kotlinx-coroutines is ever upgraded to 1.11.0+ —
// the real runBlockingK will then be present and this copy would conflict
// with it. (Same rule as SpillingKt.java for stdlib 2.1+.)

import kotlin.coroutines.CoroutineContext

/**
 * JVM method: kotlinx.coroutines.BuildersKt.runBlockingK(
 *   Lkotlin/coroutines/CoroutineContext;
 *   Lkotlin/jvm/functions/Function2;
 * )Ljava/lang/Object;
 */
@JvmName("runBlockingK")
public fun <T> runBlockingKShim(
    context: CoroutineContext,
    block: suspend CoroutineScope.() -> T,
): T = runBlocking(context, block)
