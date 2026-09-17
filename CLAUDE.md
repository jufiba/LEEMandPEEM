# CLAUDE.md — LEEMandPEEM

## What this project is

Fiji/ImageJ2 plugins for low-energy electron microscopy (LEEM) and photoemission electron microscopy (PEEM), targeting Elmitec instruments running U-view 2002 software.

**GitHub:** https://github.com/Jufiba/LEEMandPEEM  
**Current version:** 2.3.0  
**Parent POM:** pom-scijava 34.1.0  

## Plugins

- **UView_reader** — SCIFIO format plugin for Elmitec `.dat` (UKSOFT2000) files. Registers with SCIFIO so files open via File > Open or drag-and-drop. Single images only.
- **UView_Folder_Reader** — IJ1 PlugIn that reads a folder of `.dat` files into a stack. Embeds metadata (voltage, temperature, FOV, etc.) as slice labels in `key=value\n` format.
- **plotIntensityVsTag** — plots mean intensity per slice vs. a metadata tag. Has a dropdown of numeric tags parsed from slice labels, a formula field (variable `x`, supports `+−*/` and parentheses), and auto-fills the X axis label with the unit from the tag name. "Frame Number" is always available as a fallback tag.
- **processSpectrum** — post-processes an active plot window (produced by plotIntensityVsTag): pre-edge subtraction, post-edge normalisation, and optional difference curve (curve 1 − curve 2). All settings persisted via `ij.Prefs`. Uses 5-point averaging around the chosen energies for robustness.
- **quickNormalize** — one-shot beamtime command that chains plotIntensityVsTag and processSpectrum using their last-used Prefs settings, with no dialogs. Processes the active stack and shows a normalised plot immediately.

## Build

```bash
mvn clean package
```

Always use `clean` — stale `.class` files from previous builds will end up in the jar and cause duplicate-class conflicts in the Fiji updater.

The jar goes to `target/LEEMandPEEM-2.3.0.jar`. Install by copying to `<Fiji>/plugins/`.

## Key technical decisions

- **pom-scijava 34.1.0** as parent (upgraded from pom-imagej 15.8.0 in v2.0.0).
- **`annotationProcessorPaths`** must be set in `maven-compiler-plugin` for SciJava plugin JSON to be generated correctly under Java 25.
- **`plugins.config`** registers `UView_Folder_Reader` under `Plugins>LEEMandPEEM`. This is what ensures the submenu appears in alphabetical order in Fiji's menu — SciJava-only plugins get appended after IJ1 plugins.
- **`suffixSufficient() = true`** in the Checker — required for SCIFIO 0.45 auto-detection; `DefaultFormatService.getFormat()` always sets `checkerIsOpen=false`, so formats with `suffixSufficient=false` are never auto-detected.

## SCIFIO 0.45 migration notes (UView_reader)

Migrated from SCIFIO 0.28 to 0.45 (March 2026). Key API changes:
- `RandomAccessInputStream` → `DataHandle<Location>`
- `isFormat(RandomAccessInputStream)` → `isFormat(DataHandle<Location>)`
- `typedParse(RandomAccessInputStream, ...)` → `typedParse(DataHandle<Location>, ...)`
- `openPlane(..., long[] planeMin, long[] planeMax, ...)` → `openPlane(..., Interval bounds, ...)`
- `getStream()` → `getHandle()`
- `stream.getFilePointer()` → `stream.offset()`
- `meta.createImageMetadata(1)` must be called in `typedParse`, not `populateImageMetadata`
- `attachedRecipeSize` is at absolute offset 46 in the file header
- LEEM seek must account for the recipe block size
- Hidden bit (0x80) must be stripped from raw tag bytes
- `leemdataversion` 1 and 2: LEEM tag data is **embedded inside the image header** in the `LEEMdata` array starting at byte 28. Only version > 2 uses an external block after the markup; both cases use the same tag format. **The embedded array's size is keyed on the IMAGE HEADER version, not on `UKIH_size`:** version ≤ 5 → `LEEMdata[256]` followed by a 4-byte spare (28+256+4 = 288); version > 5 → `LEEMdata[239]` followed by `applied_processing`, gray adjust zone, backgroundvalue and the rendering fields. Reading to the end of the header parses those trailing fields as tags.
- **`readCString()` returns the terminating NUL** in SCIFIO 0.45 (0.28 did not). Strip it, or string values carry a stray NUL and every `length()`-based byte count is off by one. `Parser.readCStr()` wraps this.

