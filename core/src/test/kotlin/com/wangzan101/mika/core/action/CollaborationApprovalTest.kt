package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.run.*
import org.junit.Assert.*
import org.junit.Test

class CollaborationApprovalTest {
    @Test fun grantingPhoneToolsNeverGrantsSendingDeletingOrderingOrUnknownActions() {
        val collaboration = RunProvenance(RunOrigin.Mcp, Attendance.Present, strictOutward = true)
        for (risk in listOf(Risk(ActionRisk.External), Risk(ActionRisk.Irreversible), Risk(ActionRisk.Unknown), Risk(ActionRisk.Irreversible, payment = true))) {
            assertEquals(Approval.Deny, ApprovalPolicy.decide(risk, collaboration))
        }
        assertEquals(Approval.Allow, ApprovalPolicy.decide(Risk(ActionRisk.Read), collaboration))
        assertEquals(Approval.Allow, ApprovalPolicy.decide(Risk(ActionRisk.Change), collaboration))
        assertEquals(Approval.Allow, ApprovalPolicy.decide(Risk(ActionRisk.External), RunProvenance()))
    }
}
