package com.wangzan101.mika.collaboration

import com.wangzan101.mika.core.collaboration.*

/** Deliberately disconnected until an authorized, stable Muse transport is verified. */
class MuseAgentAdapter : AgentAdapter {
    override val descriptor = AgentDescriptor("muse", "Muse", "Muse", "Not configured", false, ConnectionKind.RemoteAgent)
    override suspend fun complete(call: AgentCall): AgentOutput =
        throw IllegalStateException("Muse has no verified authorized transport configured")
}
