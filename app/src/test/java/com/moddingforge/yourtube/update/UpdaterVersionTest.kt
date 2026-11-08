package com.moddingforge.yourtube.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterVersionTest {
    @Test fun newerPatchMinorMajor() {
        assertTrue(Updater.isNewer("0.1.1", "0.1.0"))
        assertTrue(Updater.isNewer("0.2.0", "0.1.9"))
        assertTrue(Updater.isNewer("1.0.0", "0.9.10"))
    }

    @Test fun comparesNumericallyNotAlphabetically() {
        assertTrue(Updater.isNewer("0.10.0", "0.9.0"))
        assertFalse(Updater.isNewer("0.9.0", "0.10.0"))
    }

    @Test fun sameOrOlderIsNoUpdate() {
        assertFalse(Updater.isNewer("0.1.0", "0.1.0"))
        assertFalse(Updater.isNewer("0.0.9", "0.1.0"))
    }

    @Test fun missingPartsCountAsZero() {
        assertFalse(Updater.isNewer("1.0", "1.0.0"))
        assertTrue(Updater.isNewer("1.0.1", "1.0"))
    }

    @Test fun suffixesAreIgnored() {
        assertFalse(Updater.isNewer("0.1.0-beta", "0.1.0"))
        assertTrue(Updater.isNewer("0.2.0-rc1", "0.1.0"))
    }
}
