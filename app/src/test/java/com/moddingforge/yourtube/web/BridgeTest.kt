package com.moddingforge.yourtube.web

import com.moddingforge.yourtube.data.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JS side (assets/inject) depends on these message names and fields. */
class BridgeTest {
    // Same configuration as AppContainer.json.
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = true
    }

    @Test fun decodesMessagesFromJs() {
        assertEquals(
            BridgeMessage.SponsorPrompt("sponsor", 83.0),
            json.decodeFromString<BridgeMessage>("""{"type":"sponsor-prompt","category":"sponsor","end":83}"""),
        )
        assertEquals(
            BridgeMessage.SponsorPrompt(),
            json.decodeFromString<BridgeMessage>("""{"type":"sponsor-prompt"}"""),
        )
        assertEquals(
            BridgeMessage.PlayerControls(true),
            json.decodeFromString<BridgeMessage>("""{"type":"player-controls","visible":true,"extra":1}"""),
        )
    }

    @Test fun encodesSegmentsWithAllFieldsTheJsReads() {
        val cmd = BridgeCommand.SponsorSegments("vid", listOf(BridgeCommand.Segment(1.0, 2.0, "sponsor")))
        val obj = json.parseToJsonElement(json.encodeToString<BridgeCommand>(cmd)).jsonObject
        assertEquals("sponsor-segments", obj["type"]!!.jsonPrimitive.content)
        assertTrue(obj["marks"]!!.jsonPrimitive.boolean)
        val seg = obj["segments"]!!.jsonArray[0].jsonObject
        // Defaults must be encoded: the JS treats a missing action as "do nothing".
        assertEquals("auto", seg["action"]!!.jsonPrimitive.content)
        assertTrue(seg["once"]!!.jsonPrimitive.boolean)
    }

    @Test fun playerToolCommand() {
        val obj = json.parseToJsonElement(json.encodeToString<BridgeCommand>(BridgeCommand.PlayerTool("seek-to", "83.0"))).jsonObject
        assertEquals("player-tool", obj["type"]!!.jsonPrimitive.content)
        assertEquals("seek-to", obj["action"]!!.jsonPrimitive.content)
    }

    @Test fun filterRulesFromSettings() {
        val rules = FilterRules.from(AppSettings(filterShorts = true, filterKeywords = "  Reaction \n\nPRANK\n"))
        assertTrue(rules.hideShorts)
        assertEquals(listOf("reaction", "prank"), rules.keywords)
    }
}
