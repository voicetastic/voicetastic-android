package re.chasam.voicetastic.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Locks in the port-number constants against the Rust `ports.rs` source of
 * truth in <https://git.cha-sam.re/acarteron/voicetastic-desktop>.
 * Drifting these breaks wire compatibility between Android and desktop
 * peers, so we keep raw-literal assertions.
 */
class PortsTest : FunSpec({

    test("constants match the Meshtastic wire") {
        Ports.TEXT_MESSAGE_APP shouldBe 1
        Ports.POSITION_APP shouldBe 3
        Ports.NODEINFO_APP shouldBe 4
        Ports.ADMIN_APP shouldBe 6
        Ports.PRIVATE_APP shouldBe 256
    }

    test("MAX_TEXT_BYTES is generous but bounded") {
        Ports.MAX_TEXT_BYTES shouldBe 1024
    }
})

