package com.tricreta.scopewa.brain.campaign

import com.tricreta.scopewa.brain.pacing.PacingPlanner
import com.tricreta.scopewa.brain.pacing.PacingProfiles
import com.tricreta.scopewa.brain.safety.PauseReason
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CampaignEngineTest {

    private val profile = PacingProfiles.Normal

    // Seeded so the randomised delays are reproducible run to run.
    private val engine = CampaignEngine(PacingPlanner(profile, Random(seed = 1234)))

    /**
     * A campaign mid-flight with nothing wrong: inside active hours, well under
     * the cap, replies coming in, and not yet due a long pause.
     */
    private fun healthyState(overrides: EngineState.() -> EngineState = { this }) =
        EngineState(
            pendingCount = 10,
            sentSinceLastLongPause = 3,
            consecutiveFailures = 0,
            restrictionDialogSeen = false,
            sentToday = 5,
            dailyCap = 30,
            currentHour = 12,
            activeHoursStart = 8,
            activeHoursEnd = 20,
            repliesInCurrentBatch = 1,
            sentInCurrentBatch = 3,
            batchSizeForReplyCheck = 20
        ).overrides()

    @Test
    fun `an empty queue finishes the campaign`() {
        assertEquals(CampaignStep.Finished, engine.nextStep(healthyState { copy(pendingCount = 0) }))
    }

    @Test
    fun `finishing wins even when a pause and a long pause would both otherwise fire`() {
        val state = healthyState {
            copy(
                pendingCount = 0,
                consecutiveFailures = 5,
                restrictionDialogSeen = true,
                sentToday = 30,
                dailyCap = 30,
                sentSinceLastLongPause = profile.pauseEveryMessages
            )
        }

        assertEquals(CampaignStep.Finished, engine.nextStep(state))
    }

    @Test
    fun `safety is evaluated before pacing, so a capped campaign pauses instead of taking a long break`() {
        // Both conditions are true at once. Pausing is always safe; a polite
        // long break would let the campaign resume sending past its cap.
        val state = healthyState {
            copy(
                sentToday = 30,
                dailyCap = 30,
                sentSinceLastLongPause = profile.pauseEveryMessages
            )
        }

        assertEquals(CampaignStep.Pause(PauseReason.DailyCapReached), engine.nextStep(state))
    }

    @Test
    fun `three consecutive failures pauses the campaign`() {
        val state = healthyState { copy(consecutiveFailures = 3) }

        assertEquals(CampaignStep.Pause(PauseReason.ConsecutiveFailures), engine.nextStep(state))
    }

    @Test
    fun `a WhatsApp restriction dialog pauses the campaign`() {
        val state = healthyState { copy(restrictionDialogSeen = true) }

        assertEquals(CampaignStep.Pause(PauseReason.RestrictionDialogShown), engine.nextStep(state))
    }

    @Test
    fun `reaching the daily cap pauses the campaign`() {
        val state = healthyState { copy(sentToday = 30, dailyCap = 30) }

        assertEquals(CampaignStep.Pause(PauseReason.DailyCapReached), engine.nextStep(state))
    }

    @Test
    fun `being outside active hours pauses the campaign`() {
        val state = healthyState { copy(currentHour = 22, activeHoursStart = 8, activeHoursEnd = 20) }

        assertEquals(CampaignStep.Pause(PauseReason.OutsideActiveHours), engine.nextStep(state))
    }

    @Test
    fun `a whole batch with no replies pauses as a cold batch`() {
        val state = healthyState {
            copy(sentInCurrentBatch = 20, repliesInCurrentBatch = 0, batchSizeForReplyCheck = 20)
        }

        assertEquals(CampaignStep.Pause(PauseReason.ColdBatchNoReplies), engine.nextStep(state))
    }

    @Test
    fun `a healthy campaign sends after a delay inside the profile's range`() {
        repeat(200) {
            val step = engine.nextStep(healthyState())

            assertTrue("expected Send but was $step", step is CampaignStep.Send)
            val delay = (step as CampaignStep.Send).delaySeconds
            assertTrue(
                "delay $delay outside ${profile.minDelaySeconds}..${profile.maxDelaySeconds}",
                delay in profile.minDelaySeconds..profile.maxDelaySeconds
            )
        }
    }

    @Test
    fun `a due long pause lasts a number of seconds inside the profile's minute range`() {
        val state = healthyState { copy(sentSinceLastLongPause = profile.pauseEveryMessages) }
        val minSeconds = profile.longPauseMinMinutes * 60
        val maxSeconds = profile.longPauseMaxMinutes * 60

        repeat(200) {
            val step = engine.nextStep(state)

            assertTrue("expected LongPause but was $step", step is CampaignStep.LongPause)
            val seconds = (step as CampaignStep.LongPause).seconds
            assertTrue("pause $seconds outside $minSeconds..$maxSeconds", seconds in minSeconds..maxSeconds)
        }
    }

    @Test
    fun `no long pause is due one message short of the profile's batch size`() {
        val state = healthyState { copy(sentSinceLastLongPause = profile.pauseEveryMessages - 1) }

        assertTrue(engine.nextStep(state) is CampaignStep.Send)
    }

    @Test
    fun `no long pause is due before the first message of a run`() {
        val state = healthyState { copy(sentSinceLastLongPause = 0) }

        assertTrue(engine.nextStep(state) is CampaignStep.Send)
    }

    @Test
    fun `checkSafety returns null when nothing is wrong`() {
        assertNull(engine.checkSafety(healthyState()))
    }

    @Test
    fun `checkSafety reports the reason the composer should warn about before Start`() {
        val state = healthyState { copy(currentHour = 3, activeHoursStart = 8, activeHoursEnd = 20) }

        assertEquals(PauseReason.OutsideActiveHours, engine.checkSafety(state))
    }
}
