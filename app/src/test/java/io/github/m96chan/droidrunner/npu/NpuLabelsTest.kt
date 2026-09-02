package io.github.m96chan.droidrunner.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NpuLabelsTest {
    @Test fun mediatekAcceleratorsProduceNeuronLabels() {
        val json = """
            {"available":true,"devices":[
              {"name":"mtk-dsp_shim","type":"accelerator"},
              {"name":"mtk-mdla_shim","type":"accelerator"},
              {"name":"mtk-neuron_shim","type":"accelerator"},
              {"name":"nnapi-reference","type":"cpu"}]}
        """.trimIndent()
        val labels = NpuLabels.fromDevicesJson(json)
        assertTrue(labels.containsAll(setOf("nnapi", "nnapi-accelerator", "npu-neuron")))
    }

    @Test fun cpuOnlyDeviceGetsNoAcceleratorLabel() {
        val json = """{"available":true,"devices":[{"name":"nnapi-reference","type":"cpu"}]}"""
        assertEquals(setOf("nnapi"), NpuLabels.fromDevicesJson(json))
    }

    @Test fun unavailableNnapiProducesNoLabels() {
        val json = """{"available":false,"error":"libneuralnetworks unavailable"}"""
        assertTrue(NpuLabels.fromDevicesJson(json).isEmpty())
    }

    @Test fun qualcommDriversMapToQnn() {
        val json = """{"available":true,"devices":[{"name":"qti-dsp","type":"accelerator"}]}"""
        assertTrue(NpuLabels.fromDevicesJson(json).contains("npu-qnn"))
    }

    @Test fun malformedJsonIsIgnored() {
        assertTrue(NpuLabels.fromDevicesJson("not json").isEmpty())
    }
}
