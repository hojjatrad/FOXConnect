package com.foxconnect.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreFailureClassifierTest {
    @Test
    fun `every typed native startup stage remains distinguishable and privacy safe`() {
        val expected = mapOf(
            CoreStartStage.SETUP to "core_setup_failed",
            CoreStartStage.VERSION to "libbox_version_mismatch",
            CoreStartStage.CONFIG_CHECK to "native_config_rejected",
            CoreStartStage.COMMAND_CREATE to "core_command_failed",
            CoreStartStage.COMMAND_START to "core_command_failed",
            CoreStartStage.NETWORK_MONITOR to "core_network_monitor_failed",
            CoreStartStage.SERVICE_START to "core_service_start_failed",
            CoreStartStage.POST_START to "core_post_start_failed",
        )

        expected.forEach { (stage, code) ->
            val failure = CoreStartFailure(stage, IllegalStateException("sensitive-native-detail"))
            assertEquals(code, CoreFailureClassifier.classify(failure))
            assertEquals(stage.code, failure.message)
        }
    }

    @Test
    fun `specific tunnel callback failure overrides broad service stage`() {
        val failure = CoreStartFailure(
            CoreStartStage.SERVICE_START,
            IllegalStateException("tun_establish_failed"),
        )
        assertEquals("tun_establish_failed", CoreFailureClassifier.classify(failure))
    }

    @Test
    fun `unknown errors remain generic`() {
        assertEquals("native_start_failed", CoreFailureClassifier.classify(IllegalStateException()))
    }
}
