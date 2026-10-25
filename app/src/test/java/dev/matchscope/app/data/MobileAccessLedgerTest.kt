package dev.matchscope.app.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MobileAccessLedgerTest {
    @get:Rule val folder = TemporaryFolder()
    private val request = MobileRequest.create(MobileRoute.MATCH,"2399415")
    @Test fun denialSurvivesRestartAndExpiresWithoutExtendingOnReads() {
        val file=File(folder.root,"access.properties"); var now=1000L
        MobileAccessLedger(file) { now }.rejected(request,MobileTransport.HTTPS,MobileAccessException(MobileFailure.BLOCKED,403))
        now+=1000
        val reopened=MobileAccessLedger(file) { now }
        assertEquals(301000L,reopened.active(request,MobileTransport.HTTPS)!!.retryAtMillis)
        assertNull(reopened.active(request,MobileTransport.BROWSER))
        repeat(3) { assertTrue(runCatching { reopened.requireReady(request,MobileTransport.HTTPS) }.isFailure) }
        assertEquals(301000L,reopened.active(request,MobileTransport.HTTPS)!!.retryAtMillis)
        now=301001
        reopened.requireReady(request,MobileTransport.HTTPS)
    }
    @Test fun integrityRequirementCannotBeAvoidedBySwitchingTransportOrEndpoint() {
        val file=File(folder.root,"access.properties")
        MobileAccessLedger(file) { 1000L }.rejected(request,MobileTransport.HTTPS,MobileAccessException(MobileFailure.INTEGRITY_REQUIRED,401))
        val reopened=MobileAccessLedger(file) { 2000L }
        val player=MobileRequest.create(MobileRoute.PLAYER,"19230")
        for (mode in MobileTransport.entries) assertEquals(MobileFailure.INTEGRITY_REQUIRED,reopened.active(player,mode)!!.reason)
    }
    @Test fun rateLimitIsSharedAndBoundedWhileNetworkFailureStaysLocal() {
        val file=File(folder.root,"access.properties"); val ledger=MobileAccessLedger(file) { 1000L }
        ledger.rejected(request,MobileTransport.HTTPS,MobileAccessException(MobileFailure.NETWORK))
        assertNull(ledger.active(MobileRequest.create(MobileRoute.EVENTS),MobileTransport.HTTPS))
        ledger.rejected(request,MobileTransport.HTTPS,MobileAccessException(MobileFailure.RATE_LIMITED,429,Long.MAX_VALUE))
        assertEquals(86401000L,ledger.active(request,MobileTransport.BROWSER)!!.retryAtMillis)
    }
    @Test fun corruptStateDoesNotCrashOrCreateAnUnboundedBlock() {
        val file=File(folder.root,"access.properties").apply { writeText("authorization=FAKE,99999999999999999999999999999") }
        assertNull(MobileAccessLedger(file).latest())
    }
    @Test fun expiredIntegrityAndSuccessfulCloudflareResponsesRemainDistinct() {
        assertEquals(MobileFailure.INTEGRITY_REJECTED,MobileResponsePolicy.failure(401,mapOf("X-App-Integrity-Error" to "expired")))
        assertNull(MobileResponsePolicy.failure(200,mapOf("Content-Type" to "application/json","X-App-Integrity-Error" to "required")))
    }
}
