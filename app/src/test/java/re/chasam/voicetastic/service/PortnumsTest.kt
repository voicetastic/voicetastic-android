package re.chasam.voicetastic.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty

class PortnumsTest : FunSpec({

    test("TEXT_MESSAGE_APP port number is 1") {
        Portnums.TEXT_MESSAGE_APP shouldBe 1
    }

    test("PRIVATE_APP port number is 256") {
        Portnums.PRIVATE_APP shouldBe 256
    }

    test("ADMIN_APP port number is 6") {
        Portnums.ADMIN_APP shouldBe 6
    }

    test("all port numbers are distinct") {
        val ports = listOf(
            Portnums.TEXT_MESSAGE_APP,
            Portnums.POSITION_APP,
            Portnums.NODEINFO_APP,
            Portnums.ADMIN_APP,
            Portnums.PRIVATE_APP
        )
        ports.distinct().size shouldBe ports.size
    }
})

