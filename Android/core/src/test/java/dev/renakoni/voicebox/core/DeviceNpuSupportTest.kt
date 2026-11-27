package dev.renakoni.voicebox.core

import dev.renakoni.voicebox.core.DeviceFacts.NpuSupport
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceNpuSupportTest {
    @Test fun eitherPositiveProbeConfirmsSupport() {
        assertEquals(NpuSupport.AVAILABLE, NpuSupport.fromProbe(75, -1))
        assertEquals(NpuSupport.AVAILABLE, NpuSupport.fromProbe(-1, 1))
    }

    @Test fun absenceRequiresBothProbesToFinish() {
        assertEquals(NpuSupport.UNAVAILABLE, NpuSupport.fromProbe(0, 0))
        assertEquals(NpuSupport.UNKNOWN, NpuSupport.fromProbe(0, -1))
        assertEquals(NpuSupport.UNKNOWN, NpuSupport.fromProbe(-1, 0))
    }
}
