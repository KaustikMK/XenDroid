package xendroid.compose.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xendroid.compose.settings.Setting
import xendroid.compose.settings.SettingsSchema

/** Schema-integrity checks (no emulator / JNI needed). The schema completeness is
 *  the deliverable, so these assert exact counts, uniqueness, and per-list well-formedness. */
class SettingsSchemaTest {

    private val all = SettingsSchema.allSettings

    // Legacy inventory (app/src/main/res/xml/emulator_settings.xml) summed to 123
    // (100 Bool + 7 IntRange + 15 ListChoice + 1 Action). One Bool, Display|
    // host_present_from_non_ui_thread, was since REMOVED from the schema: it must be
    // true on Android (forced natively) and false black-screens the app, so it is not
    // a valid user choice. XenDroid then added two ListChoices (Vulkan|turnip_debug,
    // GPU|occlusion_query) and reclassified GPU|readback_resolve from Bool to ListChoice
    // (it is a string cvar: fast/some/full/none), then added GPU|vulkan_mid_frame_submission_draws
    // as an IntRange (-> 8 IntRange), then added two Vulkan descriptor-cache Bools
    // (vulkan_cache_texture_descriptors, vulkan_texture_descriptor_reuse_edge -> 100 Bool).
    // Net: 100 Bool + 8 IntRange + 18 ListChoice + 1 Action = 127.
    @Test fun total_entry_count_is_127() {
        assertEquals(127, all.size)
        assertEquals(
            127,
            all.count { it is Setting.Bool } + all.count { it is Setting.IntRange } +
                all.count { it is Setting.ListChoice } + all.count { it is Setting.Action },
        )
    }

    @Test fun counts_by_type_match_verified_inventory() {
        assertEquals(100, all.count { it is Setting.Bool })
        assertEquals(8, all.count { it is Setting.IntRange })
        assertEquals(18, all.count { it is Setting.ListChoice })
        assertEquals(1, all.count { it is Setting.Action })
    }

    @Test fun keys_are_unique() {
        assertEquals(all.size, SettingsSchema.byKey.size)
        assertEquals(all.size, all.map { it.key }.toSet().size)
    }

    @Test fun categories_present_in_legacy_order() {
        val expected = listOf(
            "Vulkan", "Video", "UI", "Storage", "Kernel", "HID", "Memory", "XConfig",
            "Display", "GPU", "CPU", "Logging", "Content", "General", "APU",
        )
        assertEquals(expected, SettingsSchema.categories.map { it.title })
    }

    @Test fun kernel_allow_nui_has_capital_A() {
        assertNotNull(SettingsSchema.byKey["Kernel|Allow_nui_initialization"])
    }

    @Test fun vulkan_lib_path_is_the_only_action() {
        val actions = all.filterIsInstance<Setting.Action>()
        assertEquals(1, actions.size)
        assertEquals("Vulkan|vulkan_lib_path", actions.single().key)
    }

    @Test fun list_defaults_are_empty_or_a_member_of_options() {
        all.filterIsInstance<Setting.ListChoice>().forEach { lc ->
            if (lc.default.isNotEmpty()) {
                assertTrue(
                    "ListChoice ${lc.key} default '${lc.default}' must resolve to an option",
                    lc.options.any { it.value == lc.default },
                )
            }
        }
    }

    @Test fun user_language_skips_10_and_maps_8_and_17_to_zh() {
        val lc = SettingsSchema.byKey["XConfig|user_language"] as Setting.ListChoice
        assertTrue(lc.options.none { it.value == "10" })
        assertEquals("zh", lc.options.first { it.value == "8" }.label)
        assertEquals("zh", lc.options.first { it.value == "17" }.label)
    }

    @Test fun user_country_has_107_options_skips_17_and_94_and_default_103_resolves() {
        val lc = SettingsSchema.byKey["XConfig|user_country"] as Setting.ListChoice
        assertEquals(107, lc.options.size)
        assertTrue(lc.options.none { it.value == "17" })
        assertTrue(lc.options.none { it.value == "94" })
        assertNotNull(lc.options.firstOrNull { it.value == "103" })
        assertEquals("103", lc.default)
    }

    @Test fun int_ranges_match_verified_xml() {
        fun ir(key: String) = SettingsSchema.byKey[key] as Setting.IntRange
        ir("Memory|mmap_address_high").let {
            assertEquals(2, it.min); assertEquals(63, it.max); assertEquals(8, it.default)
        }
        ir("GPU|texture_cache_memory_limit_soft").let {
            // min deliberately diverges from the legacy XML (512): that floor was above the
            // real TOML default (384), which would have silently coerced the default upward.
            assertEquals(384, it.min); assertEquals(4096, it.max); assertEquals(384, it.default)
        }
        ir("GPU|texture_cache_memory_limit_hard").let {
            assertEquals(512, it.min); assertEquals(4096, it.max); assertEquals(768, it.default)
        }
        ir("General|time_scalar").let {
            assertEquals(1, it.min); assertEquals(8, it.max)
        }
        ir("APU|xmp_default_volume").let {
            assertEquals(0, it.min); assertEquals(100, it.max)
        }
        ir("APU|apu_max_queued_frames").let {
            assertEquals(4, it.min); assertEquals(64, it.max)
        }
    }

    /** Regression guard: every IntRange default must be in [min, max], else the slider
     *  silently coerces the persisted default to a different value (the texture-cache bug). */
    @Test fun int_range_defaults_within_bounds() {
        SettingsSchema.allSettings.filterIsInstance<Setting.IntRange>().forEach {
            assert(it.default in it.min..it.max) {
                "${it.key}: default ${it.default} outside [${it.min}, ${it.max}]"
            }
            assert(it.min <= it.max) { "${it.key}: min ${it.min} > max ${it.max}" }
        }
    }
}
