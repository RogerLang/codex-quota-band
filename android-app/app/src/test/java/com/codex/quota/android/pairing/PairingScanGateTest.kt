package com.codex.quota.android.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingScanGateTest {
  @Test
  fun acceptsOneCodexPairingLinkAndIgnoresOtherQrContent() {
    val gate = PairingScanGate()
    val pairingLink = "codexquota://pair?relay=abc123"

    assertNull(gate.accept("https://example.com"))
    assertEquals(pairingLink, gate.accept(pairingLink))
    assertNull(gate.accept(pairingLink))
  }
}
