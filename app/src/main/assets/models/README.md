# MagicTouch (revision 1)

Used for tap-based person/object selection in Foldex. The original model is
redistributed without modification. Foldex's own source remains MIT licensed.

- Authors: Valentin Bazarevsky and Ben Hahn, Google.
- License: Apache License 2.0.
- Model card: https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MagicTouch.pdf
- Source: https://storage.googleapis.com/mediapipe-models/interactive_segmenter/magic_touch/float32/1/magic_touch.tflite
- File: `magic_touch.tflite`
- Size: 6,227,884 bytes.
- SHA-256: `e24338a717c1b7ad8d159666677ef400babb7f33b8ad60c4d96db4ecf694cd25`
- Runtime: bundled `com.google.ai.edge.litert:litert:1.4.2` (Apache 2.0).
- Notices: `../licenses/MagicTouch.txt` and `../licenses/LiteRT.txt`.

The actual revision 1 file accepts float32 `[1,512,512,4]` in RGB + tap-prior
order. RGB is divided by 255, and the tap prior has a single pixel set to 1.
The distributed file produces float32 `[1,512,512,1]` **foreground probability**,
already passed through sigmoid. This differs from the two raw output logits
described in the model card; do not apply softmax again or assume two channels.
The app verifies tensor shapes and thresholds foreground probability at 0.5.

Images are resized to 512 × 512, and extracted contours are mapped back to the
logical canvas. The fixed model resolution limits fine detail. Users can refine
the result with manual selection before applying any edit.

The SHA-256 is checked by the image editor unit tests. Changing the model requires
reviewing its license, tensor layout, normalization, and selection quality.
