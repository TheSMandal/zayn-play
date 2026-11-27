// VENDORED (v0.2.10) — kotlin.coroutines.jvm.internal.SpillingKt.
//
// Upstream kotlin-stdlib class, first present in stdlib 2.1.0. The host
// ships stdlib 1.9.24 (frozen toolchain: kotlinc 1.9.24 cannot read
// stdlib 2.1.0+ metadata, so the stdlib cannot be upgraded), but 92 of
// 93 live plugins were compiled against Kotlin 2.4.0 and their
// invokeSuspend state machines call exactly one method on this class:
//   nullOutSpilledVariable(Ljava/lang/Object;)Ljava/lang/Object;
// Its absence breaks every suspend function in those plugins with
// NoClassDefFoundError (proven on-device in v0.2.9: Anichi Content ✗).
//
// The upstream implementation is one static method returning null —
// "called by generated code when visible dead variable is spilled…
// By default, it returns null" — 705 bytes, no fields, no dependencies
// beyond java.lang.Object. Vendored here verbatim so plugin
// classloaders resolve it from the host (same mechanism the other
// restored host classes rely on).
//
// REMOVE this file if the stdlib is ever upgraded to 2.1+ — the real
// class will then be present and this copy would conflict with it.
package kotlin.coroutines.jvm.internal

@PublishedApi
internal fun nullOutSpilledVariable(value: Any?): Any? = null
