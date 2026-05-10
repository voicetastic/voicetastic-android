package re.chasam.voicetastic.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Parity test with `voicetastic-core/src/ids.rs` (Rust).
 *
 * Each Rust test below has a mirrored Kotlin assertion so the two sides
 * stay wire-compatible on the `!aabbccdd` node-id format.
 */
class NodeIdsTest : FunSpec({

    // Rust: `round_trip`
    test("round-trips every interesting node number") {
        for (n in intArrayOf(0, 1, 0xa1b2c3d4.toInt(), 0xffffffff.toInt())) {
            val s = NodeIds.nodeNumToId(n)
            NodeIds.nodeIdToNum(s) shouldBe n
        }
    }

    // Rust: `format_is_lowercase_hex_8`
    test("formats as lowercase 8-hex-digit id") {
        NodeIds.nodeNumToId(0xA1B2C3D4.toInt()) shouldBe "!a1b2c3d4"
        NodeIds.nodeNumToId(0) shouldBe "!00000000"
    }

    // Rust: `rejects_bad_inputs`
    test("rejects malformed ids") {
        NodeIds.nodeIdToNum("a1b2c3d4").shouldBeNull()     // missing '!'
        NodeIds.nodeIdToNum("!a1b2c3d").shouldBeNull()      // 7 hex chars
        NodeIds.nodeIdToNum("!a1b2c3d4e").shouldBeNull()    // 9 hex chars
        NodeIds.nodeIdToNum("!ZZZZZZZZ").shouldBeNull()     // non-hex
        NodeIds.nodeIdToNum("").shouldBeNull()
        NodeIds.nodeIdToNum("!").shouldBeNull()
    }

    test("broadcast address is 0xFFFFFFFF") {
        NodeIds.BROADCAST_ADDR shouldBe 0xFFFFFFFF.toInt()
        NodeIds.nodeNumToId(NodeIds.BROADCAST_ADDR) shouldBe "!ffffffff"
    }
})

