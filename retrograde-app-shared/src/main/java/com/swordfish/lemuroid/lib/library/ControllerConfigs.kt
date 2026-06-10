package com.swordfish.lemuroid.lib.library

import com.swordfish.lemuroid.lib.R
import com.swordfish.lemuroid.lib.controller.ControllerConfig
import com.swordfish.touchinput.radial.sensors.TILT_CONFIGURATION_ANALOG_LEFT
import com.swordfish.touchinput.radial.sensors.TILT_CONFIGURATION_ANALOG_RIGHT
import com.swordfish.touchinput.radial.sensors.TILT_CONFIGURATION_CROSS
import com.swordfish.touchinput.radial.sensors.TILT_CONFIGURATION_DISABLED
import com.swordfish.touchinput.radial.sensors.TILT_CONFIGURATION_L1_R1
import com.swordfish.touchinput.radial.sensors.TILT_CONFIGURATION_L2_R2
import com.swordfish.touchinput.radial.sensors.TILT_CONFIGURATION_L_R
import com.swordfish.touchinput.radial.settings.TouchControllerID

// TODO PADS... Make sure the ids are correct.
object ControllerConfigs {
    val ATARI_2600 =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.ATARI2600,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val NES =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.NES,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val SNES =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.SNES,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                    TILT_CONFIGURATION_L_R,
                ),
        )

    val SMS =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.SMS,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val GENESIS_6 =
        ControllerConfig(
            "default_6",
            R.string.controller_genesis_6,
            TouchControllerID.GENESIS_6,
            mergeDPADAndLeftStickEvents = true,
            libretroDescriptor = "MD Joypad 6 Button",
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val GENESIS_3 =
        ControllerConfig(
            "default_3",
            R.string.controller_genesis_3,
            TouchControllerID.GENESIS_3,
            mergeDPADAndLeftStickEvents = true,
            libretroDescriptor = "MD Joypad 3 Button",
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val GG =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.GG,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val GB =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.GB,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val GBA =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.GBA,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                    TILT_CONFIGURATION_L_R,
                ),
        )

    val N64 =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.N64,
            allowTouchRotation = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                    TILT_CONFIGURATION_ANALOG_LEFT,
                    TILT_CONFIGURATION_L_R,
                ),
        )

    val PSX_STANDARD =
        ControllerConfig(
            "standard",
            R.string.controller_standard,
            TouchControllerID.PSX,
            mergeDPADAndLeftStickEvents = true,
            libretroDescriptor = "standard",
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                    TILT_CONFIGURATION_L1_R1,
                    TILT_CONFIGURATION_L2_R2,
                ),
        )

    val PSX_DUALSHOCK =
        ControllerConfig(
            "dualshock",
            R.string.controller_dualshock,
            TouchControllerID.PSX_DUALSHOCK,
            allowTouchRotation = true,
            libretroDescriptor = "dualshock",
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                    TILT_CONFIGURATION_ANALOG_LEFT,
                    TILT_CONFIGURATION_ANALOG_RIGHT,
                    TILT_CONFIGURATION_L1_R1,
                    TILT_CONFIGURATION_L2_R2,
                ),
        )

    val PSP =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.PSP,
            allowTouchRotation = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                    TILT_CONFIGURATION_ANALOG_LEFT,
                    TILT_CONFIGURATION_L_R,
                ),
        )

    val FB_NEO_4 =
        ControllerConfig(
            "default_4",
            R.string.controller_arcade_4,
            TouchControllerID.ARCADE_4,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val FB_NEO_6 =
        ControllerConfig(
            "default_6",
            R.string.controller_arcade_6,
            TouchControllerID.ARCADE_6,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val MAME_2003_4 =
        ControllerConfig(
            "default_4",
            R.string.controller_arcade_4,
            TouchControllerID.ARCADE_4,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val MAME_2003_6 =
        ControllerConfig(
            "default_6",
            R.string.controller_arcade_6,
            TouchControllerID.ARCADE_6,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val DESMUME =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.DESMUME,
            allowTouchOverlay = false,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                    TILT_CONFIGURATION_L_R,
                ),
        )

    val MELONDS =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.MELONDS,
            mergeDPADAndLeftStickEvents = true,
            allowTouchOverlay = false,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                    TILT_CONFIGURATION_L_R,
                ),
        )

    val LYNX =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.LYNX,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val ATARI7800 =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.ATARI7800,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val PCE =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.PCE,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                    TILT_CONFIGURATION_L_R,
                ),
        )

    val NGP =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.NGP,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val DOS_AUTO =
        ControllerConfig(
            "auto",
            R.string.controller_dos_auto,
            TouchControllerID.DOS,
            allowTouchRotation = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                    TILT_CONFIGURATION_ANALOG_LEFT,
                    TILT_CONFIGURATION_ANALOG_RIGHT,
                    TILT_CONFIGURATION_L1_R1,
                    TILT_CONFIGURATION_L2_R2,
                ),
        )

    val WS_LANDSCAPE =
        ControllerConfig(
            "landscape",
            R.string.controller_landscape,
            TouchControllerID.WS_LANDSCAPE,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val WS_PORTRAIT =
        ControllerConfig(
            "portrait",
            R.string.controller_portrait,
            TouchControllerID.WS_PORTRAIT,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val NINTENDO_3DS =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.NINTENDO_3DS,
            allowTouchOverlay = false,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                    TILT_CONFIGURATION_ANALOG_LEFT,
                    TILT_CONFIGURATION_L_R,
                ),
        )

    val MSX =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.SMS,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val VIRTUAL_BOY =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.VIRTUAL_BOY,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                    TILT_CONFIGURATION_L_R,
                ),
        )

    val POKEMON_MINI =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.POKEMON_MINI,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val C64 =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.C64,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val AMSTRAD_CPC =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.AMSTRAD_CPC,
            mergeDPADAndLeftStickEvents = true,
            libretroDescriptor = "Amstrad Joystick",
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val AMSTRAD_GX4000 =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.AMSTRAD_GX4000,
            mergeDPADAndLeftStickEvents = true,
            // The GX4000 is a cartridge console built on the Amstrad CPC Plus hardware, so
            // cap32 registers its joystick controller with the same descriptor as the CPC,
            // "Amstrad Joystick". Matching it forces setControllerType(port, RETRO_DEVICE_JOYPAD)
            // so the two fire buttons + d-pad work (same mechanism as Amstrad CPC / 3DO).
            libretroDescriptor = "Amstrad Joystick",
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val ARDUBOY =
        ControllerConfig(
            "default",
            R.string.controller_default,
            // The Arduboy has only a d-pad and two buttons (A, B), so it reuses the Game Boy
            // touch layout (same pattern as Mega Duck). arduous maps the Arduboy A button to
            // RetroPad A and B to RetroPad B, and defaults its port to RETRO_DEVICE_JOYPAD.
            TouchControllerID.GB,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val LOWRES_NX =
        ControllerConfig(
            "default",
            R.string.controller_default,
            // LowRes NX is a fantasy console with NES-style controls (d-pad, two action buttons,
            // Start, Select), so it reuses the NES touch layout. Button 1 -> RetroPad A,
            // Button 2 -> RetroPad B. The core defaults its port to RETRO_DEVICE_JOYPAD.
            TouchControllerID.NES,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val UZEBOX =
        ControllerConfig(
            "default",
            R.string.controller_default,
            // The Uzebox uses standard SNES controllers; uzem maps the RetroPad directly to the
            // SNES gamepad, so it reuses the SNES touch layout (same pattern as VECTREX->SNES).
            // uzem defaults its port to RETRO_DEVICE_JOYPAD, so no libretroDescriptor is needed.
            TouchControllerID.SNES,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                    TILT_CONFIGURATION_L_R,
                ),
        )

    val CHANNEL_F =
        ControllerConfig(
            "default",
            R.string.controller_default,
            // FreeChaF does NOT call SET_CONTROLLER_INFO and processes RETRO_DEVICE_JOYPAD by
            // default, so no libretroDescriptor override is required. Button mapping (from the
            // core's retro_input_descriptor): B=push/fire, A=rotate right, Y=rotate left,
            // X=pull, SELECT=swap controllers, START=toggle console/controller input.
            TouchControllerID.CHANNEL_F,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val MEGADUCK =
        ControllerConfig(
            "default",
            R.string.controller_default,
            // The Mega Duck is a Game Boy clone with identical controls (d-pad, A, B, Start,
            // Select), so it reuses the Game Boy touch layout (same pattern as MSX->SMS).
            // SameDuck (SameBoy-based) defaults its port to RETRO_DEVICE_JOYPAD, so no
            // libretroDescriptor override is needed.
            TouchControllerID.GB,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val VECTREX =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.SNES,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val INTELLIVISION =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.INTELLIVISION,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val DREAMCAST =
        ControllerConfig(
            "default",
            R.string.controller_dreamcast,
            TouchControllerID.DREAMCAST,
            allowTouchRotation = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                    TILT_CONFIGURATION_ANALOG_LEFT,
                    TILT_CONFIGURATION_L2_R2,
                ),
        )

    val THREE_DO =
        ControllerConfig(
            "default",
            R.string.controller_3do,
            TouchControllerID.THREE_DO,
            mergeDPADAndLeftStickEvents = true,
            // Opera defaults every port to RETRO_DEVICE_NONE and skips input processing
            // until setControllerType is called. Matching the descriptor that Opera
            // registers via SET_CONTROLLER_INFO forces GameViewModelInput.updateControllers()
            // to invoke setControllerType(port, RETRO_DEVICE_JOYPAD), enabling input on
            // the port. Opera registers this descriptor literally as "3DO Joypad".
            libretroDescriptor = "3DO Joypad",
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                    TILT_CONFIGURATION_L_R,
                ),
        )

    val PICO_8 =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.PICO_8,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val VIRCON32 =
        ControllerConfig(
            "default",
            R.string.controller_vircon32,
            TouchControllerID.VIRCON32,
            mergeDPADAndLeftStickEvents = true,
            libretroDescriptor = "Vircon32 Gamepad",
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                    TILT_CONFIGURATION_L_R,
                ),
        )

    val SEGA_32X =
        ControllerConfig(
            "default_6",
            R.string.controller_sega32x,
            TouchControllerID.SEGA_32X,
            mergeDPADAndLeftStickEvents = true,
            libretroDescriptor = "MD Joypad 6 Button",
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val FDS =
        ControllerConfig(
            "default",
            R.string.controller_fds,
            TouchControllerID.FDS,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val ATARI800 =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.ATARI800,
            mergeDPADAndLeftStickEvents = true,
            // The atari800 core registers the JOYPAD-subclass joystick via
            // SET_CONTROLLER_INFO literally as "ATARI Joystick". Matching it forces
            // setControllerType(port, RETRO_DEVICE_ATARI_JOYSTICK) so joystick input
            // is processed (same mechanism as 3DO/Amstrad).
            libretroDescriptor = "ATARI Joystick",
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val GAMECUBE =
        ControllerConfig(
            "default",
            R.string.controller_gc,
            TouchControllerID.GAMECUBE,
            allowTouchRotation = true,
            // Dolphin registers the GameCube pad via SET_CONTROLLER_INFO literally as
            // "GameCube Controller" (RETRO_DEVICE_JOYPAD). Matching it forces
            // setControllerType(port, RETRO_DEVICE_JOYPAD) so input is processed.
            libretroDescriptor = "GameCube Controller",
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                    TILT_CONFIGURATION_ANALOG_LEFT,
                    TILT_CONFIGURATION_L2_R2,
                ),
        )

    val SATURN =
        ControllerConfig(
            "default",
            R.string.controller_saturn,
            TouchControllerID.SATURN,
            mergeDPADAndLeftStickEvents = true,
            // YabaSanshiro registers the digital Saturn pad via SET_CONTROLLER_INFO
            // literally as "Saturn Pad" (RETRO_DEVICE_JOYPAD). Matching it forces
            // setControllerType(port, RETRO_DEVICE_JOYPAD) so input is processed.
            libretroDescriptor = "Saturn Pad",
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                    TILT_CONFIGURATION_L2_R2,
                ),
        )

    val JAGUAR =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.JAGUAR,
            mergeDPADAndLeftStickEvents = true,
            // Virtual Jaguar does NOT call SET_CONTROLLER_INFO and processes
            // RETRO_DEVICE_JOYPAD by default, so no libretroDescriptor override is needed.
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val ODYSSEY2 =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.ODYSSEY2,
            mergeDPADAndLeftStickEvents = true,
            // O2EM does NOT call SET_CONTROLLER_INFO and processes RETRO_DEVICE_JOYPAD
            // by default, so no libretroDescriptor override is needed.
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val NEOCD =
        ControllerConfig(
            "default",
            R.string.controller_default,
            // Neo Geo CD uses the standard 4-button Neo Geo pad, so it reuses the Arcade
            // 4-button touch layout (same as FBNeo Neo Geo). NeoCD processes
            // RETRO_DEVICE_JOYPAD by default, so no libretroDescriptor override is needed.
            TouchControllerID.ARCADE_4,
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val PCFX =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.PCFX,
            mergeDPADAndLeftStickEvents = true,
            // Beetle PC-FX registers the pad via SET_CONTROLLER_INFO literally as
            // "PCFX Joypad" (RETRO_DEVICE_JOYPAD). Matching it forces
            // setControllerType(port, RETRO_DEVICE_JOYPAD) so input is processed.
            libretroDescriptor = "PCFX Joypad",
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                    TILT_CONFIGURATION_L_R,
                ),
        )

    val AMIGA =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.AMIGA,
            // PUAE initialises every port to RETRO_DEVICE_NONE (retro_devices[] = {0}) and gates
            // BOTH the digital joystick (is_retropad) AND the RetroPad hotkeys — including the
            // default Select -> TOGGLE_VKBD that brings up the virtual keyboard — on the port
            // device being a recognised PUAE pad (libretro-mapper.c is_retropad + the hotkey
            // loop). Without an explicit setControllerType the port stays NONE, so only the
            // un-gated analog->mouse path (puae_analogmouse="both") responds: that is the
            // "only the stick works, buttons/keyboard dead" bug on Amiga/1200/CDTV/CD32.
            // We match PUAE's "RetroPad" descriptor (RETRO_DEVICE_PUAE_JOYPAD) rather than
            // "Automatic" (RETRO_DEVICE_JOYPAD == 1): id 1 is the libretro DEFAULT device, which
            // the frontend may treat as a no-op and never forward to the core, leaving the port
            // at NONE. "RetroPad" is a distinct subclass id, so setControllerType always takes
            // effect, and is_retropad() returns true unconditionally for it. This enables the
            // joystick + fire buttons and the Select-toggled virtual keyboard.
            libretroDescriptor = "RetroPad",
            allowTouchRotation = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                    TILT_CONFIGURATION_ANALOG_LEFT,
                    TILT_CONFIGURATION_L_R,
                ),
        )

    val ATARI_ST =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.ATARI_ST,
            mergeDPADAndLeftStickEvents = true,
            // Hatari does NOT call SET_CONTROLLER_INFO and defaults to RETRO_DEVICE_JOYPAD,
            // so no libretroDescriptor override is needed.
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )

    val GAME_WATCH =
        ControllerConfig(
            "default",
            R.string.controller_default,
            TouchControllerID.GAME_WATCH,
            // gw core does NOT call SET_CONTROLLER_INFO and defaults to RETRO_DEVICE_JOYPAD,
            // so no libretroDescriptor override is needed.
            mergeDPADAndLeftStickEvents = true,
            tiltConfigurations =
                listOf(
                    TILT_CONFIGURATION_DISABLED,
                    TILT_CONFIGURATION_CROSS,
                ),
        )
}
