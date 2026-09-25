/*
   Shader Modified: Pokefan531
   Color Mangler
   Author: hunterk
   Optimized by: crashGG
   License: Public domain

   GLES 2 port of libretro/slang-shaders/handheld/shaders/color/gba-color.slang
   at 9d68d530a92b5666c8dc151a6884aa8ce16df095.
   Keeps the Slang colour matrices, luminance, gamma, parameters and defaults.
   Only shader interface syntax and round() (not available in GLES 2) differ.
*/

#pragma parameter profile "Color Profile (1=sRGB, 2=DCI, 3=Rec2020)" 1.0 1.0 3.0 1.0
#pragma parameter darken_screen "Screen Darkness" 0.0 0.0 2.2 0.1

#if defined(VERTEX)
attribute vec4 VertexCoord;
attribute vec2 TexCoord;
uniform mat4 MVPMatrix;
varying mediump vec2 vTexCoord;

void main() {
    gl_Position = MVPMatrix * VertexCoord;
    vTexCoord = TexCoord;
}

#elif defined(FRAGMENT)
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D Texture;
varying mediump vec2 vTexCoord;

#ifdef PARAMETER_UNIFORM
uniform float profile;
uniform float darken_screen;
#else
#define profile 1.0
#define darken_screen 0.0
#endif

const float GAMMA = 2.2;
const float INV_GAMMA = 1.0 / GAMMA;
const float INV_GAMMA_SQ = 1.0 / (GAMMA * GAMMA);

// GLSL matrices are column-major, matching the original Slang shader.
const mat4 GBA_sRGB = mat4(
    0.905, 0.10,  0.1575, 0.0,
    0.195, 0.65,  0.1425, 0.0,
    -0.10, 0.25,  0.70,   0.0,
    0.0,   0.0,   0.0,    0.91
);
const mat4 GBA_DCI = mat4(
    0.76,  0.125,  0.16,  0.0,
    0.27,  0.6375, 0.18,  0.0,
    -0.03, 0.2375, 0.66,  0.0,
    0.0,   0.0,    0.0,   0.97
);
const mat4 GBA_Rec2020 = mat4(
    0.61,  0.155, 0.16,   0.0,
    0.345, 0.615, 0.1875, 0.0,
    0.045, 0.23,  0.6525, 0.0,
    0.0,   0.0,   0.0,    1.0
);
const float sRGB_lum = GBA_sRGB[3].w;
const float DCI_lum = GBA_DCI[3].w;
const float Rec2020_lum = GBA_Rec2020[3].w;

void main() {
    vec3 texel = texture2D(Texture, vTexCoord).rgb;
    vec3 screen = pow(texel, vec3(GAMMA));

    // profile is restricted to 1, 2 or 3 by the parameter's integer step.
    int color_mode = int(floor(profile + 0.5));
    if (color_mode == 1) screen = mat3(GBA_sRGB) * screen * sRGB_lum;
    else if (color_mode == 2) screen = mat3(GBA_DCI) * screen * DCI_lum;
    else if (color_mode == 3) screen = mat3(GBA_Rec2020) * screen * Rec2020_lum;

    screen = clamp(screen, 0.0, 1.0);
    float final_gamma = darken_screen * INV_GAMMA_SQ + INV_GAMMA;
    screen = pow(screen, vec3(final_gamma));
    gl_FragColor = vec4(screen, 1.0);
}
#endif
