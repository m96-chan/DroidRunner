package io.github.m96chan.droidrunner.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Devices report internal identifiers rather than marketing names, so the
 * cases that matter are the strings real hardware actually produces. The two
 * fixtures below were read off the test devices with `getprop`.
 */
class SocVendorTest {

    @Test fun snapdragon8Gen3ReportsQtiAndIsQualcomm() {
        // NX769J: ro.soc.manufacturer=QTI, ro.soc.model=SM8650, ro.hardware=qcom
        assertEquals(SocVendor.QUALCOMM, SocVendor.detect("QTI SM8650 qcom"))
    }

    @Test fun mediatekDimensityReportsMediatekAndAnMtNumber() {
        // 2511FPC34G: ro.soc.manufacturer=Mediatek, ro.soc.model=MT6899
        assertEquals(SocVendor.MEDIATEK, SocVendor.detect("Mediatek MT6899 mt6899"))
    }

    @Test fun qualcommIsFoundByModelNumberAlone() {
        // Older devices leave SOC_MANUFACTURER empty and only expose the model.
        assertEquals(SocVendor.QUALCOMM, SocVendor.detect("SDM845"))
        assertEquals(SocVendor.QUALCOMM, SocVendor.detect("msm8998"))
    }

    @Test fun qualcommCodenamesAreRecognised() {
        assertEquals(SocVendor.QUALCOMM, SocVendor.detect("Qualcomm Technologies, Inc lahaina"))
    }

    @Test fun marketingNamesStillWork() {
        assertEquals(SocVendor.QUALCOMM, SocVendor.detect("Qualcomm Snapdragon 888"))
        assertEquals(SocVendor.MEDIATEK, SocVendor.detect("MediaTek Dimensity 9300"))
        assertEquals(SocVendor.GOOGLE_TENSOR, SocVendor.detect("Google Tensor G3"))
        assertEquals(SocVendor.SAMSUNG_EXYNOS, SocVendor.detect("Samsung Exynos 2400"))
        assertEquals(SocVendor.HISILICON, SocVendor.detect("HiSilicon Kirin 990"))
    }

    @Test fun pixelSocModelsAreRecognised() {
        assertEquals(SocVendor.GOOGLE_TENSOR, SocVendor.detect("Google gs201"))
    }

    @Test fun unknownSiliconYieldsNoVendor() {
        assertNull(SocVendor.detect("Acme Frobnicator 9000"))
        assertNull(SocVendor.detect(""))
    }

    @Test fun mtNumberDoesNotMatchInsideOtherWords() {
        // "mt" followed by four digits is the MediaTek pattern; a stray match
        // inside an unrelated token would mislabel the device.
        assertNull(SocVendor.detect("format1234 device"))
    }
}

class QualcommHintLabelTest {

    @Test fun aSnapdragonNoLongerClaimsAnNpuFromItsName() {
        // npu-qnn was on these phones long before anything could use the
        // Hexagon: NNAPI reaches only the CPU on a Snapdragon, so the label
        // sent jobs to a device that ran everything on its CPU and said
        // nothing (#80). It is earned by a measured run now, not by a name.
        assertNull(SocVendor.QUALCOMM.npuLabel)
    }

    @Test fun theOtherVendorsKeepTheirHints() {
        // They reach their accelerators through NNAPI, where the probe can
        // confirm what the name suggests.
        assertEquals("npu-neuron", SocVendor.MEDIATEK.npuLabel)
        assertEquals("npu-tflite", SocVendor.GOOGLE_TENSOR.npuLabel)
    }
}