## LEEM tag block (authoritative reference)

Spec: [Software_UView_FileFormats_2017.pdf](https://wiki-surfmoss.iqf.csic.es/images/8/8c/Software_UView_FileFormats_2017.pdf) (U-view 15.4.0, last updated 17 April 2018).

The block is a flat `tag, payload, tag, payload...` stream. **The block length is the terminator — `0xFF` means "skip this byte", not "end of block".** Bit 7 of a tag byte marks "recorded but not shown on image" and must be masked off.

- **0–99** — LEEM2000 module readings: name + 1 ASCII unit digit + NUL + float. Unit codes `0=none 1=V 2=mA 3=A 4=°C 5=K 6=mV 7=pA 8=nA 9=µA`. There are **no special cases in this range** (the spec's own example is `0x26` = 38 = Start Voltage, `0xA6` when hidden).
- **100** Mitutoyo micrometer (2 floats) · **101** FOV (string) · **102/103** Varian gauge values (float)
- **104** camera exposure (float, seconds) + 2 bytes `B1,B2` when `leemdataversion > 1`: `B1>0` averaging on with `B2` images, `B1=0` off, `B1=0xFF` sliding average
- **105** title (string) · **106–109** and **120–130** gauges: name + units + float
- **110** FOV name + camera-to-FOV calibration factor (string + float)
- **111** phi, theta (2 floats) · **112** spin · **113** FOV rotation · **114** mirror state · **115/116** MCP screen / channelplate voltage (kV)

Payload sizes for **112 (2 bytes), 113 (4 bytes) and 114 (2 bytes) are not in the spec** — determined empirically as the only split that decodes cleanly to exactly the declared block length: 885/885 in `IMG014.dat` (113, 114) and 1468/1468 in all six blocks of `20241211_007.dat` (112, and 120-130 gauges). Tag 112 carries the spin state (1/0/2 cycling across that file's spin-resolved series), consistent with the spec's `short spin`.

Test files covering the three shapes: `070711000000.dat` (2011, image header v5, `leemdataversion` 2, embedded block), `IMG014.dat` (image header v7, external block, single image), `20241211_007.dat` (6 images, recipe block, tag 112 and 120-130 gauges).

An unknown tag ≥ 100 makes the rest of the block uninterpretable, so both parsers **stop and record `UnreadLEEMTag`** rather than advancing one byte and resynchronising on garbage.

## Multi-image .dat files

A single `.dat` may hold `NrImages` (file header offset 44) images. Each is a full
`image header + markup block + LEEM data block + pixel data` unit, concatenated —
there is no index. Both readers walk the chain and assert it lands exactly on the
file length.

**Never compute the pixel offset as `filelength - 2*W*H`.** That was the original
approach and it silently returned the *last* image's pixels paired with the *first*
image's metadata, discarding every frame in between.

- **UView_Folder_Reader** emits one stack slice per image, each with its own slice
  label, so per-frame tags (`Spin`, `Sample Temp.`, …) are plottable. Slices from a
  multi-image file are titled `name.dat [n/N]`. The dialog's start/count/increment
  still select *files*; every image inside a selected file is read.
- **UView_reader** (SCIFIO) exposes the images as planes on an added `Axes.TIME`
  axis. Its metadata table is the **first** image's LEEM block only — SCIFIO's table
  is per image index, so per-plane tags have nowhere to go. Use the folder reader
  when per-frame metadata matters.

**LEEM strings are Latin-1**, not UTF-8 — `0xB5` is the micro sign (e.g. FOV name
`50µm`). The folder reader decodes with `ISO_8859_1`; SCIFIO's `readCString()` has
no charset parameter, so that path still mangles non-ASCII (cosmetic).

**Slice-label values must be formatted with `Locale.US`** — `plotIntensityVsTag` reads them back with `Double.parseDouble`, which rejects comma decimal separators, and silently drops the tag from the dropdown.

## History

### v2.3.0 (September 2026)
- **LEEM tag parsing rewritten against the format spec.** Removed the fabricated
  `case 16` that desynced the tag stream and lost every reading after
  `Diffr.Stigm.A` (Start Voltage among them); `0xFF` now means "skip", not
  "end of block"; added tags 112/113/114 and the 120–130 gauges; unknown tags
  ≥ 100 stop the loop and record `UnreadLEEMTag` instead of resynchronising on
  garbage; tag 110 relabelled `FOVName`/`FOVCalFactor`; tag 104's B1/B2 exposed
  as `Averaging`.
- **Fixed SCIFIO 0.45 `readCString()` NUL regression** that left the unit digit
  unstripped (`Start Voltage1`) and truncated the tag block by one byte per string.
- **Multi-image `.dat` support** in both readers, replacing the
  `filelength - 2*W*H` offset that returned the last image's pixels with the
  first image's metadata. The folder reader emits one labelled slice per image;
  the SCIFIO reader exposes them on an `Axes.TIME` axis at every `MetadataLevel`.
- **`leemdataversion` 1/2 embedded block sized by image header version**
  (256 for version ≤ 5, 239 above), verified on a 2011 image-header-v5 file.
- **Slice labels formatted with `Locale.US`** — under a comma-decimal locale every
  numeric tag was silently dropped from `plotIntensityVsTag`'s dropdown.
- LEEM strings decoded as Latin-1 in the folder reader.

### v1.0.1 (original)
- OVFFormat, readOVF, writeOVF, getXYZmag, toSpherical, Vector3d included (later moved)
- Parent: pom-imagej 15.8.0

### v2.2.0 (April 2026)
- **processSpectrum**: new plugin that reads from an active PlotWindow and applies pre-edge subtraction, post-edge normalisation, and optional difference curve (curve 1 − curve 2). Energies are averaged over ±2 neighbouring points. All settings persisted via `ij.Prefs`.
- **quickNormalize**: new convenience plugin for beamtime use — chains plotIntensityVsTag and processSpectrum with no dialogs, using the last-saved Prefs from both plugins. Processes the active stack and shows the normalised spectra immediately.
- Removed `[Fkey]` shortcut annotations from all menu labels (shortcuts set manually via Plugins > Shortcuts).

### v2.1.0 (April 2026)
- **UView_Folder_Reader**: reads a companion CSV file (same folder, no "meta" in name) and appends `Energy (eV)` and `M4b` columns as slice label tags. CSV format follows the metadata files produced by the PEEM acquisition system at the Solaris DEMETER beamline. Auto-detects comma vs semicolon delimiter; strips UTF-8 BOM for Windows/Excel compatibility. Dialog options (filter, start, count, increment) are persisted via `ij.Prefs`.
- **plotIntensityVsTag**: independent Y formula field (`y` = mean intensity, `t` = a separately chosen Y tag) for normalization (e.g. `y / t` with M4b). Multi-ROI support via ROI Manager — one labelled curve per ROI, with legend and correct List table columns. X axis label pre-filled from tag name (editable). Plot title set from stack name. All dialog options persisted. Save CSV checkbox exports X + all Y columns to a file.

### v2.0.0 (April 2026)
- Restructured: magnetic/OVF plugins moved to jufiba/MagneticHelper
- Absorbed scifio-UView content (UView_reader, UView_Folder_Reader, plugins.config)
- Upgraded parent to pom-scijava 34.1.0
- plotIntensityVsTag rewritten with dynamic tag dropdown, formula field, Frame Number option
- scifio-UView repo archived on GitHub
