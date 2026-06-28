#extension GL_OES_EGL_image_external : require
precision mediump float;

// Coordinates from Vertex Shader
varying vec2 vTextureCoord;

// External Texture from Hardware Decoder
uniform samplerExternalOES sTexture;

// Dynamic Control Variables
uniform float uGamma;            // To boost stream light in dark areas
uniform int uThermalStatus;      // 0 = Safe, 1 = High Temp (Aggressive saving mode)

void main() {
    // Read raw pixel from GPU VRAM with ZERO COPY
    vec4 color = texture2D(sTexture, vTextureCoord);
    
    // If thermal status is safe, execute advanced processing
    if (uThermalStatus == 0) {
        // 1. Low Light Enhancement (Dynamic Gamma Correction)
        color.rgb = pow(color.rgb, vec3(1.0 / uGamma));
        
        // 2. Cinematic Tone Filtering (Cyberpunk Matrix Tone for Horus Al-Ferdous identity)
        color.r = color.r * 0.9;
        color.g = color.g * 1.05; // Boost Neon Green
        color.b = color.b * 1.1;
    }
    
    // Output final pixel instantly to screen
    gl_FragColor = color;
}
