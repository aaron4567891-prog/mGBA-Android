# Android shader menu

Open **Video > Shaders** on the library screen or during a game. Shaders are off by default. Choose **GBA colour + LCD3x preset** to apply the effects shown in the reference screenshot. **Shaders: Off** restores the existing Sharp HD, Smooth HD or Scale2x picture. The older LCD-grid toggle is separate and can be turned off when using LCD3x.

Each pass has a shader selector, Nearest/Linear filtering, Source/Screen scaling, parameters (where the shader declares them), Move Up and Remove controls. Add up to six passes. Source scaling uses the previous pass's image size; Screen scaling uses the displayed game rectangle. For LCD3x, Screen 1x gives a visible subpixel pattern on an HD display; Source 1x can undersample that pattern.

The bundled **gba-color** is a GLES port of the Slang implementation, using the same colour matrices, luminance and gamma/darkness calculations. Open that pass's **Parameters** for Color Profile (1 = sRGB, 2 = DCI, 3 = Rec2020; default 1) and Screen Darkness (0.0–2.2 in 0.1 steps; default 0.0). **Defaults** restores these values if you previously adjusted the older GLSL colour shader. LCD3x retains its Brighten Scanlines and Brighten LCD parameters. Display calibration, pass scaling and GPU rounding can still affect a side-by-side comparison with another app.

Use **Import file** for a self-contained `.glsl`. Use **Import folder** for a `.glslp` preset or a shader with includes: select their common parent folder, then the shader/preset. The importer copies source and resolves includes into private app storage, so files remain available after restarting or disconnecting the original storage. Imported shaders appear in the pass selector. Importing a preset asks before replacing the current passes.

During a game, select **Customize this game** in Video before opening Shaders to keep that ROM's configuration separate. **Use global display settings** returns to the shared configuration. Shader changes apply when returning to gameplay; game rendering remains on the main/top display in dual-screen mode.

## Supported files

This is a GLES 2 renderer for combined RetroArch-style GLSL shaders with `VERTEX` and `FRAGMENT` branches. It supports ordered passes, original/prior-pass textures, standard image-size and frame uniforms, `#pragma parameter`, relative includes and matching X/Y source or viewport scale from 0.25x to 4x. Pass targets use RGBA8. Total intermediate storage is bounded to 16 million pixels, with a 4096-pixel per-dimension cap (or the GPU limit if lower).

It does **not** load `.slang`, `.slangp` or Cg files. Obtain the GLES-compatible GLSL version instead. Lookup textures, previous-frame/feedback shaders, named pass aliases, uniform arrays, absolute/unequal-axis scaling, mipmaps and float/sRGB targets are not supported. Unknown active uniforms, unsupported presets and compilation failures are reported, rather than silently approximated. A rendering failure restores the normal Canvas picture and reports the error; it is also recorded if diagnostic logging is enabled. Correct the shader configuration or toggle it off/on to retry.

## Included shader credits

The original public-domain license comments are retained in the bundled files:

- gba-color.glsl is a GLES 2 port of [gba-color.slang at 9d68d530](https://github.com/libretro/slang-shaders/blob/9d68d530a92b5666c8dc151a6884aa8ce16df095/handheld/shaders/color/gba-color.slang): hunterk, Pokefan531 and crashGG, public domain. Shader interface syntax is adapted for GLES 2; the colour calculations and parameter ranges/defaults are preserved.
- [lcd3x.glsl](https://github.com/libretro/glsl-shaders/blob/master/handheld/shaders/lcd3x.glsl): Gigaherz, public domain.

Only the shader files come from Libretro. This standalone app continues to use the mGBA core directly.

## Validation

`gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`

The JVM tests check preset parsing, parameters and include path bounds. The Android instrumentation tests exercise the actual GPU: colour channels and orientation across different pass counts, the bundled two-pass preset, parameter changes, rebuilding renderer resources, scale limits and per-game setting isolation.
