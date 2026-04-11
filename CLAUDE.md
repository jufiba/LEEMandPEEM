# CLAUDE.md — LEEMandPEEM

## What this project is

Fiji/ImageJ2 plugins for low-energy electron microscopy (LEEM) and photoemission electron microscopy (PEEM), targeting Elmitec instruments running U-view 2002 software.

**GitHub:** https://github.com/Jufiba/LEEMandPEEM  
**Current version:** 2.0.0  
**Parent POM:** pom-scijava 34.1.0  

## Plugins

- **UView_reader** — SCIFIO format plugin for Elmitec `.dat` (UKSOFT2000) files. Registers with SCIFIO so files open via File > Open or drag-and-drop. Single images only.
- **UView_Folder_Reader** — IJ1 PlugIn that reads a folder of `.dat` files into a stack. Embeds metadata (voltage, temperature, FOV, etc.) as slice labels in `key=value\n` format.
- **plotIntensityVsTag** — plots mean intensity per slice vs. a metadata tag. Has a dropdown of numeric tags parsed from slice labels, a formula field (variable `x`, supports `+−*/` and parentheses), and auto-fills the X axis label with the unit from the tag name. "Frame Number" is always available as a fallback tag.

## Build

```bash
mvn clean package
```

Always use `clean` — stale `.class` files from previous builds will end up in the jar and cause duplicate-class conflicts in the Fiji updater.

The jar goes to `target/LEEMandPEEM-2.0.0.jar`. Install by copying to `<Fiji>/plugins/`.

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

## History

### v1.0.1 (original)
- OVFFormat, readOVF, writeOVF, getXYZmag, toSpherical, Vector3d included (later moved)
- Parent: pom-imagej 15.8.0

### v2.0.0 (April 2026)
- Restructured: magnetic/OVF plugins moved to jufiba/MagneticHelper
- Absorbed scifio-UView content (UView_reader, UView_Folder_Reader, plugins.config)
- Upgraded parent to pom-scijava 34.1.0
- plotIntensityVsTag rewritten with dynamic tag dropdown, formula field, Frame Number option
- scifio-UView repo archived on GitHub
