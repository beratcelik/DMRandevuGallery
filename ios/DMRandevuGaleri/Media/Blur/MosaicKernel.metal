#include <CoreImage/CoreImage.h>

using namespace metal;

// Pixelates one region of the frame and reports how strongly it should be painted over the
// original, as premultiplied alpha — so compositing the result is the same `mix` the Android
// fragment shader does.
//
// Mosaic rather than a gaussian blur: it is one texture fetch per pixel instead of a kernel, and
// at a cell size around a seventh of the region there is nothing left to reconstruct.
//
// Invoked with the region's own rectangle as the destination extent, so Core Image only ever
// shades the handful of pixels the region covers, however many regions a frame has.
extern "C" float4 mosaicPatch(
    coreimage::sampler source,
    float2 centre,       // region centre, in pixels of the frame
    float2 halfExtents,  // region half width and half height, in pixels
    float2 cell,         // mosaic cell size, in pixels
    float isRect,        // 1 for a numberplate, 0 for a head
    coreimage::destination dest)
{
    float2 position = dest.coord();
    float2 d = (position - centre) / max(halfExtents, float2(0.5));

    // A head is covered by the ellipse inscribed in its box; a plate is a rectangle, and an
    // ellipse over one would miss the first and last characters while spilling over the bodywork
    // above and below.
    float inside = isRect > 0.5
        ? 1.0 - smoothstep(0.90, 1.0, max(abs(d.x), abs(d.y)))
        : 1.0 - smoothstep(0.85, 1.0, dot(d, d));
    if (inside <= 0.0) {
        return float4(0.0);
    }

    float2 snapped = (floor(position / cell) + 0.5) * cell;
    float4 colour = source.sample(source.transform(snapped));
    return float4(colour.rgb * inside, inside);
}

